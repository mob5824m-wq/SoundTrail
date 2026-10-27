# F-Droid

Soundtrail is ready to submit to F-Droid. This folder holds the build recipe F-Droid
needs, and the repository holds two checks that keep it that way. The metadata F-Droid
shows on f-droid.org lives in `fastlane/metadata/android/en-US/`.

## What F-Droid requires, and where this repository answers it

F-Droid builds an app from a Git tag with its own toolchain, publishes it under its own
signing key, and refuses anything that is not free software. Concretely:

| Requirement | Where it is satisfied |
| --- | --- |
| No non-free library, tracker or prebuilt binary | Nothing in `app/build.gradle.kts` but AndroidX, Compose, Kotlin and `androidx.media`. No `google-services.json`, no `.jar`/`.aar`/`.so` in the tree. Verified by `fdroidserver`'s scanner (see below). |
| Free-software license | `LICENSE` is MIT, and the recipe's `License: MIT` is an FSF/OSI approved SPDX tag. |
| Store metadata in the repository | `fastlane/metadata/android/en-US/`: title, short and full description, a 512×512 icon, and a changelog per versionCode. |
| Metadata inside fdroidserver's limits | name 50, summary 80, description 4000, whatsNew 500 characters. Checked by `tools/fdroid_check.py`, because fdroidserver truncates over-length text silently instead of failing. |
| A build recipe fdroiddata can take as-is | `tech.egrie.soundtrail.yml` in this folder, byte-for-byte what `fdroid rewritemeta` writes. |
| An unsigned release APK | The release build type sets no `signingConfig`, so `assembleRelease` produces `app-release-unsigned.apk` for F-Droid to sign. |
| A build that works from a tag | Gradle wrapper committed, dependencies only from `google()` and `mavenCentral()`, `subdir: app` with the wrapper and settings at the root. |
| Anti-features declared, not hidden | `NonFreeNet`, with an explanation, in the recipe. `NonFreeAdd` was dropped when the proprietary-player hand-offs were removed: nothing is handed to another app's player any more. |
| Reviewer questions answered up front | `MaintainerNotes` in the recipe: the debug keystore, the Android Auto meta-data key, the one `<queries>` intent, and why cleartext HTTP is permitted for exactly one thing — the listener's own Jellyfin server — while the code requires HTTPS for every other address. |

Google Play services is not a dependency, in the code, or in the manifest, and no
third-party OAuth flow is left either: the Spotify, SoundCloud and YouTube integrations
were removed, so music comes from the listener's own Jellyfin server or from audio files
they attach, and motion for automations comes from GPS speed. The one Google string left
in the manifest is the `com.google.android.gms.car.application` meta-data key, which
Android Auto reads to find a media app; it pulls in no library and F-Droid's scanner does
not flag it. The `<queries>` element declares one `android.speech.RecognitionService`
intent so the assistant can list the speech engines that are installed; it names no
package.

## Verified, not assumed

Both of F-Droid's own tools were run against this tree with `fdroidserver` 2.4.5:

```
$ .venv-fdroid/bin/python tools/fdroid_lint.py
== fdroid lint -f tech.egrie.soundtrail ==
clean: no lint findings, and the recipe is in `fdroid rewritemeta` format

== fdroidserver.scanner.scan_source ==
clean: no non-free libraries, trackers, unknown Maven repos or prebuilt binaries
```

`fdroid lint` runs inside a throwaway workspace that looks like a checkout of fdroiddata,
using `lint-config/` as a stand-in for its `config.yml`, `config/categories.yml` and
`config/antiFeatures.yml`. Without those identifier lists lint calls every category and
anti-feature invalid, which says nothing about the recipe. `lint-config/` was written for
this repository rather than copied: fdroiddata is AGPL-3.0 and this project is MIT.

The scanner result is the same check `fdroid build` runs before compiling. Its only
actions on this tree are the standard deletion of `gradlew`, `gradlew.bat` and
`gradle/wrapper/gradle-wrapper.jar`, which F-Droid replaces with its own.

Neither of those proves the app *builds*, and that is the part F-Droid cannot forgive: its
buildserver runs `gradlew assembleRelease` on the tag. Building that variant in CI found a
blocker that nothing else in this repository had ever exercised, because CI had only ever
built debug. `assembleRelease` depends on `:app:lintVitalRelease`, and that task failed with
33 fatal `FullBackupContent` errors — `backup_rules.xml` and `data_extraction_rules.xml`
opened with `<include>`, which makes them allowlists, so every `<exclude>` for a path
outside the allowlist was redundant and lint treats a redundant exclude as fatal. The
release variant did not build at all. Those excludes are removed now (behaviour is
unchanged: an allowlist already leaves everything unlisted out, and each exclusion is
recorded as a comment), and CI builds the release variant and asserts the resulting
`app-release-unsigned.apk` carries no signature.

Reproduce both locally:

```sh
python3 -m venv .venv-fdroid
.venv-fdroid/bin/pip install fdroidserver
.venv-fdroid/bin/python tools/fdroid_lint.py
python3 tools/fdroid_check.py          # the same requirements, standard library only
python3 -m unittest discover -s tools  # tests for that checker
```

