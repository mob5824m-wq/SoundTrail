#!/usr/bin/env python3
"""Tests for tools/fdroid_check.py.

The checks guard the release pipeline, so a check that silently passes when it should
fail is worse than no check at all. Each test builds a small throwaway repository that
satisfies every F-Droid requirement, then breaks exactly one requirement and asserts the
checker reports it.

Run with:  python3 -m unittest discover -s tools -p 'test_*.py' -v
"""

from __future__ import annotations

import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fdroid_check  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[1]

APP_ID = "tech.egrie.soundtrail"
VERSION_CODE = "18"
VERSION_NAME = "1.0.17"

GRADLE = '''plugins {
    id("com.android.application")
}

android {
    namespace = "tech.egrie.soundtrail"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.egrie.soundtrail"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "1.0.17"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sharedDebug")
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
'''

RECIPE = f'''AntiFeatures:
  NonFreeAdd:
    en-US: Hands songs to proprietary players.
  NonFreeNet:
    en-US: Optional third-party services.
Categories:
  - Multimedia
  - Navigation
License: MIT
AuthorName: egrie-tech
SourceCode: https://github.com/example/soundtrail
IssueTracker: https://github.com/example/soundtrail/issues
Changelog: https://github.com/example/soundtrail/releases

AutoName: Soundtrail

RepoType: git
Repo: https://github.com/example/soundtrail.git

Builds:
  - versionName: {VERSION_NAME}
    versionCode: {VERSION_CODE}
    commit: v{VERSION_NAME}
    subdir: app
    gradle:
      - yes

MaintainerNotes: |-
  The release APK is unsigned so F-Droid signs it.

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: {VERSION_NAME}
CurrentVersionCode: {VERSION_CODE}
'''

MANIFEST = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:label="Soundtrail">
        <meta-data
            android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc" />
    </application>
