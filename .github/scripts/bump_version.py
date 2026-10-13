#!/usr/bin/env python3
"""Select the Android version for a release.

The first release uses the version already configured in `app/build.gradle.kts`, as
long as its `vX.Y.Z` tag does not exist yet. Once that version has been tagged, each
release increments versionCode and the patch version, skipping any already-used tags.
Reports the selected version and APK asset name through `$GITHUB_OUTPUT` when set.

Usage: bump_version.py [repository root]   (defaults to the current directory)
"""

import os
import re
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
gradle_file = root / "app" / "build.gradle.kts"

text = gradle_file.read_text(encoding="utf-8")
code_match = re.search(r"versionCode\s*=\s*(\d+)", text)
name_match = re.search(r'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"', text)
if not code_match or not name_match:
    sys.exit(f"Could not find versionCode/versionName in {gradle_file}")

version_code = int(code_match.group(1))
major, minor, patch = (int(part) for part in name_match.groups())
current_name = f"{major}.{minor}.{patch}"

# Tags are never moved. Use the configured version for its first release; only bump
# after that exact version has already been released in this repository.
taken = set(
    subprocess.run(
        ["git", "tag", "--list"], cwd=root, capture_output=True, text=True, check=True
    ).stdout.split()
)
if f"v{current_name}" in taken:
    patch += 1
    while f"v{major}.{minor}.{patch}" in taken:
        patch += 1
    version_name = f"{major}.{minor}.{patch}"
    version_code += 1
    text = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {version_code}", text, count=1)
    text = re.sub(
        r'versionName\s*=\s*"\d+\.\d+\.\d+"', f'versionName = "{version_name}"', text, count=1
    )
    gradle_file.write_text(text, encoding="utf-8")
    print(f"versionCode {code_match.group(1)} -> {version_code}")
    print(f"versionName {current_name} -> {version_name}")
else:
    version_name = current_name
    print(f"Using untagged version {version_name} for its first release (versionCode {version_code}).")

asset_name = f"soundtrail-{version_name}-debug.apk"
output = os.environ.get("GITHUB_OUTPUT")
if output:
    with open(output, "a", encoding="utf-8") as handle:
        handle.write(f"version={version_name}\n")
        handle.write(f"asset={asset_name}\n")