## Keeping it qualified

CI re-checks all of this on every push and pull request, and again before a tag is
created, so a re-added `play-services` dependency or a description that grew past its
limit cannot land unnoticed:

- `tools/fdroid_check.py` - the requirements above, in this repository's own code, with no
  dependencies beyond the standard library. It cross-checks the recipe against
  `app/build.gradle.kts`, so version, versionCode and the newest build entry cannot drift.
- `tools/fdroid_lint.py` - F-Droid's own `fdroid lint -f` and source scanner. The scan
  copies only what git lists, because that is what F-Droid clones; scanning the working
  directory instead reports fdroidserver's own dependencies as prebuilt binaries.
- `tools/fdroid_recipe_sync.py` - repoints the recipe at the version being released. No
  pull request can carry the recipe for a version the release workflow has not chosen yet,
  so the workflow runs this between the version bump and the gate; without it every release
  after the first would fail its own consistency check.
- The `fdroid` job in `.github/workflows/android.yml` runs both, plus the checker's tests.
- The `release` job in `.github/workflows/release.yml` repoints the recipe at the version
  it selected, then runs both gates before anything is tagged, builds
  `:app:assembleRelease`, and asserts the release APK is unsigned. If the release has no
  changelog for its versionCode, it writes one from the merged pull request title; a
  changelog committed by hand always wins. The bump, the changelog and the recipe are
  staged together, so the tag F-Droid builds from always carries all three.

## The version F-Droid builds

The recipe currently names:

- `v1.0.16` (versionCode 17) - the first release with no proprietary Google libraries. It
  builds, and it is already tagged.
- `v1.0.17` (versionCode 18) - `CurrentVersion`, the first release whose store metadata
  satisfies every limit. **The release workflow creates this tag when the branch carrying
  this change merges**, because that is when 1.0.17 is published.

Tags before `v1.0.16` still depend on proprietary Google libraries and must not be built.

## Steps to submit

1. Merge to `main`. The release workflow publishes `v1.0.17` and tags it.
2. Check the tag exists and matches the recipe: `python3 tools/fdroid_check.py` reports the
   tag as a note when it does, and as an error when the recipe names something else.
3. Fork <https://gitlab.com/fdroid/fdroiddata>, copy `tech.egrie.soundtrail.yml` into its
   `metadata/` folder unchanged, and open a merge request. F-Droid's reviewers and CI build
   the app from the tag there.
4. Answer reviewer questions from the recipe's `MaintainerNotes`; the open items below are
   the ones that need a human decision.

The recipe is in `fdroid rewritemeta` format on purpose: fdroiddata's tooling rewrites
metadata files into that exact shape, so a submitted recipe that already matches it
produces no diff for a reviewer to undo.

## Open items

- **Screenshots.** None are committed, so the listing is text-only. They are optional for
  inclusion. Real captures from a device go in
  `fastlane/metadata/android/en-US/images/phoneScreenshots/`, named anything, as PNG or
  JPEG with a side between 320 and 3840 pixels. Fabricated or mock-up images are worse
  than none: reviewers reject store listings that do not show the app.
- **Canonical source URL.** The recipe points at `github.com/mob5824m-wq/SoundTrail`, which
  is this repository's remote, while `LICENSE` names `egrie-tech` and the release notes
  mention an earlier repository at `egrie-tech/Geotagged-Music-App`. Confirm which one
  F-Droid should publish before submitting; `tools/fdroid_check.py` warns if `Repo` and the
  git remote disagree.
- **Reproducible builds.** Not attempted. AGP and the Compose compiler embed paths and
  timestamps, so F-Droid will build and publish the app but flag it as not reproducible.
  Fixing that means pinning the build environment and comparing against F-Droid's own
  output, which is a separate project.
- **Categories.** `Multimedia` and `Navigation` are valid identifiers and pass lint. Check
  them against `config/categories.yml` in fdroiddata at submission time anyway: F-Droid has
  been expanding the list, and a reviewer may prefer different ones.
- **The debug keystore.** `keystore/soundtrail-debug.p12` holds the well-known public
  Android debug credentials and is committed on purpose, so sideloaded debug builds update
  each other instead of failing with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. F-Droid never
  uses it: it builds the release variant and signs with its own key. `tools/fdroid_check.py`
  reports it as a warning, with that reasoning, and the recipe explains it in
  `MaintainerNotes`.
- **Anti-features.** Only `NonFreeNet` remains, covering the three optional services that
  are not free software and cannot be self-hosted: map tiles, TheAudioDB song lookup, and
  whichever AI provider the listener points the mood engine at. Each is off until an
  address or key is entered, and Jellyfin, a local Ollama server and the sync server are
  all free software the listener hosts. `NonFreeAdd` was removed with the proprietary
  player hand-offs it described - there is no patched-player package name anywhere in the
  tree now, and the `<queries>` element declares an intent rather than a package, which is
  what `MaintainerNotes` explains.
