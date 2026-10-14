# Soundtrail — music for the places you love

An Android app that saves **Spotify**, **YouTube Music**, **SoundCloud**, and **your own Jellyfin server's** songs as private, location-based pins, with an **in-app map**, a **mood engine that turns how you feel into a mix from your own library**, offline-audio management, local playlists, opt-in place/motion/time automations, **JSON backup/export**, optional **end-to-end encrypted cloud sync (public server by default, self-hostable)**, and background Jellyfin playback with system media controls.

## What connects, and how

| Service | Integration |
| --- | --- |
| Spotify | Authorization Code **with PKCE** (no client secret). Search for tracks with the official Web API; tap a pin to open playback in Spotify or a browser. Tokens are encrypted with Android Keystore. |
| SoundCloud | Authorization Code **with PKCE** (OAuth 2.1) against `secure.soundcloud.com`. Search for tracks with the official API; tap a pin to open playback in SoundCloud or a browser. Tokens **and the Client Secret** are encrypted with Android Keystore. Requires an API app on SoundCloud's side — see below. |
| Jellyfin (your own server) | Username + password sign-in against **your** server, read-only music-library search, in-app/background playback, and offline download through Jellyfin's authenticated `/Items/{id}/Download` route. The server enforces the signed-in account's access; Soundtrail downloads only items that account is allowed to retrieve, and caps each transfer at 250 MiB. The password is never stored; the access token is encrypted with Android Keystore. Works over HTTPS or plain HTTP on a trusted home network. |
| Song lookup (TheAudioDB) | **Free, no account.** Enter artist + title to get exact names, album, genre/mood, and — when TheAudioDB knows one — a Spotify or YouTube link that passes the same allowlist. Metadata only; nothing is stored. |
| YouTube Music app | Search and Library links are handed to the installed YouTube Music app, including Morphe's patched package, or to music.youtube.com. Share a song link back to Soundtrail to pin it. Soundtrail never reads the music app's private storage or session. **Opening YouTube Music requires no Google sign-in and no connection at all** — it is a link handed over exactly as a browser would — so this keeps working across app updates, reinstalls, and revoked API grants. |
| Mood mixes | **Free, on-device, no account.** Describe how you feel (or tap a quick pick); a small, explainable engine on the phone reads it into a sound profile and scores the songs in your own pins and playlists against it. Optional extras, both off by default: TheAudioDB genre/mood lookups and a bring-your-own-key AI reading. Your songs, playlists, pins, and places never leave the device. |
| YouTube playlists & search (optional) | Two explicit sign-in options request only the `youtube.readonly` scope and call the official YouTube Data API v3. **Google account** uses Google Sign-In via Play services or standard microG (Android OAuth client; the access token is requested on demand, held in memory, and never persisted). **Client ID** opens Google in your browser with your own Desktop OAuth client and PKCE (no client secret); access and refresh tokens are encrypted with Android Keystore. The Client ID path needs no Google services and is the one that works alongside MicroG RE. Once connected, Soundtrail can both search videos in your playlists AND import those playlists (including Liked videos and Uploads) into your local library. This does **not** expose YouTube Music-only collections (saved songs/albums/mixes); see setup below. |
| YouTube links | The official YouTube Data API integration is read-only metadata access. A separate, optional audio-only extraction action uses your configured sync server (the public default or a self-hosted one), has no YouTube login/cookies, and must only be used for content you own or have permission to download. |
| Your audio files | Download eligible Jellyfin tracks or permitted public YouTube audio for offline playback, or attach audio you already own or are authorized to use. Files are copied into private app storage; audio is not cloud-synced. |
| Map tiles | The Pins tab's **Map** view draws an OpenStreetMap-based map on-device: raster tiles are fetched from CARTO's public tile server (`basemaps.cartocdn.com`) for the visible area only. Tile requests reveal the visible map area; pin coordinates are rendered locally and are not included in tile requests. Attribution stays on screen. |
| Backup files | **Export** writes pins, playlists, and automations to a JSON file you choose; **Import** reads one back additively. The backup file goes only where you put it. Logins and audio files are excluded. |
| Optional cloud sync | By default connects to the public Soundtrail sync server; you can instead point it at your own self-hosted Soundtrail API over HTTPS. Pins (including coordinates), playlists, song links, and automations sync two-way, including edits and deletions. Events and compacted checkpoints are encrypted on-device with AES-256-GCM. The recovery key is user-held and cannot be reset by changing the account password. |
| Location & motion | `LocationManager` on demand, plus an **opt-in** foreground watch service for automations. Motion (driving/running/walking) is inferred **on-device from GPS speed** — no Play Services, no Activity Recognition API, no advertising ID. |
| Time | The watch checks the current time every minute; automations can fire only inside a time window (wraps midnight). The Lists tab shows the live clock. |

