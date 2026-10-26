#!/usr/bin/env python3
"""Check that this repository still satisfies F-Droid's inclusion requirements.

F-Droid builds an app from a Git tag with its own toolchain and publishes it under its
own key, so "qualifying" is a set of facts about the repository rather than a single
approval:

* no non-free dependency, tracker or prebuilt binary anywhere in the tree;
* store metadata inside fdroidserver's character limits, with a changelog for the
  versionCode that is about to be tagged;
* a build recipe (`fdroid/<applicationId>.yml`) that agrees with `app/build.gradle.kts`;
* a release build type that is left unsigned, because F-Droid signs the APK itself.

This script checks all of those with nothing but the standard library, so it runs on a
developer machine, in the Android CI job, and in the release workflow before a tag is
created. It complements `tools/fdroid_lint.py`, which runs F-Droid's own tools
(`fdroid lint` and `fdroidserver.scanner.scan_source`) and needs fdroidserver installed.

Usage
-----
    tools/fdroid_check.py                 # check the repository, exit 1 on any error
    tools/fdroid_check.py --root PATH     # check a copy of the repository elsewhere
    tools/fdroid_check.py --quiet         # only print errors
    tools/fdroid_check.py --write-missing-changelog [--text "What changed"]
                                          # create the changelog for the current
                                          # versionCode if it does not exist yet

The character limits mirror `DEFAULT_CONFIG['char_limits']` in fdroidserver/common.py:
name 50, summary 80, description 4000, whatsNew 500. fdroidserver truncates anything
longer without failing, which is why the check has to happen here: a silently truncated
"What's new" on the listing is not something F-Droid's CI will ever report.
"""

from __future__ import annotations

import argparse
import os
import re
import struct
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

# fdroidserver's char_limits, keyed the way fdroidserver keys the localized fields.
CHAR_LIMITS = {"name": 50, "summary": 80, "description": 4000, "whatsNew": 500}

# F-Droid's client shows at most 30 characters of an app name; longer names are cut off.
TITLE_DISPLAY_LIMIT = 30

FASTLANE_DIR = Path("fastlane/metadata/android/en-US")
RECIPE_DIR = Path("fdroid")
GRADLE_FILE = Path("app/build.gradle.kts")
MANIFEST = Path("app/src/main/AndroidManifest.xml")

# Maven repositories fdroidserver's source scanner accepts. Anything else in a build file
# makes the F-Droid build fail with "unknown maven repo", so it is an error here too.
ALLOWED_MAVEN_HOSTS = (
    "repo1.maven.org/maven2",
    "repo.maven.apache.org/maven2",
    "maven.google.com",
    "plugins.gradle.org/m2",
    "jitpack.io",
    "www.jitpack.io",
    "clojars.org/repo",
    "repo.clojars.org",
    "oss.sonatype.org",
    "s01.oss.sonatype.org",
    "oss.jfrog.org",
    "s3.amazonaws.com/repo.commonsware.com",
)

