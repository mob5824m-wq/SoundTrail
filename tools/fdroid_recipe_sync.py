#!/usr/bin/env python3
"""Keep fdroid/<applicationId>.yml in step with the version being released.

The recipe in this repository is the file that gets copied into fdroiddata, and
`tools/fdroid_check.py` requires it to name the version in `app/build.gradle.kts` —
F-Droid builds whatever the recipe's newest Builds entry points at, so a recipe that
lags the app would have F-Droid rebuild an old release, or nothing at all.

That invariant cannot hold by hand in this repository's release flow: `.github/scripts/
bump_version.py` moves versionCode and versionName at release time, after every pull
request has already merged. So the release workflow runs this script right after the
bump and commits the result with it, and the recipe stays a faithful copy of what
fdroiddata should hold for the tag F-Droid is about to be pointed at.

The output stays byte-for-byte what `fdroid rewritemeta` writes: entries are appended in
the same shape, separated by a blank line, and only CurrentVersion and
CurrentVersionCode are rewritten in place. `fdroid lint -f` in CI is what proves that —
it fails if this script ever produces something fdroiddata's tooling would reformat.

Usage
-----
    tools/fdroid_recipe_sync.py            # update the recipe for the current version
    tools/fdroid_recipe_sync.py --check    # exit 1 if the recipe is not in step
    tools/fdroid_recipe_sync.py --root PATH
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

GRADLE_FILE = Path("app/build.gradle.kts")
RECIPE_DIR = Path("fdroid")

BUILD_ENTRY = """  - versionName: {name}
    versionCode: {code}
    commit: v{name}
    subdir: app
    gradle:
      - yes
"""

# The next top-level key ends the Builds block; MaintainerNotes follows it in the
# canonical layout, but this does not assume which key that is.
NEXT_TOP_LEVEL_KEY = re.compile(r"^[A-Za-z][A-Za-z0-9_]*:", re.MULTILINE)


def read_version(root: Path) -> tuple[str, str, str]:
    """Return (applicationId, versionName, versionCode) from the Gradle build."""
    text = (root / GRADLE_FILE).read_text(encoding="utf-8", errors="replace")
    fields = {}
    for key, pattern in (
        ("applicationId", r'applicationId\s*=\s*"([^"]+)"'),
        ("versionName", r'versionName\s*=\s*"([^"]+)"'),
        ("versionCode", r"versionCode\s*=\s*(\d+)"),
    ):
        match = re.search(pattern, text)
        if not match:
            raise SystemExit(f"error: {GRADLE_FILE}: could not find {key}")
        fields[key] = match.group(1)
    return fields["applicationId"], fields["versionName"], fields["versionCode"]


def existing_version_codes(builds_body: str) -> set[str]:
    """The versionCodes the recipe already has a build entry for."""
    return set(re.findall(r"^\s*versionCode:\s*(\d+)\s*$", builds_body, re.MULTILINE))


def sync_recipe(text: str, version_name: str, version_code: str) -> tuple[str, list[str]]:
    """Return the updated recipe text and a list of what changed."""
    changes: list[str] = []

    current_code = re.search(r"^CurrentVersionCode:\s*(\d+)\s*$", text, re.MULTILINE)
    if current_code and int(current_code.group(1)) > int(version_code):
        raise SystemExit(
            f"error: the recipe is at versionCode {current_code.group(1)}, newer than the "
            f"app's {version_code}. Not rewriting it backwards; if the app version was "
            "rolled back on purpose, update the recipe by hand."
        )

    builds = re.search(r"(?ms)^Builds:\n(.*?)(?=^[A-Za-z][A-Za-z0-9_]*:)", text)
    if builds is None:
        raise SystemExit("error: the recipe has no Builds block to update")

    body = builds.group(1)
    if version_code not in existing_version_codes(body):
        entry = BUILD_ENTRY.format(name=version_name, code=version_code)
        new_body = body.rstrip("\n") + "\n\n" + entry + "\n"
        text = text[: builds.start(1)] + new_body + text[builds.end(1) :]
        changes.append(f"added the build entry for {version_name} (versionCode {version_code})")

    for key, value in (("CurrentVersion", version_name), ("CurrentVersionCode", version_code)):
        updated, count = re.subn(rf"^{key}:.*$", f"{key}: {value}", text, count=1, flags=re.MULTILINE)
        if count == 0:
            raise SystemExit(f"error: the recipe has no {key} line to update")
        if updated != text:
            changes.append(f"{key} -> {value}")
        text = updated

    return text, changes


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".", help="repository root (default: cwd)")
    parser.add_argument(
        "--check",
        action="store_true",
        help="exit 1 when the recipe is not in step, without changing it",
    )
    options = parser.parse_args(argv)

    root = Path(options.root).resolve()
    appid, version_name, version_code = read_version(root)
    recipe = root / RECIPE_DIR / f"{appid}.yml"
    if not recipe.is_file():
        raise SystemExit(f"error: {recipe} is missing")

    before = recipe.read_text(encoding="utf-8")
    after, changes = sync_recipe(before, version_name, version_code)

    if not changes:
        print(f"{recipe.relative_to(root)} is in step with {version_name} (versionCode {version_code})")
        return 0
    if options.check:
        print(
            f"error: {recipe.relative_to(root)} is not in step with {version_name} "
            f"(versionCode {version_code}): " + "; ".join(changes),
            file=sys.stderr,
        )
        return 1

    recipe.write_text(after, encoding="utf-8")
    print(f"{recipe.relative_to(root)}: " + "; ".join(changes))
    return 0


if __name__ == "__main__":
    sys.exit(main())