</manifest>
'''


def fake_png(width: int, height: int) -> bytes:
    """A byte string with a valid PNG signature and IHDR, enough for the size check."""
    return (
        b"\x89PNG\r\n\x1a\n"
        + struct.pack(">I", 13)
        + b"IHDR"
        + struct.pack(">II", width, height)
        + b"\x08\x06\x00\x00\x00"
        + struct.pack(">I", 0)
    )


def make_repo(root: Path) -> Path:
    """Write a repository that passes every check. Returns the root."""
    (root / "app" / "src" / "main").mkdir(parents=True, exist_ok=True)
    (root / "app" / "build.gradle.kts").write_text(GRADLE, encoding="utf-8")
    (root / "app" / "src" / "main" / "AndroidManifest.xml").write_text(MANIFEST, encoding="utf-8")
    (root / "LICENSE").write_text("MIT License\n\nCopyright (c) 2026 egrie-tech\n", encoding="utf-8")
    (root / "fdroid").mkdir(exist_ok=True)
    (root / "fdroid" / f"{APP_ID}.yml").write_text(RECIPE, encoding="utf-8")

    fastlane = root / fdroid_check.FASTLANE_DIR
    (fastlane / "images").mkdir(parents=True, exist_ok=True)
    (fastlane / "changelogs").mkdir(parents=True, exist_ok=True)
    (fastlane / "title.txt").write_text("Soundtrail\n", encoding="utf-8")
    (fastlane / "short_description.txt").write_text("Pin songs to places on a map.\n", encoding="utf-8")
    (fastlane / "full_description.txt").write_text("A longer description.\n", encoding="utf-8")
    (fastlane / "images" / "icon.png").write_bytes(fake_png(512, 512))
    (fastlane / "changelogs" / f"{VERSION_CODE}.txt").write_text("What changed.\n", encoding="utf-8")

    wrapper = root / "gradle" / "wrapper"
    wrapper.mkdir(parents=True, exist_ok=True)
    (wrapper / "gradle-wrapper.jar").write_bytes(b"PK\x03\x04" + b"\x00" * 64)
    (wrapper / "gradle-wrapper.properties").write_text(
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip\n",
        encoding="utf-8",
    )
    gradlew = root / "gradlew"
    gradlew.write_text("#!/bin/sh\n", encoding="utf-8")
    gradlew.chmod(0o755)
    (root / ".gitattributes").write_text("*.p12 binary\n", encoding="utf-8")
    return root


def check(root: Path) -> fdroid_check.Report:
    report = fdroid_check.Report()
    fdroid_check.run_checks(root, report)
    return report


class CleanRepositoryTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = make_repo(Path(self._tmp.name))
        self.addCleanup(self._tmp.cleanup)

    def test_clean_repository_has_no_errors(self) -> None:
        report = check(self.root)
        self.assertEqual([], report.errors)
        self.assertTrue(report.ok)

    def test_main_returns_zero_for_clean_repository(self) -> None:
        self.assertEqual(0, fdroid_check.main(["--root", str(self.root), "--quiet"]))

    def test_debug_keystore_is_a_warning_not_an_error(self) -> None:
        (self.root / "keystore").mkdir(exist_ok=True)
        (self.root / "keystore" / "soundtrail-debug.p12").write_bytes(b"\x30\x82")
        report = check(self.root)
        self.assertEqual([], report.errors)
        self.assertTrue(any("keystore" in w for w in report.warnings))


class RequirementFailureTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = make_repo(Path(self._tmp.name))
        self.addCleanup(self._tmp.cleanup)

    def assertErrorContains(self, needle: str) -> None:
        report = check(self.root)
        joined = "\n".join(report.errors)
        self.assertIn(needle, joined, f"expected an error mentioning {needle!r}, got:\n{joined}")
        self.assertFalse(report.ok)

    # -- store metadata ----------------------------------------------------
    def test_overlong_changelog_fails(self) -> None:
        path = self.root / fdroid_check.FASTLANE_DIR / "changelogs" / f"{VERSION_CODE}.txt"
        path.write_text("x" * 501 + "\n", encoding="utf-8")
        self.assertErrorContains("over the 500 char 'whatsNew' limit")

    def test_changelog_at_exactly_the_limit_passes(self) -> None:
        path = self.root / fdroid_check.FASTLANE_DIR / "changelogs" / f"{VERSION_CODE}.txt"
        path.write_text("x" * 499 + "\n", encoding="utf-8")
        self.assertEqual([], check(self.root).errors)

    def test_missing_changelog_for_current_version_fails(self) -> None:
        (self.root / fdroid_check.FASTLANE_DIR / "changelogs" / f"{VERSION_CODE}.txt").unlink()
        self.assertErrorContains(f"{VERSION_CODE}.txt is missing")

    def test_changelog_named_after_a_version_name_fails(self) -> None:
        changelogs = self.root / fdroid_check.FASTLANE_DIR / "changelogs"
        (changelogs / f"{VERSION_NAME}.txt").write_text("Nope.\n", encoding="utf-8")
        self.assertErrorContains("changelog filenames must be a versionCode")

    def test_overlong_short_description_fails(self) -> None:
        path = self.root / fdroid_check.FASTLANE_DIR / "short_description.txt"
        path.write_text("y" * 81 + "\n", encoding="utf-8")
        self.assertErrorContains("over fdroidserver's 80 char 'summary' limit")

    def test_overlong_title_warns_but_does_not_fail(self) -> None:
        path = self.root / fdroid_check.FASTLANE_DIR / "title.txt"
        path.write_text("Soundtrail the very best map music app\n", encoding="utf-8")
        report = check(self.root)
        self.assertEqual([], report.errors)
        self.assertTrue(any("displays about 30" in w for w in report.warnings))

    def test_overlong_full_description_fails(self) -> None:
        path = self.root / fdroid_check.FASTLANE_DIR / "full_description.txt"
        path.write_text("z" * 4001 + "\n", encoding="utf-8")
        self.assertErrorContains("over fdroidserver's 4000 char 'description' limit")

    def test_wiki_link_in_description_fails(self) -> None:
        path = self.root / fdroid_check.FASTLANE_DIR / "full_description.txt"
        path.write_text("See [the docs](https://example.net) for more.\n", encoding="utf-8")
        self.assertErrorContains("looks like a wiki link")

    def test_missing_icon_fails(self) -> None:
        (self.root / fdroid_check.FASTLANE_DIR / "images" / "icon.png").unlink()
        self.assertErrorContains("store icon")

    def test_non_square_icon_fails(self) -> None:
        (self.root / fdroid_check.FASTLANE_DIR / "images" / "icon.png").write_bytes(fake_png(512, 256))
        self.assertErrorContains("must be square")

    def test_non_png_icon_fails(self) -> None:
        (self.root / fdroid_check.FASTLANE_DIR / "images" / "icon.png").write_bytes(b"GIF89a" + b"\x00" * 40)
        self.assertErrorContains("is not a PNG")

    def test_small_icon_fails(self) -> None:
        (self.root / fdroid_check.FASTLANE_DIR / "images" / "icon.png").write_bytes(fake_png(32, 32))
        self.assertErrorContains("at least 48px")

    def test_missing_screenshots_is_only_a_warning(self) -> None:
        report = check(self.root)
        self.assertEqual([], report.errors)
        self.assertTrue(any("phoneScreenshots" in w for w in report.warnings))

    # -- recipe / version consistency -------------------------------------
    def test_recipe_version_code_mismatch_fails(self) -> None:
        path = self.root / "fdroid" / f"{APP_ID}.yml"
        path.write_text(RECIPE.replace(f"CurrentVersionCode: {VERSION_CODE}", "CurrentVersionCode: 17"), encoding="utf-8")
        self.assertErrorContains("does not match")

    def test_recipe_newest_build_mismatch_fails(self) -> None:
        path = self.root / "fdroid" / f"{APP_ID}.yml"
        path.write_text(RECIPE.replace(f"versionCode: {VERSION_CODE}", "versionCode: 17"), encoding="utf-8")
        self.assertErrorContains("newest Builds entry")

    def test_missing_recipe_file_fails(self) -> None:
        (self.root / "fdroid" / f"{APP_ID}.yml").unlink()
        self.assertErrorContains("build recipe not found")

    def test_branch_as_build_commit_fails(self) -> None:
        path = self.root / "fdroid" / f"{APP_ID}.yml"
        path.write_text(RECIPE.replace(f"commit: v{VERSION_NAME}", "commit: main"), encoding="utf-8")
        self.assertErrorContains("is a branch")

    def test_missing_gradle_flag_fails(self) -> None:
        path = self.root / "fdroid" / f"{APP_ID}.yml"
        path.write_text(RECIPE.replace("    gradle:\n      - yes\n", ""), encoding="utf-8")
        self.assertErrorContains("missing 'gradle: yes'")

    def test_http_repo_url_fails(self) -> None:
        path = self.root / "fdroid" / f"{APP_ID}.yml"
        path.write_text(
            RECIPE.replace("Repo: https://github.com/example/soundtrail.git", "Repo: http://github.com/example/soundtrail.git"),
            encoding="utf-8",
        )
        self.assertErrorContains("must use https://")

    def test_license_mismatch_fails(self) -> None:
        (self.root / "LICENSE").write_text("Apache License\nVersion 2.0\n", encoding="utf-8")
        self.assertErrorContains("does not match the LICENSE file")

    def test_missing_license_file_fails(self) -> None:
        (self.root / "LICENSE").unlink()
        self.assertErrorContains("LICENSE is missing")

    # -- build configuration ----------------------------------------------
    def test_release_signing_config_fails(self) -> None:
        path = self.root / "app" / "build.gradle.kts"
        path.write_text(
            GRADLE.replace(
                "        release {\n            isMinifyEnabled = false\n        }",
                "        release {\n            signingConfig = signingConfigs.getByName(\"release\")\n        }",
            ),
            encoding="utf-8",
        )
        self.assertErrorContains("release build type sets a signingConfig")

    def test_low_target_sdk_warns(self) -> None:
        path = self.root / "app" / "build.gradle.kts"
        path.write_text(GRADLE.replace("targetSdk = 35", "targetSdk = 31"), encoding="utf-8")
        report = check(self.root)
        self.assertEqual([], report.errors)
        self.assertTrue(any("below 34" in w for w in report.warnings))

    def test_gradlew_not_executable_fails(self) -> None:
        (self.root / "gradlew").chmod(0o644)
        self.assertErrorContains("not executable")

    def test_missing_wrapper_jar_fails(self) -> None:
        (self.root / "gradle" / "wrapper" / "gradle-wrapper.jar").unlink()
        self.assertErrorContains("gradle-wrapper.jar is missing")

    def test_wrapper_jar_as_lfs_pointer_fails(self) -> None:
        (self.root / "gradle" / "wrapper" / "gradle-wrapper.jar").write_text(
            "version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 1\n", encoding="utf-8"
        )
        self.assertErrorContains("Git LFS pointer")

    def test_git_lfs_attributes_fail(self) -> None:
        (self.root / ".gitattributes").write_text("*.png filter=lfs diff=lfs merge=lfs -text\n", encoding="utf-8")
        self.assertErrorContains("uses Git LFS")

    # -- non-free code -----------------------------------------------------
    def test_play_services_dependency_fails(self) -> None:
        path = self.root / "app" / "build.gradle.kts"
        path.write_text(
            GRADLE.replace(
                'implementation("androidx.core:core-ktx:1.15.0")',
                'implementation("androidx.core:core-ktx:1.15.0")\n'
                '    implementation("com.google.android.gms:play-services-auth:21.2.0")',
            ),
            encoding="utf-8",
        )
        self.assertErrorContains("Google Play services")

    def test_firebase_dependency_fails(self) -> None:
        path = self.root / "app" / "build.gradle.kts"
        path.write_text(
            GRADLE.replace(
                'implementation("androidx.core:core-ktx:1.15.0")',
                'implementation("com.google.firebase:firebase-analytics:22.0.0")',
            ),
            encoding="utf-8",
        )
        self.assertErrorContains("Firebase")

    def test_gms_import_in_kotlin_fails(self) -> None:
        source = self.root / "app" / "src" / "main" / "SignIn.kt"
        source.write_text(
            "import com.google.android.gms.auth.api.signin.GoogleSignIn\n", encoding="utf-8"
        )
        self.assertErrorContains("Google Play services")

    def test_gms_reference_in_a_comment_does_not_fail(self) -> None:
        source = self.root / "app" / "src" / "main" / "Notes.kt"
        source.write_text(
            "// The com.google.android.gms sign-in was removed for F-Droid.\n", encoding="utf-8"
        )
        self.assertEqual([], check(self.root).errors)

    def test_android_auto_meta_data_key_does_not_fail(self) -> None:
        report = check(self.root)
        self.assertEqual([], report.errors)
        self.assertTrue(any("car.application" in n for n in report.notes))

    def test_gms_permission_in_manifest_fails(self) -> None:
        path = self.root / "app" / "src" / "main" / "AndroidManifest.xml"
        path.write_text(
            MANIFEST.replace(
                '<uses-permission android:name="android.permission.INTERNET" />',
                '<uses-permission android:name="android.permission.INTERNET" />\n'
                '    <uses-permission android:name="com.google.android.gms.permission.ACTIVITY_RECOGNITION" />',
            ),
            encoding="utf-8",
        )
        self.assertErrorContains("Google Play services")

    def test_google_services_json_fails(self) -> None:
        (self.root / "app" / "google-services.json").write_text("{}\n", encoding="utf-8")
        self.assertErrorContains("google-services.json")

    def test_committed_jar_fails(self) -> None:
        libs = self.root / "app" / "libs"
        libs.mkdir(exist_ok=True)
        (libs / "vendor.jar").write_bytes(b"PK\x03\x04")
        self.assertErrorContains("prebuilt Java/Android library")

    def test_committed_native_library_fails(self) -> None:
        jni = self.root / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
        jni.mkdir(parents=True, exist_ok=True)
        (jni / "libnative.so").write_bytes(b"\x7fELF")
        self.assertErrorContains("prebuilt shared library")

    def test_wrapper_jar_is_allowed(self) -> None:
        report = check(self.root)
        self.assertEqual([], report.errors)
        self.assertTrue(any("Gradle wrapper" in n for n in report.notes))

    def test_unknown_maven_repository_fails(self) -> None:
        path = self.root / "settings.gradle.kts"
        path.write_text(
            "dependencyResolutionManagement {\n"
            "    repositories {\n"
            '        maven { url = uri("https://maven.example.com/private") }\n'
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        self.assertErrorContains("unknown maven repo")

    def test_maven_central_repository_is_allowed(self) -> None:
        path = self.root / "settings.gradle.kts"
        path.write_text(
            'maven { url = uri("https://repo1.maven.org/maven2") }\n', encoding="utf-8"
        )
        self.assertEqual([], check(self.root).errors)

    def test_query_all_packages_fails(self) -> None:
        path = self.root / "app" / "src" / "main" / "AndroidManifest.xml"
        path.write_text(
            MANIFEST.replace(
                '<uses-permission android:name="android.permission.INTERNET" />',
                '<uses-permission android:name="android.permission.INTERNET" />\n'
                '    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />',
            ),
            encoding="utf-8",
        )
        self.assertErrorContains("QUERY_ALL_PACKAGES")


class ChangelogWritingTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = make_repo(Path(self._tmp.name))
        self.addCleanup(self._tmp.cleanup)
        self.path = self.root / fdroid_check.FASTLANE_DIR / "changelogs" / f"{VERSION_CODE}.txt"

    def test_writes_changelog_when_missing(self) -> None:
        self.path.unlink()
        report = fdroid_check.Report()
        self.assertTrue(fdroid_check.write_missing_changelog(self.root, VERSION_CODE, "Fixed the map.", report))
        self.assertEqual("Fixed the map.\n", self.path.read_text(encoding="utf-8"))
        self.assertEqual([], check(self.root).errors)

    def test_leaves_an_existing_changelog_alone(self) -> None:
        report = fdroid_check.Report()
        self.assertFalse(fdroid_check.write_missing_changelog(self.root, VERSION_CODE, "Overwrite me.", report))
        self.assertEqual("What changed.\n", self.path.read_text(encoding="utf-8"))

    def test_generated_changelog_stays_within_the_limit(self) -> None:
        self.path.unlink()
        report = fdroid_check.Report()
        long_text = "word " * 400
        fdroid_check.write_missing_changelog(self.root, VERSION_CODE, long_text, report)
        content = self.path.read_text(encoding="utf-8")
        self.assertLessEqual(len(content), 500)
        self.assertEqual([], check(self.root).errors)

    def test_falls_back_to_generic_text(self) -> None:
        self.path.unlink()
        fdroid_check.write_missing_changelog(self.root, VERSION_CODE, "   ", fdroid_check.Report())
        self.assertEqual("Bug fixes and improvements.\n", self.path.read_text(encoding="utf-8"))

    def test_main_writes_the_changelog_and_rechecks(self) -> None:
        self.path.unlink()
        self.assertEqual(
            0,
            fdroid_check.main(
                [
                    "--root",
                    str(self.root),
                    "--quiet",
                    "--write-missing-changelog",
                    "--text",
                    "Released from CI.",
                ]
            ),
        )
        self.assertEqual("Released from CI.\n", self.path.read_text(encoding="utf-8"))


class HelperTest(unittest.TestCase):
    def test_truncate_words_keeps_whole_words(self) -> None:
        self.assertEqual("one two…", fdroid_check.truncate_words("one two three four", 10))

    def test_truncate_words_leaves_short_text_alone(self) -> None:
        self.assertEqual("short", fdroid_check.truncate_words("short", 10))

    def test_png_size_rejects_other_formats(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as handle:
            handle.write(b"not a png at all, just text")
            name = handle.name
        self.addCleanup(os.unlink, name)
        self.assertIsNone(fdroid_check.png_size(Path(name)))

    def test_relative_falls_back_to_absolute_paths(self) -> None:
        self.assertEqual("a/b.txt", fdroid_check.relative(Path("/tmp/x/a/b.txt"), Path("/tmp/x")))
        self.assertEqual("/elsewhere/b.txt", fdroid_check.relative(Path("/elsewhere/b.txt"), Path("/tmp/x")))


@unittest.skipUnless((REPO_ROOT / "app" / "build.gradle.kts").is_file(), "not run from the repository")
class RealRepositoryTest(unittest.TestCase):
    """The repository itself has to pass; this is the check that CI actually runs."""

    def test_repository_passes(self) -> None:
        report = check(REPO_ROOT)
        self.assertEqual([], report.errors, "\n".join(report.errors))

    def test_main_exits_zero(self) -> None:
        self.assertEqual(0, fdroid_check.main(["--root", str(REPO_ROOT), "--quiet"]))


if __name__ == "__main__":
    unittest.main()