# Dependency coordinates and import paths that make an app non-free. Each entry is a
# regex and the reason shown to whoever tripped it. Deliberately narrow: androidx.*,
# org.jetbrains.*, com.google.android.material and com.google.code.gson are all free
# software and must keep passing.
NONFREE_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = tuple(
    (re.compile(pattern, re.IGNORECASE), reason)
    for pattern, reason in (
        (r"com\.google\.android\.gms[:.]", "Google Play services"),
        (r"com\.google\.firebase[:.]", "Firebase"),
        (r"com\.android\.billingclient", "Google Play Billing"),
        (r"com\.google\.android\.play[:.]", "Google Play feature/asset delivery"),
        (r"com\.google\.mlkit", "Google ML Kit"),
        (r"com\.google\.android\.libraries\.places", "Google Places SDK"),
        (r"com\.google\.ads[:.]", "Google Mobile Ads"),
        (r"com\.facebook\.android", "Facebook SDK"),
        (r"com\.appsflyer", "AppsFlyer (tracker)"),
        (r"com\.adjust[:.]", "Adjust (tracker)"),
        (r"io\.branch\.referral", "Branch (tracker)"),
        (r"com\.flurry", "Flurry (tracker)"),
        (r"com\.crashlytics", "Crashlytics (tracker)"),
        (r"io\.fabric", "Fabric/Crashlytics (tracker)"),
        (r"com\.microsoft\.appcenter", "App Center (tracker)"),
        (r"com\.amazon\.device\.ads", "Amazon Mobile Ads"),
        (r"com\.unity3d\.ads", "Unity Ads"),
        (r"com\.ironsource", "ironSource Ads"),
        (r"com\.applovin", "AppLovin Ads"),
        (r"com\.mopub", "MoPub Ads"),
        (r"com\.onesignal", "OneSignal (tracker)"),
        (r"com\.huawei\.hms", "Huawei Mobile Services"),
    )
)

# The one Google string the manifest is allowed to keep. Android Auto reads this
# meta-data key to find a media app; it is a string, not a library, and it pulls no
# dependency into the build. fdroidserver's scanner does not flag it either.
ALLOWED_GMS_MANIFEST_KEY = "com.google.android.gms.car.application"

# Files fdroidserver deletes before building (it supplies its own wrapper), plus this
# project's intentional debug keystore. Keystores are a warning, not an error.
ALLOWED_JARS = (Path("gradle/wrapper/gradle-wrapper.jar"),)

BINARY_EXTENSIONS = (
    ".jar",
    ".aar",
    ".apk",
    ".dex",
    ".class",
    ".so",
    ".a",
    ".zip",
    ".gz",
    ".tgz",
    ".wasm",
)

SKIP_DIRS = {".git", "build", ".gradle", ".idea", ".kotlin", "node_modules", "__pycache__"}

SOURCE_EXTENSIONS = {".kt", ".kts", ".java", ".gradle", ".xml", ".toml", ".pro"}


@dataclass
class Report:
    """Collects what the checks found, so callers can decide how to report it."""

    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)

    def note(self, message: str) -> None:
        self.notes.append(message)

    @property
    def ok(self) -> bool:
        return not self.errors


def read_text(path: Path) -> str:
    """Read a file the way the checks need it: UTF-8, undecodable bytes replaced."""
    return path.read_text(encoding="utf-8", errors="replace")


def relative(path: Path, root: Path) -> str:
    """Format a path for a message: relative to the repository when possible."""
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def iter_files(root: Path) -> list[Path]:
    """Every tracked-looking file in the tree, skipping build output and VCS metadata."""
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            found.append(Path(dirpath, name).relative_to(root))
    return found


