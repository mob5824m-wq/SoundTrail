#!/usr/bin/env python3
"""Run F-Droid's own tools against this repository before submitting a recipe.

`tools/fdroid_check.py` encodes F-Droid's requirements in this repository's own code and
needs nothing but the standard library. This script runs the real thing instead, so the
answer does not depend on that encoding staying correct:

1. `fdroid lint -f fdroid/<applicationId>.yml` inside a throwaway workspace that looks
   like a checkout of https://gitlab.com/fdroid/fdroiddata. The `-f` also requires the
   recipe to be byte-for-byte what `fdroid rewritemeta` would write, which is the format
   fdroiddata expects to receive.
2. `fdroidserver.scanner.scan_source()` over a copy of the tree. This is the scan
   `fdroid build` runs before compiling: it reports non-free libraries and trackers,
   unknown Maven repositories, and prebuilt binaries, and it deletes the Gradle wrapper
   because F-Droid supplies its own.

The workspace configuration comes from `fdroid/lint-config/`, a minimal stand-in for
fdroiddata's `config.yml`, `config/categories.yml` and `config/antiFeatures.yml`. Without
those two identifier lists `fdroid lint` calls every category and anti-feature invalid,
which is a property of the stub rather than of the recipe.

Usage
-----
    tools/fdroid_lint.py                 # check, and explain how to install fdroidserver
    tools/fdroid_lint.py --require       # fail when fdroidserver is not installed (CI)
    tools/fdroid_lint.py --keep          # print the workspace path and leave it behind
    tools/fdroid_lint.py --root PATH     # check a copy of the repository elsewhere

Installing fdroidserver:

    python3 -m venv .venv-fdroid
    .venv-fdroid/bin/pip install fdroidserver
    .venv-fdroid/bin/python tools/fdroid_lint.py
"""

from __future__ import annotations

import argparse
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


class _ApksignerNoticeFilter(logging.Filter):
    """Drop fdroidserver's 'apksigner not found' notice.

    linting a recipe and scanning source never sign anything, so the missing Android SDK
    tool is noise here - and in CI it reads like a failure.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        return "apksigner not found" not in record.getMessage()


logging.getLogger().addFilter(_ApksignerNoticeFilter())

RECIPE_DIR = Path("fdroid")
LINT_CONFIG_DIR = RECIPE_DIR / "lint-config"
FASTLANE_BASE = Path("fastlane/metadata/android")
GRADLE_FILE = Path("app/build.gradle.kts")

INSTALL_HINT = """fdroidserver is not installed, so F-Droid's own tools could not run.
Install it into a virtualenv and re-run this script with that interpreter:

    python3 -m venv .venv-fdroid
    .venv-fdroid/bin/pip install fdroidserver
    .venv-fdroid/bin/python tools/fdroid_lint.py

