# Soundtrail — music for the places you love

An Android app that saves **your own Jellyfin server's** songs — and audio files you attach yourself — as private, location-based pins, with an **in-app map**, a **mood engine that turns how you feel into a mix from your own library**, offline-audio management, local playlists, opt-in place/motion/time automations, **managed on-device backups, JSON export, and server-side managed snapshots**, optional **end-to-end encrypted cloud sync (public server by default, self-hostable)**, and background playback with notification, lockscreen, Bluetooth and **Android Auto** controls.

**One music source, on purpose.** Earlier versions also connected to Spotify, SoundCloud and
YouTube. Those integrations are gone: this app can no longer resolve, stream or hand over their
links, so keeping them would have meant rows that look playable and are not. What plays here is a
song on a Jellyfin server you control, or an audio file you attached. The first launch after the
upgrade strips the dead rows once and says how many went — a song you had attached audio to keeps
that file and loses only the link.

## What connects, and how

| Service | Integration |
| --- | --- |
| Jellyfin (your own server) | Username + password sign-in against **your** server, read-only music-library search, in-app/background playback, and offline download through Jellyfin's authenticated `/Items/{id}/Download` route. The server enforces the signed-in account's access; Soundtrail downloads only items that account is allowed to retrieve, and caps each transfer at 250 MiB. The password is never stored; the access token is encrypted with Android Keystore. Works over HTTPS or plain HTTP on a trusted home network. |
| Song lookup (TheAudioDB) | **Free, no account.** Enter artist + title to get exact names, album, and genre/mood tags. Metadata only, used to prefill a pin or to tag a song the mood engine has nothing on: it never provides playback, offers no link, and stores nothing. |
| Mood mixes | **Free, on-device, no account.** Describe how you feel (or tap a quick pick); a small, explainable engine on the phone reads it into a sound profile and scores the songs in your own pins and playlists against it. Optional extras, both off by default: TheAudioDB genre/mood lookups and a model reading — a local **Ollama** server you run, **any OpenAI-compatible endpoint** (OpenAI, OpenRouter, a model on your own machine, a bridge to a service with no API of its own), **Arena** (one-tap preset; its free-router keys are issued for Arena's own coding harnesses, so this app needs a key from a workspace with API access, and it says so rather than blaming the key), or Google AI Studio with your own key. Your songs, playlists, pins, and places never leave the device. Before the first external request — and again when the provider or endpoint changes — the app names that destination and asks for approval; Cancel keeps the on-device reading. |
| Android Auto | Soundtrail is a media app for the car: Android Auto browses **pins and playlists** and plays the songs inside them, with play/pause, next/previous and a scrubber, through the same player the phone uses. Nothing is uploaded and no account is involved — the car sees the library already on the phone, and only rows that can actually play here are listed. See *Android Auto* below for how to enable it and who is allowed to connect. |
| Your audio files | Download eligible Jellyfin tracks for offline playback, or attach audio you already own or are authorized to use. Files are copied into private app storage; audio is not cloud-synced. |
| Map tiles | The Pins tab's **Map** view draws an OpenStreetMap-based map on-device: raster tiles are fetched from CARTO's public tile server (`basemaps.cartocdn.com`) for the visible area only. Tile requests reveal the visible map area; pin coordinates are rendered locally and are not included in tile requests. Attribution stays on screen. |
| Backup files | **Managed on-device snapshots** are written automatically after a change and the newest 10 are kept in private storage, with restore, delete, and an opt-out in **Connect → Backup & restore**. **Export** still writes a JSON file you choose; **Import** reads one back additively. **Android Auto Backup** may also copy pins, playlists, and automations to your Google account — never the Jellyfin token, the sync session, audio files, or the app's own snapshots. The JSON file goes only where you put it. |
| Optional cloud sync | By default connects to the public Soundtrail sync server; you can instead point it at your own self-hosted Soundtrail API — **HTTPS only, with no cleartext exception**. Pins (including coordinates), playlists, song links, and automations sync two-way, including edits and deletions. Events and compacted checkpoints are encrypted on-device with AES-256-GCM. The recovery key is user-held and is **not** rotated by changing the account password. The Connect tab lists every device signed in to the account, signs one out on its own, changes the password (which signs out every other device), and renews a session before it expires. |
| Location & motion | `LocationManager` on demand, plus an **opt-in** foreground watch service for automations. Motion (driving/running/walking) is inferred **on-device from GPS speed** by default — no advertising ID. The app contains no Google Play services or Google Activity Recognition code, so motion always comes from GPS speed. |
| Time | The watch checks the current time every minute; automations can fire only inside a time window (wraps midnight). The Lists tab shows the live clock. |

## Playlists & automations

- **Playlists** (Lists tab) are named groups stored locally by default. Optional cloud sync encrypts playlist metadata and song links before upload. A song needs a title; a Jellyfin song is optional, so a row can be a name on its own, a name plus a Jellyfin item, or a name plus an audio file you attached.
- **Jellyfin songs** are stored as *item references* (`jellyfin:item:<id>`), never as server URLs: addresses and access tokens do not enter pins, playlists, backups, or cloud-sync records. Playback resolves against the current connection; direct play is tried first, with an automatic MP3 transcode fallback. Download eligible audio through Jellyfin's authenticated endpoint; its account permissions remain authoritative. Offline audio stays on-device and is never backed up or cloud-synced.
- **Backups, in three managed layers.** **On-device snapshots** are written automatically a few seconds after a change to pins, playlists, or automations (debounced, so a burst of edits is one file); the newest 10 are kept in private app storage and can be restored, deleted, or switched off from **Connect → Backup & restore**. They use the same JSON document as the manual export, so audio files and provider logins are not in them. **Android Auto Backup** may additionally copy pins, playlists, and automations to your Google account under the system's own settings; the rules explicitly exclude the Keystore secret store — which holds the Jellyfin token, the mood API keys, the sync session and the recovery key — along with settings, audio, and the managed snapshots. **A JSON export/import** remains the portable file you choose. On the server side, the self-hosted API keeps **verified database snapshots** automatically (see below). All three are separate from cloud sync: backups are readable JSON or encrypted database copies; cloud sync sends only encrypted event envelopes. The Jellyfin login and local audio files stay on-device; after a restore you may have to sign in to your server again or download local audio again.
- **Get a song:** the ⬇ action is Jellyfin's authenticated offline download, and only appears for a song that really is a Jellyfin item. A row with nothing to fetch says so instead of offering a button that would hand a dead link to another app.
- **Import audio:** the ♪ button attaches an audio file from your device to a playlist song. The file is copied into private storage and shows as *On device*; tapping play opens it in your music player, fully offline. Removing the song or playlist deletes the copy.
- **Offline storage management:** Lists shows total local audio storage; **Manage** opens a largest-first file list with per-file sizes and playlist details. Remove individual copies or clear all offline files; playlist entries are kept. Removing a song or playlist still deletes its attached file.
- **Automations** point a playlist at conditions: *near a saved spot (20–2000 m radius)*, *while walking / running / driving*, and/or *between two times of day*. Set any combination; all set conditions must hold at once.
- **The watch**: an opt-in foreground service (persistent notification — required by Android and kept honest by it) checks location, motion, and time. When a rule matches it posts a notification with **Play playlist** and **Open Soundtrail** actions. Nothing plays without a tap, and a rule re-arms after a 15-minute cooldown.
- **Motion inference**: GPS speed bands — under 0.5 m/s stationary, to 2.6 walking, to 7.5 running (covers cycling), above that driving. It is an estimate; tunnels and poor GPS delay or miss triggers.
- **Battery**: continuous GPS watching uses noticeably more battery. The watch runs only while its toggle is on and its notification is visible.
- **Privacy**: by default, pins, coordinates, playlists, automations, and imported audio stay on the device. If you opt in to cloud sync, pins (including coordinates), playlist metadata, song links, and automation rules are encrypted before leaving the phone; the server stores ciphertext and account/session metadata for sync. Downloaded audio and credentials are never synced. Map tiles reveal only the visible area, not your saved pin coordinates. A Jellyfin search sends your phrase to that server and nowhere else.

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

The car never sees more than the app's own library: no Jellyfin token, no server address, no
audio file, and no screen of the app itself.

## Ask: talk to it, hear the answer

The **Ask** tab (the microphone in the bottom bar) takes a request by voice or keyboard and answers
it with songs and a sentence:

- **Say it.** Tap the microphone, say "play Sunsets by The xx", "something calm for the drive", or
  just "Midnight City". The words appear as they are recognised, so a misheard request can be seen
  and stopped instead of discovered afterwards. The microphone is asked for at that moment, never
  at launch, and the recognizer is the phone's own — Google's, or an offline engine.
- **It reads the request before searching.** Command words come off the front ("hey soundtrail,
  could you play …"), an "on Jellyfin" tail says where to look without becoming part of the
  song, and "by <artist>" keeps the artist for a search that would otherwise find covers. Only one
  command verb is ever taken off, and only when a title of at least two words follows it — so
  "Play That Funky Music", "Play Search and Destroy" and a song called "Play" all survive, and the
  words as you said them are kept for a second look if the first search finds nothing.
- **Your library answers first.** Pins and playlist songs are matched on the device — no network,
  no account — and an offline copy plays from the phone. Every word you said has to match
  something in the song, so "the xx sunsets" cannot answer with every song that has a "the" in it.
- **Then your Jellyfin server.** With a server connected on the Connect tab, the request is also
  searched against the music library that account can read, and what it finds plays in Soundtrail
  like any other song — in the app, in the background, or in the car. Only what your own server
  allows comes back, so the same words can answer differently on two servers. With nothing
  connected the reply says so and names the Connect tab, rather than failing quietly.
- **It can answer out loud.** Answers are spoken through the phone's text-to-speech engine; the
  switch on the card turns that off. Describing a feeling ("something chill", "put me in the mood
  for a party") hands the request to the mood engine instead, which builds a mix on the Mood tab,
  and the answer says what it read and how many songs it found.
- **The songs it finds are queued**, so the notification, the lockscreen, Android Auto and the Ask
  tab all move through the same list, and the reply says how many are behind the one playing.

Nothing you say is stored by Soundtrail: the words are used for the request in front of you. A
request answered from your own library sends nothing at all; one that reaches your Jellyfin server
sends it the search phrase, and no third party sees the request either way.

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
- **Setting up Ollama.** Enter the server's address on the Mood tab — `nas.local:11434`, `https://ollama.example.com`, or a pasted `…/api/chat` endpoint all work; **Find models** lists what the server has pulled and lets you tap one. **The address has to be HTTPS**, which is the one place this app asks for more than Ollama itself does: Ollama serves plain HTTP by default, so put a TLS reverse proxy or a tunnel in front of it — Caddy, nginx and `tailscale serve` each do it in a few lines — and point the app at that. A bare host is given `https://`; one typed as `http://` is kept as typed and refused before a request is built, with a sentence naming the host, rather than quietly rewritten into an address nobody entered. Ollama must also be reachable from the phone: start it with `OLLAMA_HOST=0.0.0.0:11434`, and use the machine's address on your network, because on a phone `localhost` means the phone itself. If the proxy wants a token, there is an optional field for it (Keystore-encrypted, like every other secret here). Local models can take up to a minute to answer, so the app waits three before falling back. **Test** reports exactly what happened — reached, how many models, whether the selected one exists.
- **Setting up an OpenAI-compatible endpoint.** Paste the address on the Mood tab — `https://api.openai.com/v1`, `openrouter.ai/api/v1`, `192.168.0.20:8000`, or a whole `…/v1/chat/completions` copied out of a README. The address is accepted however you found it: an endpoint path is trimmed back to its base, and a bare host gets the `/v1` every one of these APIs lives under plus `https://` in front of it — this provider sends a key to whatever is on the other end, and a private address is not a promise about who is listening on it. One typed as `http://` is kept as typed and refused before a request is built, so a self-hosted bridge needs a TLS reverse proxy or a tunnel in front of it. **Find models** lists what the endpoint advertises and lets you tap one; if it publishes no list at all, type the model name and the app uses it anyway. The key is optional — a server on your own machine often wants none, and when the field is empty no `Authorization` header is sent. **Test** reports which of the three happened: a model list, a real reading, or exactly what the endpoint said went wrong.
- **Arena.** Arena publishes an API and it speaks this protocol, so it needs no provider of its own. Tap **Use Arena (preview)** on the OpenAI-compatible card and the address and router alias are filled in from Arena's own portal (`https://api.preview.arena.ai/v1` and `coding-router-preview`); create a key on Arena's **Keys** page, paste it into the API key field, save, and press **Test**. **Find models** then lists exactly what that key is enabled for, read the same way Arena's own `setup.sh` reads it: models come only from authenticated discovery, and rows that are not callable are skipped (a `routing` row, and Arena's `exo` entry). A key may have any one of Arena's router aliases rather than the one the preset fills in, so if discovery shows another — `claude-coding-router-preview`, `claude-max-v0`, `max-v0` — the app selects the one the key actually has, and says so instead of failing the next request. A key that has **no** router alias at all, only named models (`claude-fable-5-1`, `gpt-6-sol`, `gemini-3.8-flash`, `kimi-k3`, `grok-4.7`, …), is not a dead end either: the app then selects the first model the key is enabled for, which is exactly the `models[0]` fallback `setup.sh` makes. Either way the name comes only from authenticated discovery — never from a list baked into the app — and either way it can be changed by tapping any model in the picker. The picker shows each model by the name the endpoint itself gives it, so Arena's entries read the way Arena writes them — the router appears as `(Arena) Coding Router`, not as the id underneath it — and the Model field always shows the exact id that will be sent, because that is what answers. This is the *preview* gateway: if Arena moves it, the three values live in one place in the source (`MoodAiUrls.ARENA_*`) and nothing else needs to change. **A free-router key is issued for Arena's own harnesses only** — interactive Claude Code, the Code tab in Claude Desktop, and OpenCode — so a request from anything else, this card included, is answered `403 free_router_harness_restricted` (and Cowork, print mode, and the Agent SDK get `organization_client_restricted` where a workspace blocks them); a key from a workspace with API access reads here like any other endpoint's. The app names that refusal for what it is — read from the error code, never from the sentence around it, so a reworded message on Arena's side cannot turn it back into "wrong key" — and says that the key is not the problem, because re-creating the same free-router key would only repeat it. **Test** is honest on Arena for the same reason: a model list is not an answer, and Arena's usage page records prompts, so pressing Test there sends one short reading — exactly the request the Mood tab makes — and reports that this one call is what appears in Arena's usage. A key that has only ever listed models leaves that page empty, which is a property of the gateway, not a fault in the key; the note under the field also says when the reading switch itself is still off, since that keeps a working key out of the loop too. Arena's gateway also speaks the Anthropic Messages API and the OpenAI Responses API, which this app does not use; only its OpenAI-compatible `/v1` route is called here. The community projects that wrap Arena's *web* product with a signed-in browser cookie are a different thing again — that is against its terms and breaks whenever the site changes, and Soundtrail has nothing to do with it.
- **API keys** (Gemini, an OpenAI-compatible endpoint, or an Ollama proxy token) are encrypted with Android Keystore: never synced, never backed up, and excluded from exports.
- **Optional tag lookups.** Turn on *Look up genre & mood tags* and Soundtrail can ask TheAudioDB's free metadata API about the specific songs nothing is known about (artist + title only, capped per run, and it remembers what it learned). This is what lets a track whose title says nothing — `Track 1` — still be matched by its genre.
- **Save it.** *Save this mix as a playlist* writes a playlist named `Mood · <mood>` and replaces the songs the mix generated on a re-save, so today's mood does not pile up forever. Hand-added songs in that playlist are kept.
- **Find music in a place you name.** The Mood tab has a **Find music on** card, and it says exactly what it does: when a reading comes from one of the model providers, the model is asked to **find music on <target> that fits** your words. The request is built from the pill you chose — *your own library* or *your Jellyfin server* — and the sentence that will be sent is shown on the card, word for word, before anything is. Picking Jellyfin with no server connected says so at the tap, not after a reading has been spent finding out. The model answers with songs, each with a line about why it fits. **Every song is then looked up for real** — matched against your own pins and playlists, or searched on the server you connected — and only what that lookup returns becomes a row: a tap plays it through the same in-app player as everything else. A song the model made up matches nothing and is left out, and the note under the rows says how many of the named songs were found and how many could not be matched, because a model's suggestion is a suggestion, not a source. With no model in the loop — an on-device reading, a quick pick, or a model that failed — the chosen target is searched with the reading's own phrases instead, so the card still does something useful. *The app is doing the searching: it never plays a song because a model named it, and every row it offers is music you already have.
## Offline audio and source permissions

Jellyfin offline downloads remain authenticated against your own server and subject to its account permissions, and are capped at 250 MiB per track. There is no second source to fetch from: the streaming-service hand-offs earlier versions offered are gone, so nothing in this app extracts, rips or proxies audio out of a third-party catalogue. Soundtrail does not bypass access controls, and it retrieves only what your own server lets your own account retrieve.

Audio you attach yourself is a different thing and stays yours: the ♪ action copies a file already on the phone into private app storage, where it plays offline with no server involved.

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

Each pin, playlist, playlist song, and automation is encrypted client-side with AES-256-GCM. The server stores opaque ciphertext envelopes and sequence metadata; it does not receive the recovery key or plaintext sync data. Changes flow both ways, including per-item additions, edits, and deletions. Concurrent versions merge deterministically using a last-write-wins clock and device-id tie-breaker. Once history grows, the client uploads an encrypted checkpoint and the server prunes covered event rows. The Jellyfin token, the mood API keys and downloaded audio files are not synced.

The server sees account email, authentication metadata, request timing, event IDs, and ciphertext sizes. Run it only behind HTTPS, protect and back up its SQLite volume, and keep its host patched. The server includes per-process request rate limits, a per-account encrypted-sync quota, and authenticated account deletion, but no email verification or password-reset service; use reverse-proxy limits as well, especially with multiple instances. See [`server/README.md`](server/README.md).

Automatic sync is **off by default**. Enable **Sync automatically when Soundtrail opens** in Connect to sync at app launch; it requires a signed-in account and this device's saved recovery key and does not run while the app is closed. Connect also shows pending local changes; after a sync failure, **Retry sync** runs the same merge again. In the same section, **Delete account and remote sync data** removes the server account/history only; local pins, playlists, automations, and offline audio remain on the device.

### Your account, on the Connect tab

Sync is the one feature that carries a password, a bearer token and your encrypted library, so the
account gets as much attention as the data does:

- **Signed-in devices** lists every session on the account — the name it signed in from, when it was
  created and when it expires, newest first — with **This device** marked from the session id stored
  on this phone. Each other row has its own **Sign out**, which revokes that session on the server;
  this device signs out through its own button, so it cannot revoke the session it is standing on by
  accident. A session the server has already revoked disappears from the list instead of failing
  every time you look at it.
- **Change password** asks for the current password and the new one, requires 10–128 characters and a
  password that is genuinely different, and says what will happen before it happens: **all other
  devices are signed out**, and **the recovery key is not rotated** — it still decrypts everything
  already uploaded, so changing a password is not a way to lose your own history. The returned token
  and session id are stored *before* the success message is shown, which is what makes it impossible
  for a crash mid-change to leave this phone holding a token the server has already revoked. A wrong
  current password keeps the form open and says "That is not your current password"; a reply that is
  merely ambiguous claims neither, and the app then asks the server which session it is actually
  holding and shows the sign-in form with your email already in it.
- **Renewal** happens by itself. A session lasts 30 days; the app renews it when fewer than 7 days
  are left and at most once a day, and writes the new token and session id together in a single
  commit, so no crash can leave the two disagreeing. A `401` on renewal goes down the ordinary
  expired-session path and asks you to sign in again — it is never retried with the old token.
- **A server that has fallen behind** is a warning, not a failure. If the server reports a lower
  sequence than this device has already pushed — a restored database, a rolled-back volume — Connect
  says what it saw and offers **Re-push my library to this server**, which queues every stored record
  again. Nothing is re-pushed without you asking, and nothing is overwritten silently.
- **Failures are named.** A rate limit says how long to wait — `Too many attempts. Try again in 30
  seconds.` — taken from the server's own `Retry-After` and capped at a minute. A full quota says
  `This account has reached its encrypted sync storage limit.` (`507`), and the app compacts the
  history and retries once rather than giving up. A stalled or briefly unavailable server (`408`,
  `502`, `503`, `504`) is retried at most twice with a growing delay — 1 s, 2 s, 4 s, capped at 8 s,
  plus jitter so several devices do not retry in step. A redirect is reported as an error and never
  followed, because following one would move the bearer token to a host nobody chose.
- **Creating an account and signing in are never auto-retried.** A retry there is how a duplicate
  account or a rate-limited password attempt happens. A registration that does not clearly return an
  account id *and* a token stores nothing at all and says the account may or may not exist, offering
  to sign in or to use another email address — it never says "wrong password", because that is a
  claim about a credential when the truth is that no answer arrived.

The recovery key is treated as a secret on the phone too: the screen that shows it is excluded from
screenshots and from the task switcher, copying it flags the clipboard entry sensitive (on Android
13+, so it offers no preview and is cleared by the system), and revealing it again asks for your
device unlock. It is shown once at creation, as before.

### Deploy your own server (optional)

1. Run the sync server natively (Python 3.10+, no Docker, any OS or CPU) behind a TLS reverse proxy for a domain you control. See [`server/README.md`](server/README.md) and [`server/Caddyfile.example`](server/Caddyfile.example).
2. From the repository, run `cd server && python3 run.py` (`py -3 run.py` on Windows). The service binds to loopback by default for the proxy; do not expose port 8080 directly.
3. The server manages its own backups: it writes an integrity-checked SQLite snapshot at startup and then every 24 hours, keeping the newest 7 under `server/data/backups`. `SOUNDTRAIL_BACKUP_INTERVAL_SECONDS` changes or disables that (`0`), and `SOUNDTRAIL_BACKUP_KEEP` changes retention. `python3 run.py backup`, `list`, and `verify` work without stopping the service; `python3 run.py restore <name> --yes` replaces the database and must be run with the server stopped. Copy the backups off-host — they contain account metadata and sync ciphertext, not plaintext library data. See [`server/README.md`](server/README.md).
4. In Soundtrail, open **Connect → Cloud sync**, replace the default URL with your public `https://` URL, and create an account. Save the recovery key, then tap **Sync now**. On another device, enter your server URL, sign in to the same account, and enter the saved key.

If you don't want to self-host, leave the server URL set to the default public server and create your account there instead.

Backups are separate from cloud sync: managed snapshots and the JSON export are readable files on the device you control, Auto Backup is Android's copy to your Google account, and the server's snapshots are encrypted-at-rest only because the sync payloads already are. None of them replaces the recovery key: cloud sync's ciphertext still needs that key on a new device.



**TheAudioDB free key:** Soundtrail calls TheAudioDB's v1 API with their documented shared free key (`123`, formerly `2`). The shared key can be rerouted or rate-limited at any time — an inactive route returns HTTP 404 with `{"Message":"Not found"}`. If it stops working, check [TheAudioDB's API page](https://www.theaudiodb.com/free_music_api); premium keys use the same URL format and only require changing `AUDIO_DB_FREE_KEY`.

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

3. To use **Jellyfin** — the only music source — install (or point at) your own Jellyfin server, open the **Connect** tab, and enter:

   - the **server address**, for example `http://192.168.1.20:8096` — plain HTTP is fine on your own network, HTTPS works too, and a sub-path behind a reverse proxy (`https://music.example.com/jellyfin`) is accepted;
   - a **username** and **password**. The password is sent once to that server and never stored. `GET /System/Info/Public` is checked first, so a wrong address or an unrelated web server is reported as such instead of looking like a bad password.

   Soundtrail searches that library from the **Search** tab, streams what you pin, and can save an eligible track for offline playback using Jellyfin's authenticated download endpoint. Soundtrail never writes to, renames, or deletes items in the server library. Download permission is decided by Jellyfin; only save media you have rights to access and keep.

4. Everything else is optional and off until you enter it: **cloud sync** (an email and a password, against the public server or one you host), the **mood model providers** (a local Ollama address, any OpenAI-compatible endpoint, or a Google AI Studio key), and **TheAudioDB** song lookups, which need no account at all. No music app has to be installed on the phone, and audio you already own can be attached with the ♪ action instead.

No integration needs a client ID, a registered redirect URI or an OAuth app of its own any more. The `app.soundtrail://` deep links those browser sign-ins used are gone from the manifest along with them, so nothing in the app has to be re-registered with a third party before it works.

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

**The uninstall deletes this device's app data.** Local pins, playlists, automations, downloaded/imported audio, the app's managed snapshots, sync session, and the device's encrypted copy of the recovery key disappear with the package. If Android Auto Backup is enabled on the phone, the system may restore pins, playlists, and automations on a reinstall — but it is best-effort and copies nothing else, and the activity-recognition and mood/mood-consent opt-ins deliberately ask again. Synced ciphertext remains on your self-hosted server, but you must have separately saved the recovery key to decrypt it after reinstalling. The stored Jellyfin login goes too; sign in to your server again afterwards. Before uninstalling, tap **Export backup** for a portable JSON file and make sure your cloud recovery key is saved somewhere private.

It is a one-off. Every APK built from this repository — locally or by CI — is signed with `keystore/soundtrail-debug.p12`, so from the next release on, updates install over each other normally. That key is deliberately public (it is the conventional `androiddebugkey`, and anyone can read it in this repository), which is fine for sideloaded debug builds and unusable for a Play release — this repository intentionally configures no release signing.

## Releases

The first release published from this repository is **v1.0.9** (`versionCode` 10). Releases published before the repository move (v1.0.1–v1.0.8) remain at the [Geotagged-Music-App releases page](https://github.com/egrie-tech/Geotagged-Music-App/releases). The `Release on merge` workflow (`.github/workflows/release.yml`) uses the configured version for its first untagged release; after that tag exists, each merged pull request or manual run increments the patch version and `versionCode`:

1. selects the current version for its first release, then adds 1 to `versionCode` and `0.0.1` to `versionName` on later releases (via `.github/scripts/bump_version.py`, which skips patches whose `vX.Y.Z` tags already exist),
2. runs the server API tests and Android unit tests, then builds `:app:assembleDebug` and `:app:assembleRelease` **before** pushing anything, so a failed check leaves `main` untouched,
3. runs the F-Droid gates against the version it is about to tag: it repoints the build recipe at that version (`tools/fdroid_recipe_sync.py`), writes the changelog for the new `versionCode` if the merge did not ship one, checks every requirement in `tools/fdroid_check.py`, then runs F-Droid's own `fdroid lint` and source scanner through `tools/fdroid_lint.py`, and asserts the release APK is unsigned — see *F-Droid* below,
4. commits a version bump when needed, tags `vX.Y.Z`, and pushes the commit (if any) and tag,
5. creates the GitHub Release `Soundtrail X.Y.Z` with generated notes (the merged pull requests) and the debug APK attached as `soundtrail-X.Y.Z-debug.apk`.

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

## F-Droid

Soundtrail contains no proprietary libraries, so F-Droid can build and publish it. The recipe F-Droid needs is `fdroid/tech.egrie.soundtrail.yml`, ready to copy unchanged into [fdroiddata](https://gitlab.com/fdroid/fdroiddata)'s `metadata/` folder; `fdroid/README.md` has the submission steps, what each requirement is answered by, and the open questions (screenshots, the canonical source URL, reproducible builds).

Two checks keep the repository in that state:

- **`tools/fdroid_check.py`** encodes the requirements in this repository's own code, with nothing but the standard library: store metadata inside fdroidserver's character limits (name 50, summary 80, description 4000, whatsNew 500), a changelog for the `versionCode` about to be tagged, a recipe that agrees with `app/build.gradle.kts`, no non-free dependency, tracker or prebuilt binary, no Git LFS, an executable Gradle wrapper, and a release build type left unsigned so F-Droid signs what it publishes. Over-length store text is the one F-Droid will never report: fdroidserver truncates it silently, so the listing would simply end mid-sentence.
- **`tools/fdroid_recipe_sync.py`** repoints the recipe at the version being released. No pull request can carry the recipe for a version the release workflow has not chosen yet, so the workflow runs it between the version bump and the gate; the output stays in `fdroid rewritemeta` format, which the lint check below verifies.
- **`tools/fdroid_lint.py`** runs F-Droid's own tools — `fdroid lint -f` on the recipe and `fdroidserver`'s source scanner on the tree — in a throwaway workspace that looks like a checkout of fdroiddata, with `fdroid/lint-config/` standing in for its configuration. Install fdroidserver into a virtualenv first; the script prints the commands when it is missing.

The `fdroid` job in the `Android build` workflow runs both, plus the checker's own tests, on every push and pull request. The release workflow runs them again after selecting the version and before tagging, writes the changelog for a new `versionCode` when the merge did not ship one, and asserts the release APK is unsigned — so nothing that would break an F-Droid build can be published. Both report clean at the moment:

```
$ .venv-fdroid/bin/python tools/fdroid_lint.py
== fdroid lint -f tech.egrie.soundtrail ==
clean: no lint findings, and the recipe is in `fdroid rewritemeta` format

== fdroidserver.scanner.scan_source ==
clean: no non-free libraries, trackers, unknown Maven repos or prebuilt binaries
```

The recipe is in `fdroid rewritemeta` format on purpose: fdroiddata's tooling rewrites metadata into exactly that shape, so a submitted recipe that already matches produces no diff for a reviewer to undo.

What F-Droid publishes is not the APK attached to a GitHub Release. F-Droid checks out the tag, runs `gradlew assembleRelease`, and signs the resulting `app-release-unsigned.apk` with its own key, so the shared debug keystore never touches an F-Droid build.

## Permissions, and why

| Permission | Used for |
| --- | --- |
| Internet | Your own Jellyfin server, the optional HTTPS sync server, TheAudioDB song lookups, an optional mood-model endpoint you enter, and map tiles |
| Coarse/Fine location | Pinning, sorting pins, and the opt-in automation watch |
| Foreground service (location type) | The watch's persistent, visible automation service — opt-in only |
| Foreground service (media playback type) | Keeping a Jellyfin stream alive in the background under its playback notification |
| Microphone | The Ask tab's voice input, requested when the button is pressed and only then. The audio goes to the phone's own speech recognizer; Soundtrail never records or keeps it |
| Post notifications | The watch status, automation triggers, and playback controls (Android 13+) |
| Activity recognition | Not included. Motion for automations comes from GPS speed only, so the app contains no Google Play services or Activity Recognition code and makes no Play services call. |

## Try it

- **Explore:** tap **Drop a pin**. Enter a song title, and a song from your Jellyfin server if you have one. Use **Locate** or enter coordinates manually; name the place and save. On the Pins tab, switch **List ↔ Map**: the map shows every pin — tap one for its card, pinch/double-tap to zoom, drag to pan, and **long-press anywhere to drop a new pin** at that exact spot. Type in **Find a soundspot** and the map goes to the song you name with its card open, **Me** centres the map on your fix, and **Pin** drops a soundspot where you are looking rather than under a long press.
- **Ask:** open the **Ask** tab, tap the microphone and say what you want to hear — or type it. Answers are spoken back unless you switch that off. A song from your Jellyfin server plays right there in the app; tapping a row pins the song instead.
- **Search:** connect your Jellyfin server on the **Connect** tab, then search its library from the **Search** tab — what comes back is only what that server lets your account read, and every result plays here. The **free TheAudioDB lookup** (artist + title, no account) is on the same tab and fills in a song's exact names, album and genre/mood tags.
- **Mood:** open the **Mood** tab, describe how you feel in a few words (or tap a quick pick and an energy level), and tap **Build my mood mix**. Soundtrail reads it on the device and ranks the songs in your pins and playlists against the mood; each row explains itself, and the mix plays in order. Save it as a playlist, or ask a model to find music that fits on the **Find music on** card. The Explore tab's *What's your mood?* card is one tap in.
- **Share:** Soundtrail still appears in the system share sheet for text, so a `jellyfin:item:<id>` reference shared to it opens a prefilled pin editor. Anything it cannot play is refused with a sentence that says so, rather than saved as a row that will never start.
- **Jellyfin:** connect your server on the Connect tab, search it, and add its songs to a pin or playlist. Stream in-app or in the background with notification/lockscreen controls. In a playlist, tap the download icon to save an eligible track privately for offline playback; Jellyfin decides whether your account may download it.
- **In the car:** install the app, allow it in Android Auto (developer mode + unknown sources for a sideloaded build), and pick Soundtrail from the media launcher. Your pins and playlists are there; a playlist song plays through the rest of its list.
- **Backup and sync:** on the Connect tab, leave **Managed on this device** on and Soundtrail keeps the newest 10 rolling snapshots by itself; **Back up now**, **Restore…**, or **Delete…** manage them, and **Export backup** still writes a JSON file you choose. Android Auto Backup may also copy pins, playlists, and automations to your Google account when the phone's system settings allow it. For other devices, use end-to-end encrypted cloud sync from **Connect → Cloud sync** — either the public default server or one you deploy yourself. Save the recovery key before using another device.
- **Lists:** create a playlist and add songs. Jellyfin items can be downloaded offline with the download action; use **♪** to attach an audio file you already own. Turn on the watch and create an automation — for example, *"Morning commute" → playlist "Drive" · while driving · 07:00–09:00* — then tap the notification when it fires.
- Supported song links: exactly one shape, `jellyfin:item:<id>`, where the id is 16–64 characters of `[A-Za-z0-9-]`. It carries an item id and nothing else — no server address, no user, no token — so pins, playlists, backups and sync records never contain a way to reach your server. Every other link shape is rejected, including the streaming-service URLs earlier versions accepted.

## Privacy and limitations

- By default, pins, playlists, and automations (including coordinates and song links) are saved only in private app storage. Optional cloud sync sends their client-encrypted event envelopes to the self-hosted server you choose; for sync it sees account/session metadata and ciphertext sizes, but not item contents or the recovery key. The Jellyfin token and downloaded audio are never synced.
- Location is requested on demand for pins. The automation watch is a separate, explicit opt-in: a foreground service with a persistent notification that Android shows at all times while it runs. Turn the toggle off and every watch process stops.
- **Every secret is in Android Keystore AES-GCM and nowhere else**: the Jellyfin access token, the mood providers' API keys and an Ollama proxy token, the sync account token and session id, and this device's encrypted copy of the recovery key. The Jellyfin password is sent once to your own server and never stored. There is no disk-only fallback and no plaintext mirror, so the ciphertext could not be read on another device even if it were copied.
- **Those secrets are excluded from every backup path by rule, not by luck.** Both backup XML files are allowlists — they name what *may* leave the phone and nothing else — and a JVM test fails the build if any file whose name contains `token`, `secret`, `password` or `recovery` is ever included. They are equally absent from the managed on-device snapshots and from JSON exports. **Disconnect** and **Delete account** both remove the sync token *and* the recovery key, so neither survives a sign-out.
- TheAudioDB lookups send the artist and title you type and store nothing. It is a metadata source only: it offers no link and no playback, so nothing it returns can become a playable row by itself.
- The mood engine runs on the device: your description, your library, and the mix are never uploaded. If you switch a model on and approve that exact provider and endpoint, the mood description (plus the energy hint, the hour, and whether you are moving) goes to the address you chose — your own Ollama server, an OpenAI-compatible endpoint you typed in, or Google AI Studio — and nothing else does: song titles, artists, playlists, pins, and places never leave the device, and switching back to **On-device** returns to the offline reading. Secrets (a Gemini key, an endpoint key, or an Ollama proxy token) are Keystore-encrypted, never synced, excluded from exports, and removed with **Remove**. Tag lookups are the only path that sends artist and title (to TheAudioDB), and only for the specific songs a mix is unsure about, and only when you have switched them on.
- **Jellyfin streaming playback can continue in the background.** Streams run under a `mediaPlayback` foreground service with a MediaStyle notification and lockscreen/Bluetooth controls. Streaming itself is not cached; a separate explicit playlist download saves eligible audio privately, capped at 250 MiB per item and subject to Jellyfin's access rules. The access token travels in a request header, not in a URL. The same background service, notification and media session also carry the audio files you attached, so lockscreen and car controls always act on one stream.
- **A spoken request leaves the device as a search phrase, and nothing else.** The microphone is the phone's own speech recognizer, so the audio is handled by whichever engine the phone has (usually Google's), not by Soundtrail; the app keeps the words only for the request in front of you. A request answered from your own library sends nothing at all; one that reaches your Jellyfin server sends it that phrase — the same call the Search tab makes — and no third party sees either. Speaking is the phone's text-to-speech engine and needs no network of its own.
- **The car sees the library, not the logins.** Android Auto browses pins, playlists and their songs through an exported media browser service; the connection is refused to callers that are not the car hosts, the system's media surfaces, or apps already trusted with media control (`AutoClientPolicy`). The Jellyfin token, the server address and downloaded audio are never reachable through it, and playback still resolves through the app's own connections.
- **HTTPS is required for every address you type, with one deliberate exception.** `network_security_config.xml` has to allow cleartext for any host, because the platform cannot know your Jellyfin server's address in advance and a home server usually speaks plain `http://`. That permission is not the policy: `integrations/EndpointPolicy.kt` enforces the real rule in code, at the single funnel every request passes through, so a new integration cannot forget it. Plain HTTP is accepted for a Jellyfin server and for nothing else — not a model server, not an OpenAI-compatible endpoint, not the sync server, and **not** because an address happens to be private: `192.168.0.20` can be a flatmate's laptop, a printer with a web server, or a network this phone joined somewhere else, and those are precisely the requests that carry an API key or a bearer token. The exception has to be asked for at the call site, in writing (`TransportRule.JELLYFIN`), so a new integration defaults to HTTPS-only and cannot inherit it by accident. **The sync server has no cleartext exception at all**, and it never accepts credentials embedded in its URL (`https://user:pass@host` is refused rather than silently used), a query, a fragment or a `..` segment. Certificates are validated against the system trust store only: user-installed CAs are never trusted, there is no certificate pinning and no custom `TrustManager`.
- Jellyfin downloads use the server's official authenticated endpoint and remain subject to your rights and the server's permissions. Nothing in the app extracts, rips or proxies audio from a third-party catalogue.
- Motion detection is a GPS-speed estimate, not a sensor fusion product: expect misses at poor accuracy, and driving/running thresholds are approximate. Cycling is read as running. GPS speed is the only motion source, so no Google activity data is used and no Play services call is made. Automations use cooldown-based re-arming (15 minutes), not edge triggering, so a rule can re-fire after its cooldown if conditions still hold.
- **Backups have a deliberate boundary.** Anything that leaves the phone in an Android Auto Backup is governed by the listener's Google account settings; the app's rules include only pins, playlists, and automations. Managed on-device snapshots stay in private storage and are deleted with an uninstall. The self-hosted server's snapshots include account emails, password and session hashes, event metadata, and ciphertext — never a decryption key or plaintext library data — so the backup folder must be protected and copied off-host.
- **The mood destination prompt is a real gate, not a notice.** No external mood request — a reading or the **Test** probe — is built until the exact provider and normalized endpoint have been approved on this device. Changing the provider, the Ollama address, or the OpenAI-compatible base invalidates that approval, and the Mood tab can withdraw it outright; there is no setting that silently skips the prompt.
- Without a location fix, use manual coordinates to create pins. **View place** still opens the pin in an external maps app or, with none installed, OpenStreetMap in a browser; the in-app map draws tiles itself and keeps them in memory only, so an area you have viewed works offline until the cache turns over, and a cold offline start shows markers over a blank canvas.

## Tests

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest     # needs a device or emulator
python3 -m unittest discover -s server -v
```

Unit tests cover the song-link parser, backup, map, playlist, and automation behavior plus Jellyfin download validation, recovery-key encoding, AES-GCM event encryption, tamper rejection, deterministic tombstone merges, and sync-payload handling (including keeping local audio filenames out of ciphertext). They also pin down the playback layer added for the car: queue navigation and its boundaries, the media ids the session and the browse tree agree on, the tree the car is handed (which rows are lists, which are playable, what a tap queues, what voice search matches) and the rule that decides which apps may connect at all. They also cover the voice layer and the Ask tab: the sentences shown for each speech-recognition failure, what a spoken request turns into (command words, a named artist, a named app, a feeling, dictation punctuation, and the titles that must survive untouched), how a request is matched against the library, the wording — and the singular case — of what the app says back, The map's search rule is pinned down the same way. They also cover the mood layer: the on-device reading (negation, intensifiers, emoji, time-of-day tilts, low-confidence fallbacks), the tag vocabulary, the mixer's scoring/variety/deduplication rules, the request a reading builds (the provider named in it, the sentence the model reads first, every target surviving a round trip) and the songs that come back with the answer (each shape a model may use for them, the caps and deduplication they are held to, and the rule that decides which real search result is the song that was named — including the songs nothing matches, which are reported rather than replaced), and the model client — the strict, clamping reply parser across every supported shape (Gemini, Ollama, OpenAI-compatible, bare JSON), Ollama's model list and error sentences, the address normalisers (Ollama's, and the OpenAI-compatible base that keeps a path, appends `/v1`, and picks HTTP only for a host that is plainly local — including the exact base, model alias, and both routes of Arena's preset), an endpoint's model list — the ids a request must use, the names a person sees, and the rows that are not callable — and its error sentences, and the wording of the unreachable-server messages. Arena's own client refusals are pinned down the same way: `free_router_harness_restricted` and `organization_client_restricted` are recognised from the error code rather than the sentence around them, an ordinary rejected key is not mistaken for one, and the advice shown says the key is not the problem and that a request turned away is not usage. They also pin down the two new gates: the destination prompt's endpoint normalisation and the rule that a changed provider or address is a new consent record, and the activity-recognition mapping and fallback choice (fresh recognized activity wins, a stale or contradicted one yields to GPS speed, and no recognized activity is exactly the GPS reading). The managed snapshot store's file-name allowlist is tested the same way, and the consent record has its own unit tests: one approval at a time, matched to the exact provider and endpoint, withdrawn cleanly, and never carried over to a destination the listener has not seen. One instrumented class covers what a JVM cannot — the mood consent record in the real preferences files, including that it is not stored beside the mood settings and that another endpoint is not covered by an approval for one. CI compiles it with `:app:assembleDebugAndroidTest` so it cannot rot, but running it still needs a device. The security rules above each have a test that fails if they are quietly undone: the endpoint policy (HTTPS accepted, plain HTTP and a `https://user:pass@host` authority refused, a `..` segment refused, `http://192.168.1.20:8096` accepted for Jellyfin, redirects never followed), the retry policy (which statuses are retried, how many times, with what delay, and that account creation and sign-in are never among them), the sentences each status produces, the envelope limits asserted before upload (100 events, a 12-byte nonce, 1 MiB per event, 8 MiB per checkpoint), the backup allowlists, the session rules (renewal due once a day and whenever expiry is inside seven days, the password length rule and that the new password must differ, a lower sequence being a warning rather than a failure, the labels the Connect tab shows for each device), that nothing is written before a sign-up response has been validated, and the source-inspection tests that read the shipped code itself — one failing if any main-source file ever logs a credential or an `authorization` header, one failing if the Keystore-backed secret store grows a disk fallback, and one failing if the recovery-key gate is weakened: a screenshot allowed on that screen, no `ClipDescription.EXTRA_IS_SENSITIVE` on the clipboard item, no timed clear, no unlock before a stored key is shown again, or that gate bought with a dependency, an activity base class and a theme change instead of the platform's own keyguard.

Python `unittest` coverage exercises account/session isolation and deletion, opaque event pagination/idempotency, checkpoint compaction, rate limits including trusted-proxy IP handling, and authorized audio-route validation/stream cleanup, and adds server-side managed backups: live online copies, SHA-256 sidecars, retention, restore with a pre-restore safety snapshot, tamper detection, and refusal to resolve a restore name outside the backup folder. GitHub Actions runs the Android build and uploads a debug APK, plus the server API tests across Python 3.10 and 3.14.

Documentation: [Jellyfin API](https://api.jellyfin.org/) · [Jellyfin authentication](https://jellyfin.org/docs/general/administration/configuration/#security) · [TheAudioDB free music API](https://www.theaudiodb.com/free_music_api) · [Android Keystore](https://developer.android.com/training/articles/keystore) · [AndroidX BiometricPrompt](https://developer.android.com/reference/androidx/biometric/BiometricPrompt) · [ClipDescription.EXTRA_IS_SENSITIVE](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste#sensitive) · [Foreground service types](https://developer.android.com/about/versions/14/changes/fgs-types-required) · [OpenStreetMap](https://www.openstreetmap.org/copyright) · [CARTO map tiles](https://carto.com/developers/maps/)