def git_output(root: Path, *args: str) -> str:
    """Run a git command in the repository, returning '' when git is unavailable."""
    try:
        done = subprocess.run(
            ["git", *args],
            cwd=root,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return ""
    return done.stdout.strip() if done.returncode == 0 else ""


# ---------------------------------------------------------------------------
# app/build.gradle.kts
# ---------------------------------------------------------------------------


def parse_gradle(root: Path, report: Report) -> dict[str, str]:
    """Pull applicationId, versionCode, versionName and the SDK levels out of Gradle."""
    path = root / GRADLE_FILE
    if not path.is_file():
        report.error(f"{GRADLE_FILE} is missing")
        return {}
    text = read_text(path)

    def find(pattern: str, label: str) -> str:
        match = re.search(pattern, text)
        if not match:
            report.error(f"{GRADLE_FILE}: could not find {label}")
            return ""
        return match.group(1)

    info = {
        "applicationId": find(r'applicationId\s*=\s*"([^"]+)"', "applicationId"),
        "versionCode": find(r"versionCode\s*=\s*(\d+)", "versionCode"),
        "versionName": find(r'versionName\s*=\s*"([^"]+)"', "versionName"),
        "minSdk": find(r"minSdk\s*=\s*(\d+)", "minSdk"),
        "targetSdk": find(r"targetSdk\s*=\s*(\d+)", "targetSdk"),
        "compileSdk": find(r"compileSdk\s*=\s*(\d+)", "compileSdk"),
    }

    # F-Droid signs the APK itself: a release signingConfig would mean the published app
    # is not the one that was reviewed, and an APK signed with a key from the repository
    # is exactly what the inclusion process exists to prevent.
    release_block = re.search(r"release\s*\{(.*?)\n        \}", text, re.DOTALL)
    if release_block is None:
        report.error(f"{GRADLE_FILE}: no release build type found; F-Droid builds release")
    elif "signingConfig" in release_block.group(1):
        report.error(
            f"{GRADLE_FILE}: the release build type sets a signingConfig. "
            "F-Droid needs an unsigned app-release-unsigned.apk so it can sign with "
            "its own key."
        )

    if info["targetSdk"] and int(info["targetSdk"]) < 34:
        report.warn(
            f"{GRADLE_FILE}: targetSdk {info['targetSdk']} is below 34. F-Droid asks "
            "apps to target a current Android release."
        )
    return info


# ---------------------------------------------------------------------------
# fdroid/<applicationId>.yml
# ---------------------------------------------------------------------------


def top_level_block(text: str, key: str) -> str:
    """Return the indented block that follows a top-level `key:` line."""
    match = re.search(rf"^{re.escape(key)}:\s*(?:\|[-+]?>?)?\s*$", text, re.MULTILINE)
    if match is None:
        match = re.search(rf"^{re.escape(key)}:[^\n]*\n", text, re.MULTILINE)
        if match is None:
            return ""
        return ""
    lines = []
    for line in text[match.end() :].splitlines():
        if line.strip() and not line.startswith((" ", "\t")):
            break
        lines.append(line)
    return "\n".join(lines)


def parse_recipe_block(text: str, key: str) -> str:
    """Return everything from `key:` up to the next top-level key, comments included."""
    start = re.search(rf"^{re.escape(key)}:", text, re.MULTILINE)
    if start is None:
        return ""
    rest = text[start.end() :]
    end = len(rest)
    for match in re.finditer(r"^[A-Za-z][A-Za-z0-9_]*:", rest, re.MULTILINE):
        end = match.start()
        break
    return text[start.start() : start.end() + end]


def parse_recipe(path: Path, report: Report) -> dict[str, object]:
    """Read the fields of the build recipe that have to agree with the Gradle build.

    The recipe is machine-formatted (`fdroid rewritemeta` output), which keeps this
    simple line-based reading reliable. When PyYAML happens to be installed the file is
    also parsed properly, to catch a recipe this reader cannot understand.
    """
    if not path.is_file():
        report.error(f"{path} is missing")
        return {}
    text = read_text(path)
    shown = path.name  # the caller prefixes the directory; keep messages short

    try:
        import yaml  # type: ignore

        yaml.safe_load(text)
    except ImportError:
        pass
    except Exception as exc:  # noqa: BLE001 - any YAML error is a recipe error
        report.error(f"{shown}: not valid YAML ({exc})")
        return {}

    def scalar(key: str) -> str:
        match = re.search(rf"^{re.escape(key)}:\s*(.+?)\s*$", text, re.MULTILINE)
        return match.group(1).strip("'\"") if match else ""

    builds_block = parse_recipe_block(text, "Builds")
    builds = []
    for chunk in re.split(r"^ {2}- ", builds_block, flags=re.MULTILINE)[1:]:
        entry = {}
        for key in ("versionName", "versionCode", "commit", "subdir"):
            match = re.search(rf"^\s*{key}:\s*(.+?)\s*$", chunk, re.MULTILINE)
            if match:
                entry[key] = match.group(1).strip("'\"")
        entry["gradle"] = "yes" if re.search(r"^\s*gradle:\s*\n\s*-\s*yes", chunk, re.MULTILINE) else ""
        builds.append(entry)

    recipe = {
        "License": scalar("License"),
        "RepoType": scalar("RepoType"),
        "Repo": scalar("Repo"),
        "SourceCode": scalar("SourceCode"),
        "IssueTracker": scalar("IssueTracker"),
        "Changelog": scalar("Changelog"),
        "AutoUpdateMode": scalar("AutoUpdateMode"),
        "UpdateCheckMode": scalar("UpdateCheckMode"),
        "CurrentVersion": scalar("CurrentVersion"),
        "CurrentVersionCode": scalar("CurrentVersionCode"),
        "Builds": builds,
        "Categories": re.findall(r"^ {2}- (.+?)\s*$", top_level_block(text, "Categories"), re.MULTILINE),
        "AntiFeatures": re.findall(
            r"^ {2}([A-Za-z][A-Za-z0-9]*):\s*$", parse_recipe_block(text, "AntiFeatures"), re.MULTILINE
        ),
    }
    for key in ("License", "Repo", "CurrentVersion", "CurrentVersionCode"):
        if not recipe[key]:
            report.error(f"{shown}: required field '{key}' is missing or empty")
    if not builds:
        report.error(f"{shown}: no Builds entries found")
    return recipe


def check_recipe(root: Path, recipe: dict[str, object], gradle: dict[str, str], report: Report) -> Path | None:
    """Cross-check the recipe against the Gradle build and the Git repository."""
    application_id = gradle.get("applicationId", "")
    path = root / RECIPE_DIR / f"{application_id}.yml" if application_id else None
    if path is None or not path.is_file():
        expected = f"{RECIPE_DIR}/{application_id or '<applicationId>'}.yml"
        report.error(
            f"build recipe not found at {expected}. F-Droid needs it to submit to "
            "fdroiddata as metadata/<applicationId>.yml."
        )
        return None

    shown = relative(path, root)

    if recipe.get("License") and (root / "LICENSE").is_file():
        first_line = read_text(root / "LICENSE").splitlines()[0] if read_text(root / "LICENSE") else ""
        if recipe["License"] not in first_line:
            report.error(
                f"recipe License '{recipe['License']}' does not match the LICENSE file "
                f"('{first_line}'). F-Droid only accepts FSF/OSI approved SPDX tags."
            )
    elif not (root / "LICENSE").is_file():
        report.error("LICENSE is missing; F-Droid requires a free-software license in the repository")

    if not recipe.get("Categories"):
        report.error(f"{shown}: Categories is empty; F-Droid rejects metadata without one")
    if not recipe.get("AntiFeatures"):
        report.note(
            f"{shown}: no AntiFeatures declared. Soundtrail talks to optional third-party "
            "services, so NonFreeNet and NonFreeAdd are expected here."
        )

    for url_field in ("Repo", "SourceCode", "IssueTracker", "Changelog"):
        url = str(recipe.get(url_field, ""))
        if url and not url.startswith("https://"):
            report.error(f"{shown}: {url_field} must use https:// (found '{url}')")

    remote = git_output(root, "remote", "get-url", "origin")
    repo = str(recipe.get("Repo", "")).removesuffix(".git")
    if remote and repo and not remote.startswith(repo):
        report.warn(
            f"{shown}: Repo '{repo}' does not match the git remote '{remote}'. F-Droid "
            "clones Repo, so a mismatch sends reviewers to the wrong repository."
        )

    version_code = gradle.get("versionCode", "")
    version_name = gradle.get("versionName", "")
    if str(recipe.get("CurrentVersionCode", "")) != version_code:
        report.error(
            f"{shown}: CurrentVersionCode {recipe.get('CurrentVersionCode')} does not match "
            f"{GRADLE_FILE} versionCode {version_code}. F-Droid builds the version the "
            "recipe names, so these have to move together."
        )
    if str(recipe.get("CurrentVersion", "")) != version_name:
        report.error(
            f"{shown}: CurrentVersion {recipe.get('CurrentVersion')} does not match "
            f"{GRADLE_FILE} versionName {version_name}."
        )

    builds = recipe.get("Builds") or []
    if builds:
        newest = builds[-1]
        if str(newest.get("versionCode", "")) != version_code:
            report.error(
                f"{shown}: the newest Builds entry is versionCode {newest.get('versionCode')}, "
                f"but the app is at {version_code}. AutoUpdateMode: Version only adds a "
                "build once CurrentVersionCode matches it."
            )
        if str(newest.get("versionName", "")) != version_name:
            report.error(
                f"{shown}: the newest Builds entry is versionName {newest.get('versionName')}, "
                f"but the app is at {version_name}."
            )
        commit = str(newest.get("commit", ""))
        if commit != f"v{version_name}":
            report.warn(
                f"{shown}: newest build commit '{commit}' is not the tag 'v{version_name}' "
                "this repository creates for that release."
            )
        if commit.startswith(("main", "master", "HEAD", "origin", "default", "trunk")):
            report.error(f"{shown}: build commit '{commit}' is a branch; F-Droid needs a tag or hash")
        if commit:
            if git_output(root, "tag", "-l", commit):
                report.note(f"{shown}: tag {commit} exists")
            elif commit == f"v{version_name}":
                report.note(
                    f"{shown}: tag {commit} does not exist yet. The release workflow creates "
                    "it when this version is published; open the fdroiddata merge request "
                    "afterwards, because F-Droid's CI has to be able to check the tag out."
                )
            else:
                report.error(f"{shown}: build commit '{commit}' is not a tag in this repository")
        for entry in builds:
            if entry.get("subdir") != "app":
                report.warn(f"{shown}: build {entry.get('versionName')} has subdir '{entry.get('subdir')}', expected 'app'")
            if entry.get("gradle") != "yes":
                report.error(f"{shown}: build {entry.get('versionName')} is missing 'gradle: yes'")

    if str(recipe.get("AutoUpdateMode", "")) not in ("Version", "None"):
        report.warn(f"{shown}: AutoUpdateMode '{recipe.get('AutoUpdateMode')}' is unusual for a tagged release flow")
    if str(recipe.get("UpdateCheckMode", "")).split()[0:1] != ["Tags"]:
        report.warn(f"{shown}: UpdateCheckMode '{recipe.get('UpdateCheckMode')}' is not 'Tags'")
    if str(recipe.get("RepoType", "")) != "git":
        report.error(f"{shown}: RepoType must be 'git'")
    return path


# ---------------------------------------------------------------------------
# fastlane store metadata
# ---------------------------------------------------------------------------


def png_size(path: Path) -> tuple[int, int] | None:
    """Return (width, height) for a PNG, or None when the file is not a PNG."""
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n") or len(data) < 24:
        return None
    width, height = struct.unpack(">II", data[16:24])
    return width, height


def truncate_words(text: str, limit: int) -> str:
    """Cut text to at most `limit` characters on a word boundary."""
    if len(text) <= limit:
        return text
    cut = text[: limit - 1]
    if " " in cut:
        cut = cut[: cut.rfind(" ")]
    return cut.rstrip(" ,;:.") + "…"


def check_fastlane(root: Path, version_code: str, report: Report) -> None:
    """Check the store listing F-Droid publishes, including every length limit."""
    base = root / FASTLANE_DIR
    if not base.is_dir():
        report.error(
            f"{FASTLANE_DIR} is missing. F-Droid reads the store listing from the "
            "repository, so without it the app has no description on f-droid.org."
        )
        return

    texts = {
        "title.txt": "name",
        "short_description.txt": "summary",
        "full_description.txt": "description",
    }
    for filename, key in texts.items():
        path = base / filename
        if not path.is_file():
            report.error(f"{FASTLANE_DIR / filename} is missing (fdroidserver reads it as '{key}')")
            continue
        content = read_text(path)
        limit = CHAR_LIMITS[key]
        stripped = content.strip()
        if not stripped:
            report.error(f"{FASTLANE_DIR / filename} is empty")
            continue
        if len(content) > limit:
            report.error(
                f"{FASTLANE_DIR / filename} is {len(content)} chars, over fdroidserver's "
                f"{limit} char '{key}' limit. F-Droid truncates it silently, so the "
                "listing would end mid-sentence."
            )
        if key == "name" and len(stripped) > TITLE_DISPLAY_LIMIT:
            report.warn(
                f"{FASTLANE_DIR / filename} is {len(stripped)} chars; F-Droid's client "
                f"displays about {TITLE_DISPLAY_LIMIT}."
            )
        if key == "description":
            for line in stripped.splitlines():
                if re.search(r"(^|[^[])\[([^ ]+)( |$)", line):
                    report.error(
                        f"{FASTLANE_DIR / filename}: line looks like a wiki link "
                        f"('{line.strip()[:60]}…'). `fdroid lint` rejects those in a "
                        "description."
                    )

    icon = base / "images" / "icon.png"
    if not icon.is_file():
        report.error(f"{FASTLANE_DIR / 'images/icon.png'} is missing; F-Droid needs a store icon")
    else:
        size = png_size(icon)
        if size is None:
            report.error(f"{icon.relative_to(root)} is not a PNG")
        else:
            width, height = size
            if width != height:
                report.error(f"{icon.relative_to(root)} is {width}x{height}; the store icon must be square")
            elif width < 48:
                report.error(f"{icon.relative_to(root)} is {width}px wide; F-Droid needs at least 48px")
            elif width != 512:
                report.warn(f"{icon.relative_to(root)} is {width}px; 512x512 is what f-droid.org displays")
            else:
                report.note(f"{icon.relative_to(root)} is 512x512")

    changelogs = base / "changelogs"
    found_codes: set[str] = set()
    if not changelogs.is_dir():
        report.error(f"{FASTLANE_DIR / 'changelogs'} is missing")
    else:
        for path in sorted(changelogs.iterdir()):
            if not path.is_file():
                continue
            stem = path.stem
            if not stem.isdigit():
                report.error(
                    f"{path.relative_to(root)}: changelog filenames must be a versionCode "
                    "(for example 18.txt), not '%s'" % stem
                )
                continue
            found_codes.add(stem)
            content = read_text(path)
            limit = CHAR_LIMITS["whatsNew"]
            if len(content) > limit:
                report.error(
                    f"{path.relative_to(root)} is {len(content)} chars, over the {limit} "
                    "char 'whatsNew' limit. fdroidserver truncates it without warning."
                )
            if not content.strip():
                report.error(f"{path.relative_to(root)} is empty")

    if version_code and version_code not in found_codes:
        report.error(
            f"{changelogs / (version_code + '.txt')} is missing. F-Droid shows 'What's new' "
            "per versionCode, so the release that is about to be tagged needs one."
        )
    elif version_code:
        report.note(f"changelog for versionCode {version_code} is present")

    if not (base / "images" / "phoneScreenshots").is_dir():
        report.warn(
            f"{FASTLANE_DIR / 'images/phoneScreenshots'} does not exist. Screenshots are "
            "optional for inclusion, but the listing is text-only without them."
        )


def write_missing_changelog(root: Path, version_code: str, text: str, report: Report) -> bool:
    """Create the changelog for `version_code` when the release did not ship one.

    The release workflow calls this after the version bump, so a tag can never be created
    without an F-Droid 'What's new' entry. Returns True when a file was written.
    """
    if not version_code:
        report.error("cannot write a changelog without a versionCode")
        return False
    path = root / FASTLANE_DIR / "changelogs" / f"{version_code}.txt"
    if path.is_file():
        report.note(f"{path.relative_to(root)} already exists; leaving it alone")
        return False
    body = (text or "").strip() or "Bug fixes and improvements."
    body = truncate_words(body, CHAR_LIMITS["whatsNew"] - 1)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body + "\n", encoding="utf-8")
    report.note(f"wrote {path.relative_to(root)} ({len(body) + 1} chars)")
    return True