tools/fdroid_check.py covers the same requirements without that dependency and did run."""


def application_id(root: Path) -> str:
    """Read the applicationId the recipe filename and the F-Droid package name come from."""
    text = (root / GRADLE_FILE).read_text(encoding="utf-8", errors="replace")
    match = re.search(r'applicationId\s*=\s*"([^"]+)"', text)
    if not match:
        raise SystemExit(f"error: no applicationId in {GRADLE_FILE}")
    return match.group(1)


def build_workspace(root: Path, appid: str, workspace: Path) -> Path:
    """Lay the recipe and store metadata out the way fdroiddata holds them."""
    recipe = root / RECIPE_DIR / f"{appid}.yml"
    if not recipe.is_file():
        raise SystemExit(f"error: {recipe} is missing")
    config = root / LINT_CONFIG_DIR / "config.yml"
    if not config.is_file():
        raise SystemExit(f"error: {config} is missing; it stubs fdroiddata's configuration")

    (workspace / "metadata").mkdir(parents=True, exist_ok=True)
    shutil.copy2(config, workspace / "config.yml")
    shutil.copytree(root / LINT_CONFIG_DIR / "config", workspace / "config")
    shutil.copy2(recipe, workspace / "metadata" / recipe.name)

    # fdroiddata keeps an app's store metadata beside its recipe, in the same layout
    # fastlane uses. Copying it here lets lint see the listing F-Droid would publish.
    fastlane = root / FASTLANE_BASE
    if fastlane.is_dir():
        target = workspace / "metadata" / appid
        shutil.copytree(fastlane, target)
    return recipe


# fdroidserver logs through Python logging: "2026-10-26 06:33:03,321 INFO: Using ...".
# Those lines are the tool talking to itself, not findings about the recipe, so they must
# not decide the verdict - a runner that happens to have the Android SDK installed logs
# "Using .../apksigner" and one that does not logs "apksigner not found". `fdroid lint`
# exits non-zero when it has something to say about the metadata, and that is the answer.
LOG_LINE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d+ [A-Z]+: ")


def run_lint(workspace: Path, appid: str) -> tuple[int, str]:
    """Run `fdroid lint -f` on one recipe; returns (exit code, findings)."""
    done = subprocess.run(
        [sys.executable, "-m", "fdroidserver", "lint", "-f", appid],
        cwd=workspace,
        capture_output=True,
        text=True,
        check=False,
    )
    lines = [
        line
        for line in (done.stdout + done.stderr).splitlines()
        if "apksigner" not in line and not LOG_LINE.match(line)
    ]
    return done.returncode, "\n".join(lines).strip()


def copy_tree_for_scan(root: Path, dest: Path) -> None:
    """Copy the tree the way F-Droid sees it: what git has, and nothing else.

    F-Droid clones the repository, so untracked and ignored files are not part of the
    build. Scanning them reports problems in files that never reach F-Droid - a local
    virtualenv alone produces over a hundred "binary" and "executable binary, possibly
    code" findings from its own dependencies.

    `--others --exclude-standard` keeps new, not-yet-committed files in the scan (so a
    freshly added non-free library is still caught) while leaving ignored paths such as
    .venv-fdroid/ and build output out.
    """
    listing = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=root,
        capture_output=True,
        text=True,
        check=False,
    )
    if listing.returncode != 0 or not listing.stdout:
        shutil.copytree(
            root,
            dest,
            ignore=shutil.ignore_patterns(
                ".git", "build", ".gradle", ".venv*", "venv", "node_modules", "__pycache__"
            ),
        )
        return

    copied = 0
    for rel in listing.stdout.split("\0"):
        if not rel:
            continue
        source = root / rel
        if not source.is_file() or source.is_symlink():
            continue
        target = dest / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        copied += 1
    if copied == 0:
        raise SystemExit("error: git listed no files to scan; is --root a repository?")


def run_scanner(root: Path, workspace: Path) -> tuple[int, list, list]:
    """Run fdroidserver's source scanner over a copy of the tree.

    The scan deletes the files F-Droid replaces (gradlew, gradle-wrapper.jar), so it must
    never be pointed at the working copy.
    """
    from fdroidserver import common, metadata, scanner  # type: ignore

    source = Path(tempfile.mkdtemp(prefix="fdroid-scan-src-"))
    copy = source / "repo"
    copy.mkdir()
    copy_tree_for_scan(root, copy)

    common.read_config()
    store = scanner.MessageStore()
    problems = scanner.scan_source(str(copy), metadata.Build(), store)
    shutil.rmtree(source, ignore_errors=True)
    return problems, store.errors, store.warnings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".", help="repository root to check (default: cwd)")
    parser.add_argument("--require", action="store_true", help="fail when fdroidserver is missing")
    parser.add_argument("--keep", action="store_true", help="leave the temporary workspace behind")
    options = parser.parse_args(argv)

    root = Path(options.root).resolve()
    appid = application_id(root)

    try:
        import fdroidserver  # noqa: F401
    except ImportError:
        print(INSTALL_HINT)
        return 1 if options.require else 0

    workspace = Path(tempfile.mkdtemp(prefix="fdroid-lint-"))
    failures = 0
    try:
        build_workspace(root, appid, workspace)

        print(f"== fdroid lint -f {appid} ==")
        code, findings = run_lint(workspace, appid)
        if code == 0:
            print("clean: no lint findings, and the recipe is in `fdroid rewritemeta` format")
            if findings:
                print(findings)
        else:
            if findings:
                print(findings)
            print(f"FAILED: `fdroid lint` exited {code}")
            failures += 1

        print("\n== fdroidserver.scanner.scan_source ==")
        # The scanner reads its signature data through the config in the workspace, so it
        # has to run with that as the working directory.
        previous = os.getcwd()
        os.chdir(workspace)
        try:
            problems, errors, warnings = run_scanner(root, workspace)
        finally:
            os.chdir(previous)
        for item in errors:
            print(f"error: {item[0]} at {item[1]}")
        for item in warnings:
            print(f"warning: {item[0]} at {item[1]}")
        if problems:
            print(f"FAILED: the source scanner reported {problems} problem(s)")
            failures += 1
        else:
            print("clean: no non-free libraries, trackers, unknown Maven repos or prebuilt binaries")
    finally:
        if options.keep:
            print(f"\nworkspace kept at {workspace}")
        else:
            shutil.rmtree(workspace, ignore_errors=True)

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
