# Soundtrail — music for the places you love

An Android app that saves **Spotify**, **YouTube Music**, **SoundCloud**, and **your own Jellyfin server's** songs as private, location-based pins, with an **in-app map**, a **mood engine that turns how you feel into a mix from your own library**, offline-audio management, local playlists, opt-in place/motion/time automations, **managed on-device backups, JSON export, and server-side managed snapshots**, optional **end-to-end encrypted cloud sync (public server by default, self-hostable)**, and background playback with notification, lockscreen, Bluetooth and **Android Auto** controls.

## What connects, and how

| Service | Integration |
| --- | --- |
| Spotify | Authorization Code **with PKCE** (no client secret). Search for tracks with the official Web API; tap a pin to open playback in Spotify or a browser. Tokens are encrypted with Android Keystore. |
| SoundCloud | Authorization Code **with PKCE** (OAuth 2.1) against `secure.soundcloud.com`. Search for tracks with the official API; tap a pin to open playback in SoundCloud or a browser. Tokens **and the Client Secret** are encrypted with Android Keystore. Requires an API app on SoundCloud's side — see below. |
| Jellyfin (your own server) | Username + password sign-in against **your** server, read-only music-library search, in-app/background playback, and offline download through Jellyfin's authenticated `/Items/{id}/Download` route. The server enforces the signed-in account's access; Soundtrail downloads only items that account is allowed to retrieve, and caps each transfer at 250 MiB. The password is never stored; the access token is encrypted with Android Keystore. Works over HTTPS or plain HTTP on a trusted home network. |
| Song lookup (TheAudioDB) | **Free, no account.** Enter artist + title to get exact names, album, genre/mood, and — when TheAudioDB knows one — a Spotify or YouTube link that passes the same allowlist. Metadata only; nothing is stored. |
| YouTube Music app | Search and Library links are handed to the installed YouTube Music app, including Morphe's patched package, or to music.youtube.com. Share a song link back to Soundtrail to pin it. Soundtrail never reads the music app's private storage or session. **Opening YouTube Music requires no Google sign-in and no connection at all** — it is a link handed over exactly as a browser would — so this keeps working across app updates, reinstalls, and revoked API grants. |
| Mood mixes | **Free, on-device, no account.** Describe how you feel (or tap a quick pick); a small, explainable engine on the phone reads it into a sound profile and scores the songs in your own pins and playlists against it. Optional extras, both off by default: TheAudioDB genre/mood lookups and a model reading — a local **Ollama** server you run, **any OpenAI-compatible endpoint** (OpenAI, OpenRouter, a model on your own machine, a bridge to a service with no API of its own), **Arena** (one-tap preset; its free-router keys are issued for Arena's own coding harnesses, so this app needs a key from a workspace with API access, and it says so rather than blaming the key), or Google AI Studio with your own key. Your songs, playlists, pins, and places never leave the device. Before the first external request — and again when the provider or endpoint changes — the app names that destination and asks for approval; Cancel keeps the on-device reading. |
| YouTube playlists & search (optional) | An explicit sign-in requests only the `youtube.readonly` scope and calls the official YouTube Data API v3. **Client ID** opens Google in your browser with your own Desktop OAuth client and PKCE (no client secret); access and refresh tokens are encrypted with Android Keystore. It needs no Google services, no Google account on the phone, and no proprietary SDK, and it works alongside MicroG RE. The app has no Google Play services sign-in: that path was removed so the whole app is free software. Once connected, Soundtrail can both search videos in your playlists AND import those playlists (including Liked videos and Uploads) into your local library. **Likes are one list**: an import lands in the liked list you already have (the listener's own list wins over one an earlier import made), an older import's list is folded into it — a song already there under the same link is not added twice, and an offline copy travels with the song — and a name the app chose becomes "Liked songs". What cannot be imported is YouTube Music's account likes: the public API has no such endpoint, which is why the app's own text, and this file, say so instead of pretending. This does **not** expose YouTube Music-only collections (saved songs/albums/mixes); see setup below. |
| YouTube links | The official YouTube Data API integration is read-only metadata access. Soundtrail does not download or extract YouTube audio; YouTube songs open in the YouTube Music app. |
| Android Auto | Soundtrail is a media app for the car: Android Auto browses **pins and playlists** and plays the songs inside them, with play/pause, next/previous and a scrubber, through the same player the phone uses. Nothing is uploaded and no account is involved — the car sees the library already on the phone, and only rows that can actually play here are listed. See *Android Auto* below for how to enable it and who is allowed to connect. |
| Your audio files | Download eligible Jellyfin tracks for offline playback, or attach audio you already own or are authorized to use. Files are copied into private app storage; audio is not cloud-synced. |
| Map tiles | The Pins tab's **Map** view draws an OpenStreetMap-based map on-device: raster tiles are fetched from CARTO's public tile server (`basemaps.cartocdn.com`) for the visible area only. Tile requests reveal the visible map area; pin coordinates are rendered locally and are not included in tile requests. Attribution stays on screen. |
| Backup files | **Managed on-device snapshots** are written automatically after a change and the newest 10 are kept in private storage, with restore, delete, and an opt-out in **Connect → Backup & restore**. **Export** still writes a JSON file you choose; **Import** reads one back additively. **Android Auto Backup** may also copy pins, playlists, and automations to your Google account — never provider tokens, the sync session, audio files, or the app's own snapshots. The JSON file goes only where you put it. |
| Optional cloud sync | By default connects to the public Soundtrail sync server; you can instead point it at your own self-hosted Soundtrail API over HTTPS. Pins (including coordinates), playlists, song links, and automations sync two-way, including edits and deletions. Events and compacted checkpoints are encrypted on-device with AES-256-GCM. The recovery key is user-held and cannot be reset by changing the account password. |
| Location & motion | `LocationManager` on demand, plus an **opt-in** foreground watch service for automations. Motion (driving/running/walking) is inferred **on-device from GPS speed** by default — no advertising ID. The app contains no Google Play services or Google Activity Recognition code, so motion always comes from GPS speed. |
| Time | The watch checks the current time every minute; automations can fire only inside a time window (wraps midnight). The Lists tab shows the live clock. |

## Playlists & automations

- **Playlists** (Lists tab) are named groups stored locally by default. Optional cloud sync encrypts playlist metadata and song links before upload. A song needs a title; supported provider links are optional — without one, tapping play opens a YouTube Music search in the installed app or browser.
- **Jellyfin songs** are stored as *item references* (`jellyfin:item:<id>`), never as server URLs: addresses and access tokens do not enter pins, playlists, backups, or cloud-sync records. Playback resolves against the current connection; direct play is tried first, with an automatic MP3 transcode fallback. Download eligible audio through Jellyfin's authenticated endpoint; its account permissions remain authoritative. **YouTube audio** can be requested through the self-hosted API for public audio-only streams, with an explicit rights confirmation; that extraction is unofficial and does not use YouTube login cookies. Both kinds of offline audio stay on-device and are not backed up or cloud-synced.
- **Backups, in three managed layers.** **On-device snapshots** are written automatically a few seconds after a change to pins, playlists, or automations (debounced, so a burst of edits is one file); the newest 10 are kept in private app storage and can be restored, deleted, or switched off from **Connect → Backup & restore**. They use the same JSON document as the manual export, so audio files and provider logins are not in them. **Android Auto Backup** may additionally copy pins, playlists, and automations to your Google account under the system's own settings; the rules explicitly exclude the Keystore token store, the cloud-sync session, settings, audio, and the managed snapshots. **A JSON export/import** remains the portable file you choose. On the server side, the self-hosted API keeps **verified database snapshots** automatically (see below). All three are separate from cloud sync: backups are readable JSON or encrypted database copies; cloud sync sends only encrypted event envelopes. Account logins and local audio files stay on-device; restore or sync may require reconnecting providers or downloading local audio again.
- **Get a song (external handoff):** the ⬇ action for other providers shares the song link or YouTube Music search URL. Jellyfin items use their authenticated offline-download action.
- **Import audio:** the ♪ button attaches an audio file from your device to a playlist song. The file is copied into private storage and shows as *On device*; tapping play opens it in your music player, fully offline. Removing the song or playlist deletes the copy.
- **Offline storage management:** Lists shows total local audio storage; **Manage** opens a largest-first file list with per-file sizes and playlist details. Remove individual copies or clear all offline files; playlist entries are kept. Removing a song or playlist still deletes its attached file.
- **Automations** point a playlist at conditions: *near a saved spot (20–2000 m radius)*, *while walking / running / driving*, and/or *between two times of day*. Set any combination; all set conditions must hold at once.
- **The watch**: an opt-in foreground service (persistent notification — required by Android and kept honest by it) checks location, motion, and time. When a rule matches it posts a notification with **Play playlist** and **Open Soundtrail** actions. Nothing plays without a tap, and a rule re-arms after a 15-minute cooldown.
- **Motion inference**: GPS speed bands — under 0.5 m/s stationary, to 2.6 walking, to 7.5 running (covers cycling), above that driving. It is an estimate; tunnels and poor GPS delay or miss triggers.
- **Battery**: continuous GPS watching uses noticeably more battery. The watch runs only while its toggle is on and its notification is visible.
- **Privacy**: by default, pins, coordinates, playlists, automations, and imported audio stay on the device. If you opt in to cloud sync, pins (including coordinates), playlist metadata, song links, and automation rules are encrypted before leaving the phone; the server stores ciphertext and account/session metadata for sync. The separate YouTube extractor receives the requested video ID and temporarily processes its audio. Downloaded audio and provider credentials are never synced. Map tiles reveal only the visible area, not your saved pin coordinates. For YouTube playlist search, Soundtrail fetches metadata and matches your query on-device; it does not send the query to YouTube.

## Android Auto

Soundtrail is a **media app for the car**. Plug the phone in (or connect wirelessly), open
Android Auto, and Soundtrail appears in the media launcher with this phone's own library:

- **Pins** and **every playlist** are browsable lists, and the songs inside them are the rows
  that play. A song with no link and no attached copy is left out rather than shown as a row
  that does nothing.
- Tapping a song plays it, queueing the list it came from: **next**, **previous** and the
  scrubber in the car work, and the open list highlights the row that is playing.
- Pressing play with nothing chosen starts the first song of your first playlist, or of your
  pins if you have no playlists.
- **Voice search works on-device**: "play <song> by <artist> on Soundtrail" is matched against
  the titles, artists and places already saved on the phone. The query is never sent anywhere.
- What the car shows is exactly what the app's own bar and the notification show, because all
  of them drive one player and one queue. Pausing on the phone pauses the car, and the reverse
  is true too.

**Enabling it.** The app declares the car's media service and its browse tree; on the phone,
Android Auto decides what to list. A release built by Google Play has to be reviewed for
Android Auto before it appears there. For a debug or sideloaded build, open the **Android Auto
app → Settings → Version** (tap it ten times) to unlock **Developer settings**, then enable
**Unknown sources** — the app then appears in the launcher, and in the **Desktop Head Unit**
(`desktop-head-unit`) used for testing. Apps that are not approved stay invisible in a
production car even with developer mode on, so this is a per-build decision as much as a
per-phone one.

**Who may connect.** The car runs in another app, so the browse service has to be exported —
and something has to decide who is asking. `AutoClientPolicy` allows the app itself, the known
car hosts (`com.google.android.projection.gearhead`, Android Automotive's `com.android.car.media`),
the Google app, `com.android.systemui`, and anything the platform has already trusted with
`MEDIA_CONTENT_CONTROL`. The caller is identified by the **uid the system reports**, not the
package name it claims, so an app cannot borrow one of those names. Everyone else is answered
with "no root": the pins, the places and the playlists stay invisible. If you use another car
host and want it listed, add its package to `KNOWN_HOSTS` in `AutoClientPolicy`.

The car never sees more than the app's own library: no provider token, no Jellyfin address, no
audio file, and no screen of the app itself.

## Ask: talk to it, hear the answer

The **Ask** tab (the microphone in the bottom bar) takes a request by voice or keyboard and answers
it with songs and a sentence:

- **Say it.** Tap the microphone, say "play Sunsets by The xx", "something calm for the drive", or
  just "Midnight City". The words appear as they are recognised, so a misheard request can be seen
  and stopped instead of discovered afterwards. The microphone is asked for at that moment, never
  at launch, and the recognizer is the phone's own — Google's, or an offline engine.
- **It reads the request before searching.** Command words come off the front ("hey soundtrail,
  could you play …"), an "on YouTube Music" tail says where to look without becoming part of the
  song, and "by <artist>" keeps the artist for a search that would otherwise find covers. Only one
  command verb is ever taken off, and only when a title of at least two words follows it — so
  "Play That Funky Music", "Play Search and Destroy" and a song called "Play" all survive, and the
  words as you said them are kept for a second look if the first search finds nothing.
- **Your library answers first.** Pins and playlist songs are matched on the device — no network,
  no account — and an offline copy plays from the phone. Every word you said has to match
  something in the song, so "the xx sunsets" cannot answer with every song that has a "the" in it.
- **Then YouTube's music catalogue.** With a Google account connected on the Connect tab, the
  request is searched against YouTube's music videos, lyric videos and audio uploads, and the
  results play in Soundtrail like any other song. **YouTube Music's account library — saved songs,
  albums and your likes there — is not open to other apps**, so songs are looked up by name; each
  row carries a **YT Music** button that opens that same song in the YouTube Music app, where your
  own account and quality apply. Rows are cleaned of production noise ("(Official Video)") while
  keeping what changes the recording ("(Live)", "(Kygo Remix)").
- **It can answer out loud.** Answers are spoken through the phone's text-to-speech engine; the
  switch on the card turns that off. Describing a feeling ("something chill", "put me in the mood
  for a party") hands the request to the mood engine instead, which builds a mix on the Mood tab,
  and the answer says what it read and how many songs it found.
- **The songs it finds are queued**, so the notification, the lockscreen, Android Auto and the Ask
  tab all move through the same list, and the reply says how many are behind the one playing.

Nothing you say is stored by Soundtrail: the words are used for the request in front of you. The
only thing that leaves the device is the search phrase, and only when a Google account is
connected, exactly as the Search tab's YouTube search does.

## Mood mixes

The **Mood** tab turns how you feel into music you already own a link to. Type a few words
(*"tired but I want something warm for the drive home"*), or tap one of the quick picks, and tap
**Build my mood mix**. Soundtrail reads the description and ranks every song in your pins and
playlists against the sound it asks for — each row shows why it was picked ("mood fits", "energy
fits", the tags that overlapped, or "no sound tags yet"), and the mix plays through the same
in-app player as everything else, advancing song by song.

- **On-device by default.** The reading is a small, explainable bag-of-words model on the phone: phrases, negation ("not happy"), intensifiers, emoji, plus gentle tilts from the time of day and — only while the automation watch is on — whether you are walking, running, or driving. No key, no account, no network. Nothing about your library is ever sent anywhere for this.
- **Four places a reading can come from**, picked in the Mood tab. **On-device** is the default and needs nothing. **Ollama** runs the model on hardware you control — this phone, a computer on your network, or a server you rent — over its native API. **OpenAI-compatible** is any endpoint that speaks `/v1/chat/completions`: OpenAI itself, OpenRouter, **Arena** (a one-tap preset), a model on your own machine, or a bridge in front of a service that has no API of its own. **Google AI Studio** is a hosted model with your own key. Whichever you pick, only your mood description plus the energy hint, the hour, and whether you are moving is sent; song titles, artists, playlists, pins, and places stay on the device. Every failure (no address, no key, no network, quota, an unreadable reply) falls back to the on-device reading and says so, so the feature never depends on a model being reachable.
- **The destination is named before anything is sent.** Switching a ready model on, pressing **Test**, or building a mix with a model requires one approval for that provider *and* that endpoint; a half-filled provider card turns on without asking, and the question follows when there is a real address to name. **Send mood data to this endpoint?** shows `Provider: … · Endpoint: …` (the normalized address the request is actually built from), says that the request carries your mood description and the Privacy Policy's context — the energy hint, the hour, and whether you are moving — under the provider's retention, training, and billing terms, and offers **Cancel**. Cancel sends nothing, leaves the on-device engine reading your words, and switches the model reading back off rather than asking again at every request. Changing the provider or the address is a new destination and asks again. The approval is stored on the device, never backed up or synced, and can be withdrawn at any time in the Mood tab (**Ask again before sending**), which makes the very next request ask and name the destination again. On-device readings never prompt because nothing leaves the phone.
- **Setting up Ollama.** Enter the server's address on the Mood tab — `192.168.0.20:11434`, `http://nas.local:11434`, or a pasted `…/api/chat` endpoint all work; **Find models** lists what the server has pulled and lets you tap one. Ollama must be reachable from the phone: start it with `OLLAMA_HOST=0.0.0.0:11434`, and use the machine's address on your network, because on a phone `localhost` means the phone itself. If your Ollama sits behind a proxy that wants a token, there is an optional field for it (Keystore-encrypted, like every other secret here). Local models can take up to a minute to answer, so the app waits three before falling back. **Test** reports exactly what happened — reached, how many models, whether the selected one exists.
- **Setting up an OpenAI-compatible endpoint.** Paste the address on the Mood tab — `https://api.openai.com/v1`, `openrouter.ai/api/v1`, `192.168.0.20:8000`, or a whole `…/v1/chat/completions` copied out of a README. The address is accepted however you found it: an endpoint path is trimmed back to its base, a bare host gets the `/v1` every one of these APIs lives under, and a LAN address typed with no scheme is assumed to be plain HTTP (which is what a model server or bridge on your own network usually is) while a public host is assumed to be HTTPS, because this provider sends a key to whatever is on the other end. **Find models** lists what the endpoint advertises and lets you tap one; if it publishes no list at all, type the model name and the app uses it anyway. The key is optional — a server on your own machine often wants none, and when the field is empty no `Authorization` header is sent. **Test** reports which of the three happened: a model list, a real reading, or exactly what the endpoint said went wrong.
- **Arena.** Arena publishes an API and it speaks this protocol, so it needs no provider of its own. Tap **Use Arena (preview)** on the OpenAI-compatible card and the address and router alias are filled in from Arena's own portal (`https://api.preview.arena.ai/v1` and `coding-router-preview`); create a key on Arena's **Keys** page, paste it into the API key field, save, and press **Test**. **Find models** then lists exactly what that key is enabled for, read the same way Arena's own `setup.sh` reads it: models come only from authenticated discovery, and rows that are not callable are skipped (a `routing` row, and Arena's `exo` entry). A key may have any one of Arena's router aliases rather than the one the preset fills in, so if discovery shows another — `claude-coding-router-preview`, `claude-max-v0`, `max-v0` — the app selects the one the key actually has, and says so instead of failing the next request. A key that has **no** router alias at all, only named models (`claude-fable-5-1`, `gpt-6-sol`, `gemini-3.8-flash`, `kimi-k3`, `grok-4.7`, …), is not a dead end either: the app then selects the first model the key is enabled for, which is exactly the `models[0]` fallback `setup.sh` makes. Either way the name comes only from authenticated discovery — never from a list baked into the app — and either way it can be changed by tapping any model in the picker. The picker shows each model by the name the endpoint itself gives it, so Arena's entries read the way Arena writes them — the router appears as `(Arena) Coding Router`, not as the id underneath it — and the Model field always shows the exact id that will be sent, because that is what answers. This is the *preview* gateway: if Arena moves it, the three values live in one place in the source (`MoodAiUrls.ARENA_*`) and nothing else needs to change. **A free-router key is issued for Arena's own harnesses only** — interactive Claude Code, the Code tab in Claude Desktop, and OpenCode — so a request from anything else, this card included, is answered `403 free_router_harness_restricted` (and Cowork, print mode, and the Agent SDK get `organization_client_restricted` where a workspace blocks them); a key from a workspace with API access reads here like any other endpoint's. The app names that refusal for what it is — read from the error code, never from the sentence around it, so a reworded message on Arena's side cannot turn it back into "wrong key" — and says that the key is not the problem, because re-creating the same free-router key would only repeat it. **Test** is honest on Arena for the same reason: a model list is not an answer, and Arena's usage page records prompts, so pressing Test there sends one short reading — exactly the request the Mood tab makes — and reports that this one call is what appears in Arena's usage. A key that has only ever listed models leaves that page empty, which is a property of the gateway, not a fault in the key; the note under the field also says when the reading switch itself is still off, since that keeps a working key out of the loop too. Arena's gateway also speaks the Anthropic Messages API and the OpenAI Responses API, which this app does not use; only its OpenAI-compatible `/v1` route is called here. The community projects that wrap Arena's *web* product with a signed-in browser cookie are a different thing again — that is against its terms and breaks whenever the site changes, and Soundtrail has nothing to do with it.
- **API keys** (Gemini, an OpenAI-compatible endpoint, or an Ollama proxy token) are encrypted with Android Keystore: never synced, never backed up, and excluded from exports.
- **Optional tag lookups.** Turn on *Look up genre & mood tags* and Soundtrail can ask TheAudioDB's free metadata API about the specific songs nothing is known about (artist + title only, capped per run, and it remembers what it learned). This is what lets a track whose title says nothing — `Track 1` — still be matched by its genre.
- **Save it.** *Save this mix as a playlist* writes a playlist named `Mood · <mood>` and replaces the songs the mix generated on a re-save, so today's mood does not pile up forever. Hand-added songs in that playlist are kept.
- **Bring it to YouTube Music.** *Open this mood in YouTube Music* hands the mood's search phrases to the YouTube Music app (or `music.youtube.com`) with no sign-in, for the music you have not pinned yet.
- **Find music on a named provider.** The Mood tab has a **Find music on** card, and it says exactly what it does: when a reading comes from one of the model providers, the model is asked to **find music on <provider> that fits** your words — the request is built from the pill you chose (*your own library*, *YouTube*, *YouTube Music*, *Spotify*, *SoundCloud*, *your Jellyfin server*) and the sentence that will be sent is shown on the card, word for word, before anything is. The model answers with songs, each with a line about why it fits. **Every song is then looked up for real on the provider you chose** (or matched against your own pins and playlists), and only what the search actually returns becomes a row: a tap plays it through the same in-app player as everything else. A song the model made up matches nothing and is left out — the note under the rows says how many of the named songs were found and how many could not be matched — because a model's suggestion is a suggestion, not a source. Where a search result is real but this app cannot honestly start it — a **YouTube Music** suggestion, since that service publishes no API this app may search with, or a Spotify result whose full song belongs to Spotify's own app — the row says so and **Open** takes you to the real song instead of pretending to play it. A Spotify row still plays its 30-second preview here; SoundCloud, Jellyfin, and your own library play in full. With no model in the loop — on-device reading, a quick pick, or a model that failed — the chosen provider is instead searched with the reading's own phrases, so the card still does something useful. *the app is doing the searching: it never plays a song because a model named it, and it never asks a provider to stream something for you that you have no right to hear.*

## Offline audio and source permissions

Jellyfin offline downloads remain authenticated against your own server and subject to its account permissions. Jellyfin offline downloads are limited to 250 MiB per track. The **Get** action for other links still hands the link to an app you choose; Soundtrail does not bypass access controls or download content you do not have rights to keep.

## YouTube Music and official API access

Morphe patches a separate YouTube Music app; it is not an SDK or a third-party library API. Soundtrail can detect Morphe's patched package, open searches or the Library inside it, and receive song links shared back from it. **None of that needs the optional Google connection**: handing a search or a song link to the music app works like a browser link, with no sign-in, so opening YouTube Music from Soundtrail keeps working across updates no matter what happens to the API connection. Separately, an optional, explicit Google OAuth connection can use the official YouTube Data API to search videos found in accessible playlists.

**The saved Google connection survives app updates.** Access and refresh tokens are encrypted with Android Keystore and never deleted by a failed refresh, a timeout, or an app update; the app renews the token by itself when it starts, and writes a rotated refresh token to disk *before* using it, so an update (or a crash) can never leave the device holding a token Google will not accept. Only Google itself can end a connection — you revoked it, changed the password, or the Cloud project's OAuth consent screen is still in **Testing**, which makes Google expire grants after 7 days. In that one case the Connect tab says exactly which of those it was, keeps the Client ID and account label, and offers a one-tap **Reconnect Google** — no re-typing, no re-setup.

Soundtrail has no Google-account (Play services or microG) sign-in and never reads microG or Morphe private storage. MicroG RE users should use the browser-based Client ID sign-in, which needs no Google services at all. The official API does not reproduce the YouTube Music library: saved songs, albums, artists, mixes, and other Music-only collections may be unavailable. The optional audio extractor is a separate, unofficial server-side component; it uses no Google token or YouTube Music session, supports public audio-only streams, and must be used only with content you are authorized to download. It does not bypass logins, DRM, paywalls, or access restrictions. Spotify and SoundCloud playback also stays in their own apps rather than being streamed by Soundtrail.

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

The server sees account email, authentication metadata, request timing, event IDs, and ciphertext sizes. Run it only behind HTTPS, protect and back up its SQLite volume, and keep its host patched. The server includes per-process request rate limits, a per-account encrypted-sync quota, and authenticated account deletion, but no email verification or password-reset service; use reverse-proxy limits as well, especially with multiple instances. See [`server/README.md`](server/README.md).

Automatic sync is **off by default**. Enable **Sync automatically when Soundtrail opens** in Connect to sync at app launch; it requires a signed-in account and this device's saved recovery key and does not run while the app is closed. Connect also shows pending local changes; after a sync failure, **Retry sync** runs the same merge again. In the same section, **Delete account and remote sync data** removes the server account/history only; local pins, playlists, automations, and offline audio remain on the device.

### Deploy your own server (optional)

1. Run the sync server natively (Python 3.10+, no Docker, any OS or CPU) behind a TLS reverse proxy for a domain you control. See [`server/README.md`](server/README.md) and [`server/Caddyfile.example`](server/Caddyfile.example).
2. From the repository, run `cd server && python3 run.py` (`py -3 run.py` on Windows). The service binds to loopback by default for the proxy; do not expose port 8080 directly.
3. The server manages its own backups: it writes an integrity-checked SQLite snapshot at startup and then every 24 hours, keeping the newest 7 under `server/data/backups`. `SOUNDTRAIL_BACKUP_INTERVAL_SECONDS` changes or disables that (`0`), and `SOUNDTRAIL_BACKUP_KEEP` changes retention. `python3 run.py backup`, `list`, and `verify` work without stopping the service; `python3 run.py restore <name> --yes` replaces the database and must be run with the server stopped. Copy the backups off-host — they contain account metadata and sync ciphertext, not plaintext library data. See [`server/README.md`](server/README.md).
4. In Soundtrail, open **Connect → Cloud sync**, replace the default URL with your public `https://` URL, and create an account. Save the recovery key, then tap **Sync now**. On another device, enter your server URL, sign in to the same account, and enter the saved key.

If you don't want to self-host, leave the server URL set to the default public server and create your account there instead.

Backups are separate from cloud sync: managed snapshots and the JSON export are readable files on the device you control, Auto Backup is Android's copy to your Google account, and the server's snapshots are encrypted-at-rest only because the sync payloads already are. None of them replaces the recovery key: cloud sync's ciphertext still needs that key on a new device.



**SoundCloud access:** SoundCloud treats *every* API client as confidential, so its token exchange requires both a Client ID **and** a Client Secret — unlike Spotify, there is no public-client mode. Registering an API app requires an eligible SoundCloud subscription or an approved API request; check [SoundCloud for Developers](https://developers.soundcloud.com/docs/api/register-app). The Secret is stored only in Android Keystore-encrypted storage on this device.

**TheAudioDB free key:** Soundtrail calls TheAudioDB's v1 API with their documented shared free key (`123`, formerly `2`). The shared key can be rerouted or rate-limited at any time — an inactive route returns HTTP 404 with `{"Message":"Not found"}`. If it stops working, check [TheAudioDB's API page](https://www.theaudiodb.com/free_music_api); premium keys use the same URL format and only require changing `AUDIO_DB_FREE_KEY`.

## YouTube playlist search setup

The optional YouTube connection is independent of signing in to YouTube Music/Morphe. The sign-in requests only the `https://www.googleapis.com/auth/youtube.readonly` scope for Data API access (no YouTube write scope). It does not inspect app data or extract credentials from any other app.

Common steps for both options:

1. Create or select a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Configure the OAuth consent screen (choose the appropriate audience and add your account as a test user while developing). Google may require verification or test-user access for the YouTube read-only scope, depending on the project and distribution. While the project's publishing status is **Testing**, Google expires the grant after 7 days and you reconnect; publishing the project avoids the expiry (the unverified-app warning remains until Google verifies it).
3. Enable **YouTube Data API v3** in that project.

**Client ID in the browser (the only sign-in; works with MicroG RE, or with no Google services).** Soundtrail does not sign in through a device Google account or microG. Under **APIs & Services → Credentials**, create a **Desktop app OAuth client ID** (creation asks for a name only; no redirect URI registration is needed and no secret is used — Desktop clients use PKCE). In Soundtrail's **Connect** tab, enter that Client ID (`123…-abc….apps.googleusercontent.com`) and choose **Connect with Client ID**. Google opens in your browser; approve the read-only permission and you return to the app. No device Google account, Play services, or microG account is required. Then choose **YouTube** on the **Search** tab.

Soundtrail validates the grant with a read-only `playlists.list` request. When **Import automatically when connected** is on (default), Soundtrail pulls the accessible playlists as soon as sign-in completes; tap **Import playlists now** anytime to refresh. Playlists and items use deterministic ids (`yt-pl:<playlistId>` / `yt:<playlistId>:<videoId>`), so re-importing is idempotent — only newly discovered videos are appended and any offline copies or edits you made stay attached. Import is capped at 200 playlists and 2,000 items per list. The YouTube Data API does **not** expose YouTube Music-only collections (saved songs/albums/artists, Supermix, Daylist, My Supermix, "Your likes" mixes, etc.); those stay in the YouTube Music app. Search scans at most the first 50 of your playlists and the first 50 video items in each, then matches the terms locally and returns at most 20 results. This is deliberately bounded; older playlists/items beyond those pages won't be found. It searches playlist videos only, not the complete YouTube Music catalog or all saved Music-library types. If the browser sign-in reports a rejected redirect or Client ID, make sure the ID comes from a **Desktop** client (Android and Web client types do not support this flow).

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

The car and notification transport icons (`drawable/ic_media_play.xml`, `ic_media_pause`,
`ic_media_next`, `ic_media_previous`, `ic_media_stop`) are hand-written, and deliberately built
from whole command groups so the same check passes them. `drawable-nodpi/soundtrail_artwork.png`
is the app's mark as a plain PNG: a car host loads an icon URI itself, and the `ic_soundtrail`
mipmap id resolves to the adaptive **icon XML** on API 26+, which it could not decode.

The app draws that same icon for its own logo: `BrandHeader`, the mark beside the wordmark at the
top of every tab, renders `drawable/ic_soundtrail` rather than a second, hand-drawn copy of the
mark. The icon's own padding is the adaptive icon's safe zone and its tile is the colour of the
app's background, so what shows in the header is the pin; change the geometry in the generator and
both the launcher icon and the in-app logo change together.

The same file writes `drawable/ic_soundtrail_notification.xml`, the status-bar icon for the
automation watch and the playback notification. Those are drawn at 24dp and tinted a single
colour by the system, so that icon is a flat silhouette — the pin with the note knocked out of
it — rather than the launcher artwork, whose dark tile would render as a solid blob.

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

7. For the optional **YouTube playlist search**, follow [YouTube playlist search setup](#youtube-playlist-search-setup), then connect with a Client ID in Soundtrail. To use the separate Morphe-patched YouTube Music app, install/configure Morphe and sign in there (including MicroG RE if that build uses it — the Client ID sign-in is the option that works alongside it). Soundtrail can open the app's search or Library and receive shared song links; it never imports Morphe's session or reads private app data.

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

**The uninstall deletes this device's app data.** Local pins, playlists, automations, downloaded/imported audio, the app's managed snapshots, sync session, and the device's encrypted copy of the recovery key disappear with the package. If Android Auto Backup is enabled on the phone, the system may restore pins, playlists, and automations on a reinstall — but it is best-effort and copies nothing else, and the activity-recognition and mood/mood-consent opt-ins deliberately ask again. Synced ciphertext remains on your self-hosted server, but you must have separately saved the recovery key to decrypt it after reinstalling. Stored provider logins go too; reconnect those accounts afterwards. Before uninstalling, tap **Export backup** for a portable JSON file and make sure your cloud recovery key is saved somewhere private.

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
| Microphone | The Ask tab's voice input, requested when the button is pressed and only then. The audio goes to the phone's own speech recognizer; Soundtrail never records or keeps it |
| Post notifications | The watch status, automation triggers, and playback controls (Android 13+) |
| Activity recognition | Not included. Motion for automations comes from GPS speed only, so the app contains no Google Play services or Activity Recognition code and makes no Play services call. |

## Try it

- **Explore:** tap **Drop a pin**. Enter a song title and a supported link. Use **Locate** or enter coordinates manually; name the place and save. On the Pins tab, switch **List ↔ Map**: the map shows every pin in its provider colour — tap one for its card, pinch/double-tap to zoom, drag to pan, and **long-press anywhere to drop a new pin** at that exact spot. Type in **Find a soundspot** and the map goes to the song you name with its card open, **Me** centres the map on your fix, and **Pin** drops a soundspot where you are looking rather than under a long press.
- **Ask:** open the **Ask** tab, tap the microphone and say what you want to hear — or type it. Answers are spoken back unless you switch that off. A Spotify result carries a **play** button that opens the whole track in the Spotify app; tapping the row pins the song instead.
- **Search:** connect Spotify or SoundCloud and switch the provider chip. For optional read-only YouTube playlist search, configure Google Cloud as described above, connect with a Client ID on the **Connect** tab, and choose **YouTube**; queries are matched locally against videos in your playlists. This does not cover the full YouTube Music library. The separate **YouTube Music** search field opens results in YouTube Music or Morphe; **Open your YouTube Music library** and share-back are available without OAuth. The **free TheAudioDB lookup** (artist + title, no account) and direct song-link paste are also available.
- **Mood:** open the **Mood** tab, describe how you feel in a few words (or tap a quick pick and an energy level), and tap **Build my mood mix**. Soundtrail reads it on the device and ranks the songs in your pins and playlists against the mood; each row explains itself, and the mix plays in order. Save it as a playlist, or hand the mood to YouTube Music — the latter needs no sign-in. The Explore tab's *What's your mood?* card and the same YouTube Music button are one tap in.
- **Share:** in Spotify, YouTube Music, or SoundCloud, share a **song** link to Soundtrail to open a prefilled pin editor.
- **Jellyfin:** connect your server on the Connect tab, pick the *Jellyfin* chip on Search, and add its songs to a pin or playlist. Stream in-app or in the background with notification/lockscreen controls. In a playlist, tap the download icon to save an eligible track privately for offline playback; Jellyfin decides whether your account may download it.
- **In the car:** install the app, allow it in Android Auto (developer mode + unknown sources for a sideloaded build), and pick Soundtrail from the media launcher. Your pins and playlists are there; a playlist song plays through the rest of its list.
- **Backup and sync:** on the Connect tab, leave **Managed on this device** on and Soundtrail keeps the newest 10 rolling snapshots by itself; **Back up now**, **Restore…**, or **Delete…** manage them, and **Export backup** still writes a JSON file you choose. Android Auto Backup may also copy pins, playlists, and automations to your Google account when the phone's system settings allow it. For other devices, use end-to-end encrypted cloud sync from **Connect → Cloud sync** — either the public default server or one you deploy yourself. Save the recovery key before using another device.
- **Lists:** create a playlist and add songs. Jellyfin items can be downloaded offline with the download action; use **♪** to attach an audio file you already own. Other **⬇ Get** actions only hand a link to another app. Turn on the watch and create an automation — for example, *"Morning commute" → playlist "Drive" · while driving · 07:00–09:00* — then tap the notification when it fires.
- Supported links: `open.spotify.com/track/<id>`, `spotify:track:<id>`, `music.youtube.com/watch?v=<id>`, `youtube.com/watch?v=<id>`, `youtu.be/<id>`, `soundcloud.com/<artist>/<track>` (including `www.`/`m.` hosts and `/s-<token>` secret trails), and `on.soundcloud.com/<token>` or `soundcloud.app.goo.gl/<token>` share links. Tracking parameters are removed. Playlists, artist profiles, site pages, and unknown domains are rejected.

## Privacy and limitations

- By default, pins, playlists, and automations (including coordinates and song links) are saved only in private app storage. Optional cloud sync sends their client-encrypted event envelopes to the self-hosted server you choose; for sync it sees account/session metadata and ciphertext sizes, but not item contents or the recovery key. If you invoke YouTube extraction, the server receives that video's ID and temporarily handles the downloaded audio. Provider tokens and downloaded audio are never synced.
- Location is requested on demand for pins. The automation watch is a separate, explicit opt-in: a foreground service with a persistent notification that Android shows at all times while it runs. Turn the toggle off and every watch process stops.
- Spotify, SoundCloud, and Jellyfin tokens (plus SoundCloud's Client Secret and short-lived PKCE state) are stored with Android Keystore AES-GCM. The YouTube connection stores its access and refresh tokens encrypted with Android Keystore, like Spotify. Disconnect clears local state and best-effort asks Google to revoke the grant. Provider tokens are excluded from Android Auto Backup, the managed on-device snapshots, and JSON exports; their Keystore ciphertext could not be decrypted on another device anyway. Revoke access any time from your provider account settings.
- TheAudioDB lookups send the artist and title you type and store nothing. Lookup-offered links must pass the same URL allowlist as manual links.
- The mood engine runs on the device: your description, your library, and the mix are never uploaded. If you switch a model on and approve that exact provider and endpoint, the mood description (plus the energy hint, the hour, and whether you are moving) goes to the address you chose — your own Ollama server, an OpenAI-compatible endpoint you typed in, or Google AI Studio — and nothing else does: song titles, artists, playlists, pins, and places never leave the device, and switching back to **On-device** returns to the offline reading. Secrets (a Gemini key, an endpoint key, or an Ollama proxy token) are Keystore-encrypted, never synced, excluded from exports, and removed with **Remove**. Tag lookups are the only path that sends artist and title (to TheAudioDB), and only for the specific songs a mix is unsure about, and only when you have switched them on.
- **Jellyfin streaming playback can continue in the background.** Streams run under a `mediaPlayback` foreground service with a MediaStyle notification and lockscreen/Bluetooth controls. Streaming itself is not cached; a separate explicit playlist download saves eligible audio privately, capped at 250 MiB per item and subject to Jellyfin's access rules. The access token travels in a request header, not in a URL. The same background service, notification and media session carry every other provider too — Spotify previews, SoundCloud streams, YouTube audio prepared by your own sync server, and attached offline copies — so lockscreen and car controls always act on one stream.
- **A spoken request leaves the device as a search phrase, and nothing else.** The microphone is the phone's own speech recognizer, so the audio is handled by whichever engine the phone has (usually Google's), not by Soundtrail; the app keeps the words only for the request in front of you. If a Google account is connected, the phrase is the one thing sent to YouTube's search API — the same call the Search tab makes — and a request that is answered from your own library sends nothing at all. Speaking is the phone's text-to-speech engine and needs no network of its own.
- **The car sees the library, not the logins.** Android Auto browses pins, playlists and their songs through an exported media browser service; the connection is refused to callers that are not the car hosts, the system's media surfaces, or apps already trusted with media control (`AutoClientPolicy`). Provider tokens, the Jellyfin address and downloaded audio are never reachable through it, and playback still resolves through the app's own connections.
- **Plain HTTP is permitted for the address you type yourself.** A home Jellyfin server usually speaks `http://`, and the platform's security config cannot know your server's address in advance, so `network_security_config.xml` allows cleartext traffic for the app. Soundtrail still contacts only the address you entered — every other provider API stays HTTPS-only, and certificates are validated against the system trust store with user-installed CAs never trusted.
- The official YouTube Data API feature is read-only metadata access; YouTube Music playback stays in its app. Jellyfin downloads use the server's official authenticated endpoint and remain subject to your rights and the server's permissions.
- Motion detection is a GPS-speed estimate, not a sensor fusion product: expect misses at poor accuracy, and driving/running thresholds are approximate. Cycling is read as running. GPS speed is the only motion source, so no Google activity data is used and no Play services call is made. Automations use cooldown-based re-arming (15 minutes), not edge triggering, so a rule can re-fire after its cooldown if conditions still hold.
- **Backups have a deliberate boundary.** Anything that leaves the phone in an Android Auto Backup is governed by the listener's Google account settings; the app's rules include only pins, playlists, and automations. Managed on-device snapshots stay in private storage and are deleted with an uninstall. The self-hosted server's snapshots include account emails, password and session hashes, event metadata, and ciphertext — never a decryption key or plaintext library data — so the backup folder must be protected and copied off-host.
- **The mood destination prompt is a real gate, not a notice.** No external mood request — a reading or the **Test** probe — is built until the exact provider and normalized endpoint have been approved on this device. Changing the provider, the Ollama address, or the OpenAI-compatible base invalidates that approval, and the Mood tab can withdraw it outright; there is no setting that silently skips the prompt.
- Without a location fix, use manual coordinates to create pins. **View place** still opens the pin in an external maps app or, with none installed, OpenStreetMap in a browser; the in-app map draws tiles itself and keeps them in memory only, so an area you have viewed works offline until the cache turns over, and a cold offline start shows markers over a blank canvas.
- SoundCloud permalinks are slug-based and canonicalized rather than re-derived; well-known non-artist paths (e.g. `/discover`, `/you/apps`) are rejected, but an unexpected new site section could in principle parse as a track and would simply open that page.

## Tests

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest     # needs a device or emulator
python3 -m unittest discover -s server -v
```

Unit tests cover the existing URL/provider, backup, map, playlist, and automation behavior plus Jellyfin download validation, YouTube video-ID parsing, YouTube browser-OAuth URL/redirect/token handling, recovery-key encoding, AES-GCM event encryption, tamper rejection, deterministic tombstone merges, and sync-payload handling (including keeping local audio filenames out of ciphertext). They also pin down the two rules that protect a saved login — which token-refresh failures may ask for a new sign-in and which must keep the stored grant — and the playback layer added for the car: queue navigation and its boundaries, the media ids the session and the browse tree agree on, the tree the car is handed (which rows are lists, which are playable, what a tap queues, what voice search matches) and the rule that decides which apps may connect at all. They also cover the voice layer and the Ask tab: the sentences shown for each speech-recognition failure, what a spoken request turns into (command words, a named artist, a named app, a feeling, dictation punctuation, and the titles that must survive untouched), how a request is matched against the library, the wording — and the singular case — of what the app says back, and how a YouTube search page is read into rows, including artists that live in the title and pages that are not JSON at all. The map's search rule and the likes consolidation are pinned down the same way. They also cover the mood layer: the on-device reading (negation, intensifiers, emoji, time-of-day tilts, low-confidence fallbacks), the tag vocabulary, the mixer's scoring/variety/deduplication rules, the request a reading builds (the provider named in it, the sentence the model reads first, every target surviving a round trip) and the songs that come back with the answer (each shape a model may use for them, the caps and deduplication they are held to, and the rule that decides which real search result is the song that was named — including the songs nothing matches, which are reported rather than replaced), and the model client — the strict, clamping reply parser across every supported shape (Gemini, Ollama, OpenAI-compatible, bare JSON), Ollama's model list and error sentences, the address normalisers (Ollama's, and the OpenAI-compatible base that keeps a path, appends `/v1`, and picks HTTP only for a host that is plainly local — including the exact base, model alias, and both routes of Arena's preset), an endpoint's model list — the ids a request must use, the names a person sees, and the rows that are not callable — and its error sentences, and the wording of the unreachable-server messages. Arena's own client refusals are pinned down the same way: `free_router_harness_restricted` and `organization_client_restricted` are recognised from the error code rather than the sentence around them, an ordinary rejected key is not mistaken for one, and the advice shown says the key is not the problem and that a request turned away is not usage. They also pin down the two new gates: the destination prompt's endpoint normalisation and the rule that a changed provider or address is a new consent record, and the activity-recognition mapping and fallback choice (fresh recognized activity wins, a stale or contradicted one yields to GPS speed, and no recognized activity is exactly the GPS reading). The managed snapshot store's file-name allowlist is tested the same way, and the consent record has its own unit tests: one approval at a time, matched to the exact provider and endpoint, withdrawn cleanly, and never carried over to a destination the listener has not seen. Two instrumented smoke tests cover what a JVM cannot — the real preferences files, the runtime permission check, and the hand-off to Play services, skipping themselves with a reason on a device or emulator that has none. CI compiles them with `:app:assembleDebugAndroidTest` so they cannot rot, but running them still needs a device. Python `unittest` coverage exercises account/session isolation and deletion, opaque event pagination/idempotency, checkpoint compaction, rate limits including trusted-proxy IP handling, and authorized audio-route validation/stream cleanup, and adds server-side managed backups: live online copies, SHA-256 sidecars, retention, restore with a pre-restore safety snapshot, tamper detection, and refusal to resolve a restore name outside the backup folder. GitHub Actions runs the Android build and uploads a debug APK, plus the server API tests across Python 3.10 and 3.14.

Documentation: [YouTube Data API v3](https://developers.google.com/youtube/v3) · [Google OAuth for Android](https://developers.google.com/identity/sign-in/android/start-integrating) · [Google OAuth for installed apps](https://developers.google.com/identity/protocols/oauth2/native-app) · [Google Cloud Console](https://console.cloud.google.com/) · [Jellyfin API](https://api.jellyfin.org/) · [Jellyfin authentication](https://jellyfin.org/docs/general/administration/configuration/#security) · [Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow) · [SoundCloud API guide](https://developers.soundcloud.com/docs/api/guide) · [SoundCloud app registration](https://developers.soundcloud.com/docs/api/register-app) · [TheAudioDB free music API](https://www.theaudiodb.com/free_music_api) · [Foreground service types](https://developer.android.com/about/versions/14/changes/fgs-types-required) · [microG](https://microg.org/) · [OpenStreetMap](https://www.openstreetmap.org/copyright) · [CARTO map tiles](https://carto.com/developers/maps/)
