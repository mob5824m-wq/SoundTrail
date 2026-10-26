#!/usr/bin/env python3
"""Tests for tools/fdroid_recipe_sync.py.

This script runs in the release workflow between the version bump and the F-Droid gate,
so its output has to be right the first time: it edits the file that gets submitted to
fdroiddata, and it has to keep that file in the byte-exact shape `fdroid rewritemeta`
writes (which `fdroid lint -f` checks in CI, and which these tests check by string).

Run with:  python3 -m unittest discover -s tools -p 'test_*.py' -v
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fdroid_recipe_sync  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[1]

RECIPE = """AntiFeatures:
  NonFreeNet:
    en-US: Optional third-party services.
Categories:
  - Multimedia
License: MIT

RepoType: git
Repo: https://github.com/example/soundtrail.git

Builds:
  - versionName: 1.0.17
    versionCode: 18
    commit: v1.0.17
    subdir: app
    gradle:
      - yes

MaintainerNotes: |-
  The release APK is unsigned so F-Droid signs it.

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.0.17
CurrentVersionCode: 18
"""

NEW_ENTRY = """  - versionName: 1.0.18
    versionCode: 19
    commit: v1.0.18
    subdir: app
    gradle:
      - yes
"""


class SyncRecipeTest(unittest.TestCase):
    def test_appends_the_build_entry_in_canonical_shape(self) -> None:
        text, changes = fdroid_recipe_sync.sync_recipe(RECIPE, "1.0.18", "19")
        self.assertIn(NEW_ENTRY, text)
        self.assertTrue(any("added the build entry for 1.0.18" in c for c in changes))

    def test_entry_lands_after_the_previous_one_and_before_the_next_key(self) -> None:
        text, _ = fdroid_recipe_sync.sync_recipe(RECIPE, "1.0.18", "19")
        self.assertLess(text.index("commit: v1.0.17"), text.index("commit: v1.0.18"))
        self.assertLess(text.index("commit: v1.0.18"), text.index("MaintainerNotes:"))

    def test_entries_stay_separated_by_a_blank_line(self) -> None:
        text, _ = fdroid_recipe_sync.sync_recipe(RECIPE, "1.0.18", "19")
        self.assertIn("      - yes\n\n" + NEW_ENTRY, text)

    def test_updates_current_version_and_code(self) -> None:
        text, changes = fdroid_recipe_sync.sync_recipe(RECIPE, "1.0.18", "19")
        self.assertIn("CurrentVersion: 1.0.18\n", text)
        self.assertIn("CurrentVersionCode: 19\n", text)
        self.assertNotIn("CurrentVersionCode: 18", text)
        self.assertEqual(2, len([c for c in changes if c.startswith("CurrentVersion")]))

    def test_is_idempotent(self) -> None:
        once, _ = fdroid_recipe_sync.sync_recipe(RECIPE, "1.0.18", "19")
        twice, changes = fdroid_recipe_sync.sync_recipe(once, "1.0.18", "19")
        self.assertEqual(once, twice)
        self.assertEqual([], changes)

    def test_does_not_duplicate_an_existing_entry(self) -> None:
        text, changes = fdroid_recipe_sync.sync_recipe(RECIPE, "1.0.17", "18")
        self.assertEqual(1, text.count("commit: v1.0.17"))
        self.assertEqual([], changes)

    def test_several_releases_in_a_row_accumulate(self) -> None:
        text = RECIPE
        for name, code in (("1.0.18", "19"), ("1.0.19", "20"), ("1.1.0", "21")):
            text, _ = fdroid_recipe_sync.sync_recipe(text, name, code)
        for commit in ("v1.0.17", "v1.0.18", "v1.0.19", "v1.1.0"):
            self.assertEqual(1, text.count(f"commit: {commit}"), commit)
        self.assertIn("CurrentVersion: 1.1.0\n", text)
        self.assertIn("CurrentVersionCode: 21\n", text)

    def test_refuses_to_move_backwards(self) -> None:
        with self.assertRaises(SystemExit) as raised:
            fdroid_recipe_sync.sync_recipe(RECIPE, "1.0.16", "17")
        self.assertIn("Not rewriting it backwards", str(raised.exception))

    def test_missing_builds_block_is_an_error(self) -> None:
        with self.assertRaises(SystemExit):
            fdroid_recipe_sync.sync_recipe(RECIPE.replace("Builds:\n", ""), "1.0.18", "19")

    def test_missing_current_version_line_is_an_error(self) -> None:
        with self.assertRaises(SystemExit):
            fdroid_recipe_sync.sync_recipe(
                RECIPE.replace("CurrentVersion: 1.0.17\n", ""), "1.0.18", "19"
            )


class CommandLineTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        (self.root / "app").mkdir()
        (self.root / "fdroid").mkdir()
        (self.root / "app" / "build.gradle.kts").write_text(
            'android {\n    defaultConfig {\n        applicationId = "tech.egrie.soundtrail"\n'
            '        versionCode = 19\n        versionName = "1.0.18"\n    }\n}\n',
            encoding="utf-8",
        )
        self.recipe = self.root / "fdroid" / "tech.egrie.soundtrail.yml"
        self.recipe.write_text(RECIPE, encoding="utf-8")

    def test_check_fails_when_the_recipe_lags(self) -> None:
        self.assertEqual(1, fdroid_recipe_sync.main(["--root", str(self.root), "--check"]))

    def test_writes_the_recipe_then_passes_its_own_check(self) -> None:
        self.assertEqual(0, fdroid_recipe_sync.main(["--root", str(self.root)]))
        self.assertIn("commit: v1.0.18", self.recipe.read_text(encoding="utf-8"))
        self.assertEqual(0, fdroid_recipe_sync.main(["--root", str(self.root), "--check"]))

    def test_missing_recipe_is_an_error(self) -> None:
        self.recipe.unlink()
        with self.assertRaises(SystemExit):
            fdroid_recipe_sync.main(["--root", str(self.root)])

    def test_missing_gradle_version_is_an_error(self) -> None:
        (self.root / "app" / "build.gradle.kts").write_text("android {}\n", encoding="utf-8")
        with self.assertRaises(SystemExit):
            fdroid_recipe_sync.main(["--root", str(self.root)])


@unittest.skipUnless((REPO_ROOT / "app" / "build.gradle.kts").is_file(), "not run from the repository")
class RealRepositoryTest(unittest.TestCase):
    """The committed recipe has to be in step; this is what CI asserts."""

    def test_repository_recipe_is_in_step(self) -> None:
        self.assertEqual(0, fdroid_recipe_sync.main(["--root", str(REPO_ROOT), "--check"]))


if __name__ == "__main__":
    unittest.main()