## Playlists & automations

- **Playlists** (Lists tab) are named groups stored locally by default. Optional cloud sync encrypts playlist metadata and song links before upload. A song needs a title; supported provider links are optional — without one, tapping play opens a YouTube Music search in the installed app or browser.
- **Jellyfin songs** are stored as *item references* (`jellyfin:item:<id>`), never as server URLs: addresses and access tokens do not enter pins, playlists, backups, or cloud-sync records. Playback resolves against the current connection; direct play is tried first, with an automatic MP3 transcode fallback. Download eligible audio through Jellyfin's authenticated endpoint; its account permissions remain authoritative. **YouTube audio** can be requested through the self-hosted API for public audio-only streams, with an explicit rights confirmation; that extraction is unofficial and does not use YouTube login cookies. Both kinds of offline audio stay on-device and are not backed up or cloud-synced.
- **Backups:** export pins, playlists, and automations to a JSON file, or import additively. Backups and cloud sync are separate: backups are readable JSON; cloud sync sends only encrypted event envelopes. Account logins and local audio files stay on-device; restore or sync may require reconnecting providers or downloading local audio again.
- **Get a song (external handoff):** the ⬇ action for other providers shares the song link or YouTube Music search URL. YouTube playlist items also offer a separate, permission-confirmed audio-only extraction action; Jellyfin items use their authenticated offline-download action.
- **Import audio:** the ♪ button attaches an audio file from your device to a playlist song. The file is copied into private storage and shows as *On device*; tapping play opens it in your music player, fully offline. Removing the song or playlist deletes the copy.
- **Offline storage management:** Lists shows total local audio storage; **Manage** opens a largest-first file list with per-file sizes and playlist details. Remove individual copies or clear all offline files; playlist entries are kept. Removing a song or playlist still deletes its attached file.
- **Automations** point a playlist at conditions: *near a saved spot (20–2000 m radius)*, *while walking / running / driving*, and/or *between two times of day*. Set any combination; all set conditions must hold at once.
- **The watch**: an opt-in foreground service (persistent notification — required by Android and kept honest by it) checks location, motion, and time. When a rule matches it posts a notification with **Play playlist** and **Open Soundtrail** actions. Nothing plays without a tap, and a rule re-arms after a 15-minute cooldown.
- **Motion inference**: GPS speed bands — under 0.5 m/s stationary, to 2.6 walking, to 7.5 running (covers cycling), above that driving. It is an estimate; tunnels and poor GPS delay or miss triggers.
- **Battery**: continuous GPS watching uses noticeably more battery. The watch runs only while its toggle is on and its notification is visible.
- **Privacy**: by default, pins, coordinates, playlists, automations, and imported audio stay on the device. If you opt in to cloud sync, pins (including coordinates), playlist metadata, song links, and automation rules are encrypted before leaving the phone; the server stores ciphertext and account/session metadata for sync. The separate YouTube extractor receives the requested video ID and temporarily processes its audio. Downloaded audio and provider credentials are never synced. Map tiles reveal only the visible area, not your saved pin coordinates. For YouTube playlist search, Soundtrail fetches metadata and matches your query on-device; it does not send the query to YouTube.

## Mood mixes

