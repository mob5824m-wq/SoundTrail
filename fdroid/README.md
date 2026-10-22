# F-Droid

Soundtrail is prepared for F-Droid. This folder holds the draft build recipe. The metadata
F-Droid shows on its site lives in `fastlane/metadata/android/en-US/` in this repository.

## What is already done

- **No proprietary libraries.** Google Play services (Sign-In and Location/Activity Recognition)
  is removed from the Gradle build, the code, and the manifest. The YouTube connection uses the
  browser OAuth flow with the listener's own Desktop client ID. Motion for automations comes from
  GPS speed. `fdroidserver`'s scanner reports no problems on this tree. It reports four
  `play-services` problems on the previous release, v1.0.15, which confirms the check works.
- **Free-software build.** Every dependency is an open-source AndroidX, Compose, or Kotlin
  library, so the app builds from source with no closed binary inputs. The release APK is
  unsigned, so F-Droid signs it with its own key.
- **Metadata in the repo.** `fastlane/metadata/android/en-US/` contains the title, short and full
  descriptions, a 512×512 icon, and the changelog for versionCode 17.
- **Draft recipe.** `tech.egrie.soundtrail.yml` passes `fdroid lint`, using a stub of the
  fdroiddata config (see the open items below).

## Steps to submit

1. Merge this change to `main`. The release workflow then bumps the version to 1.0.16
   (versionCode 17) and tags `v1.0.16`. F-Droid builds from that tag.
2. Fork <https://gitlab.com/fdroid/fdroiddata>, copy `tech.egrie.soundtrail.yml` into its
   `metadata/` folder, and open a merge request. F-Droid's reviewers and CI check the build there.
3. Answer any reviewer questions on the merge request. The open items below are the likely ones.

## Open items to confirm before submitting

- **Categories.** F-Droid has been expanding its category list. Check `Multimedia` and
  `Navigation` against `config/categories.yml` in fdroiddata.
- **Author and source URL.** The recipe names `egrie-tech` as author and points at
  `github.com/mob5824m-wq/SoundTrail`. The license header says `egrie-tech`, and the release
  notes mention an earlier repository, so confirm the canonical source URL.
- **Anti-features.** The recipe declares `NonFreeNet` (optional Spotify, SoundCloud, YouTube, and
  AI-provider connections) and `NonFreeAdd` (links that open Spotify, SoundCloud, and YouTube Music
  apps). Both are informational, but reviewers may ask for more.
- **Optional YouTube audio extraction.** The README describes an optional audio-only extraction
  action that uses the configured sync server and requires the listener to confirm rights to the
  content. F-Droid's inclusion policy is strict about tools that enable copyright infringement.
  Expect questions about it. If it is a problem, the feature needs to go before submission.
- **Patched YouTube Music apps.** The app lists Morphe and ReVanced package names so it can hand
  links to them. Reviewers may ask about this.
- **Committed debug keystore.** `keystore/soundtrail-debug.p12` uses the public Android debug
  credentials and is committed on purpose, so sideloaded debug builds update each other. F-Droid
  signs its own builds, so this key is not used for F-Droid releases.
- **Screenshots.** The metadata has no phone screenshots yet. They are optional, but they help
  listings. Add them under `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
- **Android Auto metadata.** The manifest keeps the `com.google.android.gms.car.application`
  meta-data key. Android Auto reads it. It is a string key, not a library, and the scanner does not
  flag it. Reviewers may still ask.