# ---------------------------------------------------------------------------
# non-free code and binaries
# ---------------------------------------------------------------------------


def check_sources(root: Path, report: Report) -> None:
    """Scan the tree for non-free libraries, trackers, prebuilt binaries and odd repos."""
    gradle_files = 0
    for relative in iter_files(root):
        path = root / relative
        name = relative.name
        as_posix = relative.as_posix()

        if name == "google-services.json":
            report.error(f"{as_posix}: google-services.json belongs to Firebase/Play services and has no place here")

        if name.endswith(BINARY_EXTENSIONS) and not name.endswith(".so.txt"):
            if relative in ALLOWED_JARS:
                report.note(f"{as_posix} is the Gradle wrapper; fdroidserver replaces it with its own")
                continue
            if re.match(r".*\.so(\..+)*$", name):
                report.error(f"{as_posix}: prebuilt shared library. F-Droid builds from source only")
            elif name.endswith((".jar", ".aar")):
                report.error(f"{as_posix}: prebuilt Java/Android library. F-Droid builds from source only")
            elif name.endswith(".apk"):
                report.error(f"{as_posix}: committed APK. F-Droid builds the APK itself")
            else:
                report.error(f"{as_posix}: binary archive or compiled code in the source tree")
            continue

        if name.endswith((".p12", ".jks", ".keystore")):
            report.warn(
                f"{as_posix}: a keystore is committed. This repository's is the debug key "
                "with the public Android credentials (androiddebugkey / android) and only "
                "signs debug builds - F-Droid signs its own release APKs. Anything else "
                "here must go."
            )
            continue

        if relative.suffix not in SOURCE_EXTENSIONS:
            continue

        text = read_text(path)
        is_gradle = name.endswith((".gradle", ".gradle.kts")) or name == "libs.versions.toml"

        noted_auto_key = False
        for number, line in enumerate(text.splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith(("//", "#", "*", "/*", "<!--")):
                continue
            for pattern, reason in NONFREE_PATTERNS:
                if not pattern.search(line):
                    continue
                # The Android Auto meta-data key is a string Android reads to find a media
                # app, not a dependency, and fdroidserver's scanner does not flag it. It is
                # documented in the recipe's MaintainerNotes, so it stays a note here.
                if ALLOWED_GMS_MANIFEST_KEY in line:
                    if not noted_auto_key:
                        report.note(
                            f"{as_posix}: keeps the Android Auto meta-data key "
                            f"{ALLOWED_GMS_MANIFEST_KEY} (a string, not a library)"
                        )
                        noted_auto_key = True
                    continue
                report.error(f"{as_posix}:{number}: {reason} ({stripped[:90]})")

        if is_gradle:
            gradle_files += 1
            for url in re.findall(
                r"""(?:setUrl|\burl)\s*(?:\(|=)\s*(?:uri\s*\()?\s*["']([^"']+)["']""", text
            ):
                if url.startswith("file:"):
                    continue
                if not any(host in url for host in ALLOWED_MAVEN_HOSTS):
                    report.error(
                        f"{as_posix}: maven repository '{url}' is not one fdroidserver "
                        "accepts; its scanner fails the build with 'unknown maven repo'."
                    )
    if gradle_files == 0:
        report.error("no Gradle build files found; is this the right repository root?")


def check_build_infrastructure(root: Path, report: Report) -> None:
    """The things F-Droid's buildserver needs to be able to run the build at all."""
    gradlew = root / "gradlew"
    if not gradlew.is_file():
        report.error("gradlew is missing; F-Droid builds with the committed Gradle wrapper")
    elif not os.access(gradlew, os.X_OK):
        report.error("gradlew is not executable; F-Droid's buildserver runs it directly")

    wrapper_jar = root / "gradle/wrapper/gradle-wrapper.jar"
    if not wrapper_jar.is_file():
        report.error("gradle/wrapper/gradle-wrapper.jar is missing")
    elif wrapper_jar.read_bytes()[:64].startswith(b"version https://git-lfs"):
        report.error(
            "gradle/wrapper/gradle-wrapper.jar is a Git LFS pointer. F-Droid clones "
            "without LFS, so the build would get a text file instead of the wrapper."
        )

    wrapper_properties = root / "gradle/wrapper/gradle-wrapper.properties"
    if wrapper_properties.is_file():
        url = re.search(r"distributionUrl=(.+)", read_text(wrapper_properties))
        if url and "services.gradle.org/distributions" not in url.group(1):
            report.warn(f"gradle-wrapper.properties: distributionUrl '{url.group(1)}' is not a Gradle release archive")

    gitattributes = root / ".gitattributes"
    if gitattributes.is_file() and "filter=lfs" in read_text(gitattributes):
        report.error(
            ".gitattributes uses Git LFS. F-Droid clones the repository without LFS, so "
            "every LFS file arrives as a pointer and the build fails."
        )

    manifest = root / MANIFEST
    if not manifest.is_file():
        report.error(f"{MANIFEST} is missing")
    else:
        text = read_text(manifest)
        if "android.permission.QUERY_ALL_PACKAGES" in text:
            report.error(
                f"{MANIFEST}: QUERY_ALL_PACKAGES is declared. F-Droid rejects it; list the "
                "packages you need in <queries> instead."
            )
        if "android:debuggable=\"true\"" in text:
            report.error(f"{MANIFEST}: the manifest forces a debuggable app")


# ---------------------------------------------------------------------------
# entry point
# ---------------------------------------------------------------------------


def run_checks(root: Path, report: Report) -> None:
    """Run every check against `root`."""
    gradle = parse_gradle(root, report)
    recipe_path = root / RECIPE_DIR / f"{gradle.get('applicationId', '')}.yml"
    recipe = parse_recipe(recipe_path, report) if gradle.get("applicationId") else {}
    check_recipe(root, recipe, gradle, report)
    check_fastlane(root, gradle.get("versionCode", ""), report)
    check_sources(root, report)
    check_build_infrastructure(root, report)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".", help="repository root to check (default: cwd)")
    parser.add_argument("--quiet", action="store_true", help="print errors only")
    parser.add_argument(
        "--write-missing-changelog",
        action="store_true",
        help="create fastlane/.../changelogs/<versionCode>.txt when it is missing",
    )
    parser.add_argument(
        "--text",
        default="",
        help="changelog text for --write-missing-changelog (truncated to the limit)",
    )
    options = parser.parse_args(argv)

    root = Path(options.root).resolve()
    if not (root / GRADLE_FILE).is_file():
        print(f"error: {root} does not look like the SoundTrail repository", file=sys.stderr)
        return 2

    report = Report()
    if options.write_missing_changelog:
        gradle = parse_gradle(root, Report())
        write_missing_changelog(root, gradle.get("versionCode", ""), options.text, report)

    run_checks(root, report)

    if not options.quiet:
        for note in report.notes:
            print(f"note: {note}")
        for warning in report.warnings:
            print(f"warning: {warning}")
    for error in report.errors:
        print(f"error: {error}", file=sys.stderr)

    if report.errors:
        print(
            f"\n{len(report.errors)} F-Droid requirement(s) not met "
            f"({len(report.warnings)} warning(s)).",
            file=sys.stderr,
        )
        return 1
    if not options.quiet:
        print(
            f"\nF-Droid requirements met ({len(report.warnings)} warning(s), "
            f"{len(report.notes)} note(s))."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