The **Mood** tab turns how you feel into music you already own a link to. Type a few words
(*"tired but I want something warm for the drive home"*), or tap one of the quick picks, and tap
**Build my mood mix**. Soundtrail reads the description and ranks every song in your pins and
playlists against the sound it asks for — each row shows why it was picked ("mood fits", "energy
fits", the tags that overlapped, or "no sound tags yet"), and the mix plays through the same
in-app player as everything else, advancing song by song.

- **On-device by default.** The reading is a small, explainable bag-of-words model on the phone: phrases, negation ("not happy"), intensifiers, emoji, plus gentle tilts from the time of day and — only while the automation watch is on — whether you are walking, running, or driving. No key, no account, no network. Nothing about your library is ever sent anywhere for this.
- **Optional AI reading (bring your own key).** Switch it on in the Mood tab and paste an API key from Google AI Studio (stored with Android Keystore, never synced, never backed up, excluded from exports). Only your mood description — plus the energy hint, the hour, and whether you are moving — is sent to the model; your song titles, artists, playlists, pins, and places stay on the device. Every failure (no key, no network, quota, an unreadable reply) falls back to the on-device reading and says so, so the feature never depends on the model being reachable.
- **Optional tag lookups.** Turn on *Look up genre & mood tags* and Soundtrail can ask TheAudioDB's free metadata API about the specific songs nothing is known about (artist + title only, capped per run, and it remembers what it learned). This is what lets a track whose title says nothing — `Track 1` — still be matched by its genre.
- **Save it.** *Save this mix as a playlist* writes a playlist named `Mood · <mood>` and replaces the songs the mix generated on a re-save, so today's mood does not pile up forever. Hand-added songs in that playlist are kept.
- **Bring it to YouTube Music.** *Open this mood in YouTube Music* hands the mood's search phrases to the YouTube Music app (or `music.youtube.com`) with no sign-in, for the music you have not pinned yet.

## Offline audio and source permissions

The optional YouTube offline action is **audio-only** and runs through your self-hosted server; it requires a signed-in Soundtrail sync-server account. The official YouTube Data API remains read-only and does not provide media bytes. The extractor uses no Google OAuth token, YouTube Music session, or browser cookies; it only attempts a publicly accessible audio stream, so private, age-restricted, login-gated, DRM-protected, or otherwise restricted items will fail. You must confirm that you own the content or have explicit permission to download it. This unofficial extractor may stop working when YouTube changes its site. The download UI distinguishes server-side preparation from transfer to the device and shows transfer progress. The server temporarily handles the video ID and audio file; the audio is streamed to the app, saved privately, and not added to cloud sync.

Jellyfin offline downloads remain authenticated against your own server and subject to its account permissions. Both download paths are limited to 250 MiB per track. The **Get** action for other links still hands the link to an app you choose; Soundtrail does not bypass access controls or download content you do not have rights to keep.

## YouTube Music and official API access

Morphe patches a separate YouTube Music app; it is not an SDK or a third-party library API. Soundtrail can detect Morphe's patched package, open searches or the Library inside it, and receive song links shared back from it. **None of that needs the optional Google connection**: handing a search or a song link to the music app works like a browser link, with no sign-in, so opening YouTube Music from Soundtrail keeps working across updates no matter what happens to the API connection. Separately, an optional, explicit Google OAuth connection can use the official YouTube Data API to search videos found in accessible playlists.

**The saved Google connection survives app updates.** Access and refresh tokens are encrypted with Android Keystore and never deleted by a failed refresh, a timeout, or an app update; the app renews the token by itself when it starts, and writes a rotated refresh token to disk *before* using it, so an update (or a crash) can never leave the device holding a token Google will not accept. Only Google itself can end a connection — you revoked it, changed the password, or the Cloud project's OAuth consent screen is still in **Testing**, which makes Google expire grants after 7 days. In that one case the Connect tab says exactly which of those it was, keeps the Client ID and account label, and offers a one-tap **Reconnect Google** — no re-typing, no re-setup.

Standard microG/GmsCore may support the Google-account sign-in flow on compatible setups, but it is not guaranteed and Soundtrail never reads microG or Morphe private storage. MicroG RE (the Morphe/ReVanced GmsCore fork) only authenticates patched apps, so Soundtrail cannot use its accounts; MicroG RE users should use the browser-based Client ID sign-in, which needs no Google services at all. The official API does not reproduce the YouTube Music library: saved songs, albums, artists, mixes, and other Music-only collections may be unavailable. The optional audio extractor is a separate, unofficial server-side component; it uses no Google token or YouTube Music session, supports public audio-only streams, and must be used only with content you are authorized to download. It does not bypass logins, DRM, paywalls, or access restrictions. Spotify and SoundCloud playback also stays in their own apps rather than being streamed by Soundtrail.

## Cloud sync (public server by default, self-host optional)

Out of the box the app points at the official public Soundtrail sync server
(`https://soundtrail.duckdns.org`). Just open **Connect → Cloud sync**, enter an email
and password, tap **Create account and recovery key**, save the recovery key, and syncing
works with no extra setup.

You can instead run your own server (see [`server/`](server/README.md)) and replace the URL
in the **Sync server URL** field on the Connect tab — a **Use public server** button resets
it back. The server manages email/password accounts and sessions; it has no key recovery
service. The app generates a random 256-bit recovery key on account creation. **Save it
somewhere private before relying on sync.** You may reveal it again on an already signed-in
device, but a new device needs the key. A password reset cannot recover encrypted content,
and uninstalling removes the device's saved copy of the key.

Each pin, playlist, playlist song, and automation is encrypted client-side with AES-256-GCM. The server stores opaque ciphertext envelopes and sequence metadata; it does not receive the recovery key or plaintext sync data. Changes flow both ways, including per-item additions, edits, and deletions. Concurrent versions merge deterministically using a last-write-wins clock and device-id tie-breaker. Once history grows, the client uploads an encrypted checkpoint and the server prunes covered event rows. Provider tokens and downloaded audio files are not synced.

The server sees account email, authentication metadata, request timing, event IDs, and ciphertext sizes. If you use YouTube extraction, it also processes the requested video ID and transient audio file. Run it only behind HTTPS, protect and back up its SQLite volume, and keep its host patched. The server includes per-process request rate limits, a per-account encrypted-sync quota, and authenticated account deletion, but no email verification or password-reset service; use reverse-proxy limits as well, especially with multiple instances. See [`server/README.md`](server/README.md).

Automatic sync is **off by default**. Enable **Sync automatically when Soundtrail opens** in Connect to sync at app launch; it requires a signed-in account and this device's saved recovery key and does not run while the app is closed. Connect also shows pending local changes; after a sync failure, **Retry sync** runs the same merge again. In the same section, **Delete account and remote sync data** removes the server account/history only; local pins, playlists, automations, and offline audio remain on the device.

### Deploy your own server (optional)

1. Run the sync server natively (Python 3.10+, no Docker, any OS or CPU) behind a TLS reverse proxy for a domain you control. See [`server/README.md`](server/README.md) and [`server/Caddyfile.example`](server/Caddyfile.example).
2. From the repository, run `cd server && python3 run.py` (`py -3 run.py` on Windows). The service binds to loopback by default for the proxy; do not expose port 8080 directly.
3. In Soundtrail, open **Connect → Cloud sync**, replace the default URL with your public `https://` URL, and create an account. Save the recovery key, then tap **Sync now**. On another device, enter your server URL, sign in to the same account, and enter the saved key.

If you don't want to self-host, leave the server URL set to the default public server and create your account there instead.

Backups remain useful as a separate portable JSON export; they are not encrypted cloud sync.



**SoundCloud access:** SoundCloud treats *every* API client as confidential, so its token exchange requires both a Client ID **and** a Client Secret — unlike Spotify, there is no public-client mode. Registering an API app requires an eligible SoundCloud subscription or an approved API request; check [SoundCloud for Developers](https://developers.soundcloud.com/docs/api/register-app). The Secret is stored only in Android Keystore-encrypted storage on this device.

**TheAudioDB free key:** Soundtrail calls TheAudioDB's v1 API with their documented shared free key (`123`, formerly `2`). The shared key can be rerouted or rate-limited at any time — an inactive route returns HTTP 404 with `{"Message":"Not found"}`. If it stops working, check [TheAudioDB's API page](https://www.theaudiodb.com/free_music_api); premium keys use the same URL format and only require changing `AUDIO_DB_FREE_KEY`.

## YouTube playlist search setup

The optional YouTube connection is independent of signing in to YouTube Music/Morphe. Both sign-in options request only the `https://www.googleapis.com/auth/youtube.readonly` scope for Data API access (no YouTube write scope). Neither inspects app data nor extracts credentials from microG. The two options are mutually exclusive: connecting with one replaces the other.

Common steps for both options:

1. Create or select a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Configure the OAuth consent screen (choose the appropriate audience and add your account as a test user while developing). Google may require verification or test-user access for the YouTube read-only scope, depending on the project and distribution. While the project's publishing status is **Testing**, Google expires the grant after 7 days and you reconnect; publishing the project avoids the expiry (the unverified-app warning remains until Google verifies it).
3. Enable **YouTube Data API v3** in that project.

**Option A — Google account (Play services or standard microG).** Under **APIs & Services → Credentials**, create an **Android OAuth client ID** for package `tech.egrie.soundtrail`. Add the SHA-1 signing-certificate fingerprint for the APK you install. This repository's shared debug key uses `21:D3:90:27:4A:69:5B:3F:C0:C3:84:A1:3E:70:F1:95:3A:A8:32:2F` (verify with `./gradlew :app:signingReport`, the `debug` variant); a build signed with a different certificate needs its own Android OAuth client/fingerprint. Do not create a Web client or put a client secret in the app. Then, on a compatible microG/GmsCore (or Google Play services) setup, add your Google account to microG and make sure the account and OAuth APIs are configured. In Soundtrail's **Connect** tab, choose **Connect Google account**. If exactly one Google-type account is on the device (your microG account), Soundtrail connects it directly with no picker and asks you to approve the read-only permission only if Google requires it. If there are none or several, the Google sign-in screen opens so you can choose or add one. Then choose **YouTube** on the **Search** tab. Soundtrail asks for the **Accounts** permission (`GET_ACCOUNTS`) to see the microG account; deny it and the sign-in screen is used instead.

**Option B — Client ID in the browser (works with MicroG RE, or with no Google services).** MicroG RE only authenticates Morphe/ReVanced patched apps, so Soundtrail cannot sign in through its accounts — and this option does not need to. Under **APIs & Services → Credentials**, create a **Desktop app OAuth client ID** (creation asks for a name only; no redirect URI registration is needed and no secret is used — Desktop clients use PKCE). In Soundtrail's **Connect** tab, enter that Client ID (`123…-abc….apps.googleusercontent.com`) and choose **Connect with Client ID**. Google opens in your browser; approve the read-only permission and you return to the app. No device Google account, Play services, or microG account is required. Then choose **YouTube** on the **Search** tab.

Soundtrail validates the grant with a read-only `playlists.list` request. When **Import automatically when connected** is on (default), Soundtrail pulls the accessible playlists as soon as sign-in completes; tap **Import playlists now** anytime to refresh. Playlists and items use deterministic ids (`yt-pl:<playlistId>` / `yt:<playlistId>:<videoId>`), so re-importing is idempotent — only newly discovered videos are appended and any offline copies or edits you made stay attached. Import is capped at 200 playlists and 2,000 items per list. The YouTube Data API does **not** expose YouTube Music-only collections (saved songs/albums/artists, Supermix, Daylist, My Supermix, "Your likes" mixes, etc.); those stay in the YouTube Music app. Search scans at most the first 50 of your playlists and the first 50 video items in each, then matches the terms locally and returns at most 20 results. This is deliberately bounded; older playlists/items beyond those pages won't be found. It searches playlist videos only, not the complete YouTube Music catalog or all saved Music-library types. If Google sign-in fails on microG, check the installed GmsCore version, account setup, package name/signing fingerprint, and Cloud OAuth/API configuration; Soundtrail does not bypass those checks. If the browser sign-in reports a rejected redirect or Client ID, make sure the ID comes from a **Desktop** client (Android and Web client types do not support this flow).

## Build & install

1. Install Android Studio with JDK 17 and Android SDK Platform 35. Open this directory as a Gradle project.
2. Run the `app` configuration on an Android 8.0+ device/emulator, or run:

   ```bash
   ./gradlew :app:assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### The launcher icon

The icon is generated, not hand-drawn: **`tools/build_icon.py`** holds the geometry (a lime map
pin with a dark note and two sound ripples), writes the adaptive icon, the legacy vector, the
Android 13+ themed silhouette, and every mipmap PNG, and validates each path first. Android reads
vector path data in whole command groups, so a `c` carrying 22 numbers — three and a half
groups — leaves the drawable wrong or undrawable; that is exactly what happened to the note in
the previous icon. After changing the geometry, or anything in the palette it mirrors, run:

```bash
python3 tools/build_icon.py          # rewrites app/src/main/res/{drawable,mipmap-*}
python3 tools/build_icon.py --check  # what CI runs; fails on drift or a malformed path
```

The generator uses [Pillow](https://python-pillow.org/) for the PNGs (`pip install pillow`);
without it, the check still covers the XML drawables and every path. The generated geometry is
kept inside the 66dp adaptive-icon safe circle, and the ripples are checked to stay inside the
pin, so no launcher mask can crop the mark.

   Debug builds are signed with the key committed at `keystore/soundtrail-debug.p12` — the standard public debug credentials, `androiddebugkey` / `android` — rather than the per-machine `~/.android/debug.keystore`. Your build, the CI build, and every published release therefore share one signature, so a newer APK installs straight over an older one as an update. See *Updating an installed build* below for the one-time exception.

3. Optionally install Spotify, YouTube Music, and/or SoundCloud. An installed music app is **not** required for saving a pin; a browser can open supported links. For YouTube Music, configure microG and sign in inside your compatible music app if that is how your device is set up.
4. To use Spotify **search**: create an app at [Spotify for Developers](https://developer.spotify.com/dashboard), register the exact redirect URI below, and enter the app's public **Client ID** on the **Connect** tab. Do **not** enter a client secret.

   ```text
   app.soundtrail://spotify-auth
   ```

   Spotify requires this URI to match your dashboard setting exactly. Developer-mode apps have [access/allowed-user restrictions](https://developer.spotify.com/documentation/web-api/concepts/quota-modes). If the provider rejects a custom scheme for your configuration, use a verified HTTPS Android App Link and update both the manifest and `SPOTIFY_REDIRECT_URI`.

5. To use SoundCloud **search**: register an API app per [SoundCloud's docs](https://developers.soundcloud.com/docs/api/register-app), register the exact redirect URI below, and enter the **Client ID and Client Secret** on the **Connect** tab.

   ```text
   app.soundtrail://soundcloud-auth
   ```

   Soundtrail uses the OAuth 2.1 Authorization Code flow with PKCE (`display=popup` for the mobile screen), exchanges the code at `secure.soundcloud.com/oauth/token`, and requests only `/me` and `/tracks` search. Access tokens expire after about an hour; refresh tokens are single-use and handled automatically.

6. To use **Jellyfin**, install (or point at) your own Jellyfin server, open the **Connect** tab, and enter:

   - the **server address**, for example `http://192.168.1.20:8096` — plain HTTP is fine on your own network, HTTPS works too, and a sub-path behind a reverse proxy (`https://music.example.com/jellyfin`) is accepted;
   - a **username** and **password**. The password is sent once to that server and never stored. `GET /System/Info/Public` is checked first, so a wrong address or an unrelated web server is reported as such instead of looking like a bad password.

   Soundtrail searches that library from the **Search** tab (pick the *Jellyfin* chip), streams what you pin, and can save an eligible track for offline playback using Jellyfin's authenticated download endpoint. Soundtrail never writes to, renames, or deletes items in the server library. Download permission is decided by Jellyfin; only save media you have rights to access and keep.

7. For the optional **YouTube playlist search**, follow [YouTube playlist search setup](#youtube-playlist-search-setup), then connect with your Google account or a Client ID in Soundtrail. To use the separate Morphe-patched YouTube Music app, install/configure Morphe and sign in there (including MicroG RE if that build uses it — the Client ID sign-in is the option that works alongside it). Soundtrail can open the app's search or Library and receive shared song links; it never imports Morphe's session or reads private app data.

### Updating an installed build (signature mismatch)

Android will not install an APK over an existing app when the two were signed with different certificates, and reports:

```text
adb: failed to install soundtrail-X.Y.Z-debug.apk: Failure
[INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package tech.egrie.soundtrail signatures do not match newer version; ignoring!]
```

That means the installed copy carries a different signing key than the APK in your hand. The usual cause is an APK built before the shared debug key (`keystore/soundtrail-debug.p12`) existed: a CI runner is discarded after every job and Android's default debug key is created per machine, so each run of the release workflow signed its APK with a brand-new, randomly generated key. Those keys are gone and cannot be reproduced, so an update over such an install is impossible — uninstall once and install cleanly:

```bash
adb uninstall tech.egrie.soundtrail
adb install soundtrail-X.Y.Z-debug.apk
```

**The uninstall deletes this device's app data.** Local pins, playlists, automations, downloaded/imported audio, sync session, and the device's encrypted copy of the recovery key disappear with the package. Synced ciphertext remains on your self-hosted server, but you must have separately saved the recovery key to decrypt it after reinstalling. Stored provider logins go too; reconnect those accounts afterwards. Before uninstalling, export a portable JSON backup if desired and make sure your cloud recovery key is saved somewhere private.

It is a one-off. Every APK built from this repository — locally or by CI — is signed with `keystore/soundtrail-debug.p12`, so from the next release on, updates install over each other normally. That key is deliberately public (it is the conventional `androiddebugkey`, and anyone can read it in this repository), which is fine for sideloaded debug builds and unusable for a Play release — this repository intentionally configures no release signing.

## Releases

The first release published from this repository is **v1.0.9** (`versionCode` 10). Releases published before the repository move (v1.0.1–v1.0.8) remain at the [Geotagged-Music-App releases page](https://github.com/egrie-tech/Geotagged-Music-App/releases). The `Release on merge` workflow (`.github/workflows/release.yml`) uses the configured version for its first untagged release; after that tag exists, each merged pull request or manual run increments the patch version and `versionCode`:

1. selects the current version for its first release, then adds 1 to `versionCode` and `0.0.1` to `versionName` on later releases (via `.github/scripts/bump_version.py`, which skips patches whose `vX.Y.Z` tags already exist),
2. runs the server API tests and Android unit tests, then builds `:app:assembleDebug` **before** pushing anything, so a failed check leaves `main` untouched,
3. commits a version bump when needed, tags `vX.Y.Z`, and pushes the commit (if any) and tag,
4. creates the GitHub Release `Soundtrail X.Y.Z` with generated notes (the merged pull requests) and the debug APK attached as `soundtrail-X.Y.Z-debug.apk`.

Details worth knowing:

- **It cannot loop.** The bump is pushed with the workflow's own token, and pushes made with `GITHUB_TOKEN` do not start further workflow runs.
- **Closing a pull request without merging does nothing**; the job is skipped unless `pull_request.merged` is true. A pull request merged into a branch other than `main` is ignored too.
- **Concurrent merges are serialized** by a `concurrency` group, so two merges cannot claim the same version number.
- **Branch protection:** releases after v1.0.9 need the workflow to push its version-bump commit to `main`. If a rule requires pull requests for `main`, allow the GitHub Actions bot to bypass it (Settings → Branches → the `main` rule), otherwise the job fails with a message saying exactly that. The workflow requests `permissions: contents: write` for the same reason.
- **Manual runs:** the workflow also has `workflow_dispatch`, so *Actions → Release on merge → Run workflow* publishes the first untagged version or increments and releases the next patch (it always operates on `main`).
- The attached APK is the **debug** build, signed with the shared debug key committed at `keystore/soundtrail-debug.p12`, so each release installs as a normal update over the previous one (the first install over an APK built before that key existed still needs one uninstall — see *Updating an installed build* above). It is **not** a Play-ready build: that key is public and no release signing is configured here.
- The `Android build` workflow checks the APK it built against the committed keystore and fails if the signing certificate does not match, so the "updates install in place" guarantee cannot regress silently.
- The `Server tests` workflow runs the API suite on Python 3.10 and 3.14 for pushes and pull requests; the release workflow also reruns the suite against `main` before changing the version or publishing.
- CI note: because the bump is pushed with `GITHUB_TOKEN`, the separate `Android build` workflow does not re-run for that commit — the release workflow compiles the same code itself.

## Permissions, and why

| Permission | Used for |
| --- | --- |
| Internet | Provider search APIs, TheAudioDB lookups, your own Jellyfin server, optional HTTPS sync server, and map tiles |
| Coarse/Fine location | Pinning, sorting pins, and the opt-in automation watch |
| Foreground service (location type) | The watch's persistent, visible automation service — opt-in only |
| Foreground service (media playback type) | Keeping a Jellyfin stream alive in the background under its playback notification |
| Post notifications | The watch status, automation triggers, and playback controls (Android 13+) |

## Try it

- **Explore:** tap **Drop a pin**. Enter a song title and a supported link. Use **Locate** or enter coordinates manually; name the place and save. On the Pins tab, switch **List ↔ Map**: the map shows every pin in its provider colour — tap one for its card, pinch/double-tap to zoom, drag to pan, and **long-press anywhere to drop a new pin** at that exact spot.
- **Search:** connect Spotify or SoundCloud and switch the provider chip. For optional read-only YouTube playlist search, configure Google Cloud as described above, connect with your Google account or a Client ID on the **Connect** tab, and choose **YouTube**; queries are matched locally against videos in your playlists. This does not cover the full YouTube Music library. The separate **YouTube Music** search field opens results in YouTube Music or Morphe; **Open your YouTube Music library** and share-back are available without OAuth. The **free TheAudioDB lookup** (artist + title, no account) and direct song-link paste are also available.
- **Mood:** open the **Mood** tab, describe how you feel in a few words (or tap a quick pick and an energy level), and tap **Build my mood mix**. Soundtrail reads it on the device and ranks the songs in your pins and playlists against the mood; each row explains itself, and the mix plays in order. Save it as a playlist, or hand the mood to YouTube Music — the latter needs no sign-in. The Explore tab's *What's your mood?* card and the same YouTube Music button are one tap in.
- **Share:** in Spotify, YouTube Music, or SoundCloud, share a **song** link to Soundtrail to open a prefilled pin editor.
- **Jellyfin:** connect your server on the Connect tab, pick the *Jellyfin* chip on Search, and add its songs to a pin or playlist. Stream in-app or in the background with notification/lockscreen controls. In a playlist, tap the download icon to save an eligible track privately for offline playback; Jellyfin decides whether your account may download it.
- **Backup and sync:** export/import a portable JSON backup, or use end-to-end encrypted cloud sync from **Connect → Cloud sync** — either the public default server or one you deploy yourself. Save the recovery key before using another device.
- **Lists:** create a playlist and add songs. Jellyfin items can be downloaded offline with the download action; use **♪** to attach an audio file you already own. Other **⬇ Get** actions only hand a link to another app. Turn on the watch and create an automation — for example, *"Morning commute" → playlist "Drive" · while driving · 07:00–09:00* — then tap the notification when it fires.
- Supported links: `open.spotify.com/track/<id>`, `spotify:track:<id>`, `music.youtube.com/watch?v=<id>`, `youtube.com/watch?v=<id>`, `youtu.be/<id>`, `soundcloud.com/<artist>/<track>` (including `www.`/`m.` hosts and `/s-<token>` secret trails), and `on.soundcloud.com/<token>` or `soundcloud.app.goo.gl/<token>` share links. Tracking parameters are removed. Playlists, artist profiles, site pages, and unknown domains are rejected.

## Privacy and limitations

- By default, pins, playlists, and automations (including coordinates and song links) are saved only in private app storage. Optional cloud sync sends their client-encrypted event envelopes to the self-hosted server you choose; for sync it sees account/session metadata and ciphertext sizes, but not item contents or the recovery key. If you invoke YouTube extraction, the server receives that video's ID and temporarily handles the downloaded audio. Provider tokens and downloaded audio are never synced.
- Location is requested on demand for pins. The automation watch is a separate, explicit opt-in: a foreground service with a persistent notification that Android shows at all times while it runs. Turn the toggle off and every watch process stops.
- Spotify, SoundCloud, and Jellyfin tokens (plus SoundCloud's Client Secret and short-lived PKCE state) are stored with Android Keystore AES-GCM. The Google-account YouTube connection stores only an encrypted account label; Google services supplies an OAuth access token on demand, which Soundtrail holds in memory for the API request and never persists. The Client ID YouTube connection instead stores its own access and refresh tokens encrypted with Android Keystore, like Spotify. Disconnect clears local state and best-effort asks Google to revoke the grant. Backups are disabled. Revoke access any time from your provider account settings.
- TheAudioDB lookups send the artist and title you type and store nothing. Lookup-offered links must pass the same URL allowlist as manual links.
- The mood engine runs on the device: your description, your library, and the mix are never uploaded. Only if you switch the optional AI reading on — with your own key — does the mood description (plus the energy hint, the hour, and whether you are moving) go to Google AI Studio; song titles, artists, playlists, pins, and places never do, and turning it back off returns the app to the offline reading. The API key is Keystore-encrypted, never synced, excluded from exports, and removed with **Remove key**. Tag lookups are the only path that sends artist and title (to TheAudioDB), and only for the specific songs a mix is unsure about, and only when you have switched them on.
- **Jellyfin streaming playback can continue in the background.** Streams run under a `mediaPlayback` foreground service with a MediaStyle notification and lockscreen/Bluetooth controls. Streaming itself is not cached; a separate explicit playlist download saves eligible audio privately, capped at 250 MiB per item and subject to Jellyfin's access rules. The access token travels in a request header, not in a URL. There is no queue or next/previous track, and the media session uses the platform's deprecated-but-supported `android.media.MediaSession`.
- **Plain HTTP is permitted for the address you type yourself.** A home Jellyfin server usually speaks `http://`, and the platform's security config cannot know your server's address in advance, so `network_security_config.xml` allows cleartext traffic for the app. Soundtrail still contacts only the address you entered — every other provider API stays HTTPS-only, and certificates are validated against the system trust store with user-installed CAs never trusted.
- The official YouTube Data API feature is read-only metadata access; YouTube Music playback stays in its app. A separate, optional, unofficial self-hosted extractor can request audio-only streams for public videos after a rights confirmation. It uses no YouTube/Morphe session or cookies, does not handle access-restricted media, and may stop working if YouTube changes its site. Jellyfin downloads use the server's official authenticated endpoint and remain subject to your rights and the server's permissions.
- Motion detection is a GPS-speed estimate, not a sensor fusion product: expect misses at poor accuracy, and driving/running thresholds are approximate. Automations use cooldown-based re-arming (15 minutes), not edge triggering, so a rule can re-fire after its cooldown if conditions still hold.
- Without a location fix, use manual coordinates to create pins. **View place** still opens the pin in an external maps app or, with none installed, OpenStreetMap in a browser; the in-app map draws tiles itself and keeps them in memory only, so an area you have viewed works offline until the cache turns over, and a cold offline start shows markers over a blank canvas.
- SoundCloud permalinks are slug-based and canonicalized rather than re-derived; well-known non-artist paths (e.g. `/discover`, `/you/apps`) are rejected, but an unexpected new site section could in principle parse as a track and would simply open that page.

## Tests

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
python3 -m unittest discover -s server -v
```

Unit tests cover the existing URL/provider, backup, map, playlist, and automation behavior plus Jellyfin download validation, YouTube video-ID parsing, YouTube browser-OAuth URL/redirect/token handling, recovery-key encoding, AES-GCM event encryption, tamper rejection, deterministic tombstone merges, and sync-payload handling (including keeping local audio filenames out of ciphertext). They also pin down the two rules that protect a saved login — which token-refresh failures may ask for a new sign-in and which must keep the stored grant — and the mood layer: the on-device reading (negation, intensifiers, emoji, time-of-day tilts, low-confidence fallbacks), the tag vocabulary, the mixer's scoring/variety/deduplication rules, and the strict, clamping parser for an optional AI model's reply. Python `unittest` coverage exercises account/session isolation and deletion, opaque event pagination/idempotency, checkpoint compaction, rate limits including trusted-proxy IP handling, and authorized audio-route validation/stream cleanup. GitHub Actions runs the Android build and uploads a debug APK, plus the server API tests across Python 3.10 and 3.14.

Documentation: [YouTube Data API v3](https://developers.google.com/youtube/v3) · [Google OAuth for Android](https://developers.google.com/identity/sign-in/android/start-integrating) · [Google OAuth for installed apps](https://developers.google.com/identity/protocols/oauth2/native-app) · [Google Cloud Console](https://console.cloud.google.com/) · [Jellyfin API](https://api.jellyfin.org/) · [Jellyfin authentication](https://jellyfin.org/docs/general/administration/configuration/#security) · [Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow) · [SoundCloud API guide](https://developers.soundcloud.com/docs/api/guide) · [SoundCloud app registration](https://developers.soundcloud.com/docs/api/register-app) · [TheAudioDB free music API](https://www.theaudiodb.com/free_music_api) · [Foreground service types](https://developer.android.com/about/versions/14/changes/fgs-types-required) · [microG](https://microg.org/) · [OpenStreetMap](https://www.openstreetmap.org/copyright) · [CARTO map tiles](https://carto.com/developers/maps/)
