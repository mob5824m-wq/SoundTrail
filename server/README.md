# Soundtrail self-hosted sync service

This service provides Soundtrail account sessions and an end-to-end-encrypted sync log with encrypted checkpoints. Deploy it on infrastructure you control, behind HTTPS.

## What the server can see

For cloud sync, the Android app encrypts each entity event and checkpoint on-device with AES-256-GCM. The server cannot decrypt pins, coordinates, playlist details, automation rules, song links, or the recovery key. It does see account email addresses, salted password hashes, session metadata, event IDs/sequences, ciphertext sizes, client IPs in proxy/server logs, and request timing.

Keep the machine, database volume, TLS proxy, backups, and logs secured. The database has a **64 MiB per-account quota** for encrypted sync events and checkpoints. Event rows are pruned into an encrypted client checkpoint after sync; retry-receipt metadata is also pruned at that point. Old delivery IDs may therefore be accepted again after compaction, but encrypted entity clocks and tombstones make duplicate old versions harmless. Snapshots are limited to 8 MiB.

The server applies in-process sliding-window limits to account creation, login, sync reads/writes, checkpoints, and account deletion. Current defaults are 5 account creations/hour/IP, 20 logins/minute/IP, 600 sync reads and writes/minute/IP, 10 checkpoints/minute/IP, and 5 account deletions/hour/IP. In-process limits reset on restart and are per instance, so keep the API behind a reverse proxy with its own request/body limits and rate limiting, especially if you run multiple workers. The app trusts `X-Forwarded-For` only when its direct peer is private/loopback; configure the proxy to overwrite or sanitize client-supplied forwarding headers before passing them upstream. There is no email verification or password-reset service. An authenticated account-deletion endpoint is available.

## Run natively (no Docker, any OS and CPU)

The server is plain Python. It runs on Linux, macOS, Windows, and BSD, on x86-64, ARM64, and other CPUs with a Python 3.10+ interpreter. Docker is not required.

1. Point a DNS name such as `sync.example.net` at your server.
2. Install Python 3.10 or newer (`python3 --version`, or `py -3 --version` on Windows).
3. Start the service from the `server` folder:

   ```bash
   cd server
   python3 run.py          # Windows: py -3 run.py
   ```

   `run.py` creates a private `.venv` and starts the API. The server uses only the Python standard library, so nothing is downloaded. Other commands:

   - `python3 run.py --setup` prepares the environment without starting the server.
   - `python3 run.py --test` runs the API test suite.

4. Put a reverse proxy with TLS in front of it. The API listens on `127.0.0.1:8080` by default. Keep that loopback default and proxy to it, for example with [`Caddyfile.example`](Caddyfile.example) (Caddy runs on the same machine, so the upstream is `127.0.0.1:8080`). Do not expose port 8080 directly.

The SQLite database is created at `server/data/soundtrail.sqlite3`. Set `SOUNDTRAIL_DB` to store it elsewhere (for example on a dedicated disk).

## Managed backups

The service backs up its own database. A daemon thread takes a consistent copy through SQLite's
online backup API at startup and then every 24 hours, verifies it with `PRAGMA integrity_check`,
writes a SHA-256 sidecar, and deletes older snapshots beyond the retention count. Snapshot files
are named `soundtrail-YYYYmmdd-HHMMSS-ffffff.sqlite3` and live in `<database folder>/backups` by
default.

| Variable | Default | Meaning |
| --- | --- | --- |
| `SOUNDTRAIL_BACKUP_DIR` | `<database folder>/backups` | Where snapshots and their `.sha256` sidecars are written. |
| `SOUNDTRAIL_BACKUP_INTERVAL_SECONDS` | `86400` | Seconds between automatic snapshots. `0` turns the thread off (for example when an external backup tool owns the database). |
| `SOUNDTRAIL_BACKUP_KEEP` | `7` | How many snapshots remain after a successful backup. |

The same code works from the command line, with or without the virtual environment:

```bash
cd server
python3 run.py backup                 # write and verify a snapshot now
python3 run.py list                   # newest first, with sizes and checksum state
python3 run.py verify                 # verify the newest snapshot
python3 run.py verify soundtrail-20261019-120000-000000.sqlite3
python3 run.py restore NAME --yes     # replace the live database (see below)
```

`restore` verifies the snapshot's checksum and integrity first, takes a safety snapshot of the
current database, then replaces it. **Stop the service before restoring** and start it again
afterwards. The command refuses to run without `--yes`, and a name that is not a bare snapshot
file in the backup folder is rejected, so it cannot read or overwrite an arbitrary path.
Pass the exact name that `list` printed.

A snapshot is the whole database, not the decrypted library: sync payloads stay AES-256-GCM
ciphertext. It still contains account email addresses, password hashes, session metadata, event
metadata, and ciphertext sizes, exactly as the live database does. Treat the backup folder as
sensitive (`0o700` is applied where the platform supports it), keep it on a volume only the
service user can read, and copy snapshots off-host. Backups on the same disk do not survive a
lost volume or a hosting account closure.

### Run as a background service

Use your platform's service manager so the API restarts after reboots. Run it as a dedicated, unprivileged user.

**Linux (systemd)**: save as `/etc/systemd/system/soundtrail-sync.service`, adjusting `User`, `WorkingDirectory`, and the paths:

```ini
[Unit]
Description=Soundtrail encrypted sync API
After=network-online.target
Wants=network-online.target

[Service]
User=soundtrail
WorkingDirectory=/opt/soundtrail/server
Environment=SOUNDTRAIL_HOST=127.0.0.1
Environment=PORT=8080
Environment=SOUNDTRAIL_DB=/var/lib/soundtrail/soundtrail.sqlite3
ExecStart=/usr/bin/python3 /opt/soundtrail/server/run.py
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Then run `sudo systemctl daemon-reload && sudo systemctl enable --now soundtrail-sync`.

**macOS (launchd)** and **Windows** (Task Scheduler, or a service wrapper such as NSSM): run `python3 run.py` (or `py -3 run.py`) from the `server` folder at boot, with the working directory set to `server`, and set `SOUNDTRAIL_HOST`/`SOUNDTRAIL_DB` as environment variables for that task.

### Back up the database

The service already writes and verifies its own snapshots — see [Managed backups](#managed-backups)
above. Those copies are taken through SQLite's online backup API, so the service does not need to
be stopped, and each one is checksummed and pruned.

A whole-folder archive is still useful before an upgrade or when moving hosts. Stop the service
and archive the database plus its WAL side-files:

```bash
# Linux/macOS, from the server folder, with the service stopped
mkdir -p /secure/offsite && chmod 700 /secure/offsite
tar -czf /secure/offsite/soundtrail-data.tar.gz -C . data
```

On Windows, stop the task and copy the `data` folder to a safe location. To restore an archive,
stop the service, keep a copy of the current folder, and replace the `data` folder (or the
`SOUNDTRAIL_DB` location) with the archived contents. Test restoration, and keep an off-host copy
outside the server's own volume.

The `/health` endpoint reports liveness only.

### Dependencies

None beyond the Python 3.10+ standard library.

## HTTP API

`/health` is a public liveness endpoint; the application endpoints are under `/v1`:

- `POST /accounts` — `{ "email": "…", "password": "…" }`, creates the account and returns `accountId`, `email`, `token`, and `expiresAt`.
- `POST /sessions` — same body, authenticates and returns a new bearer token.
- `DELETE /sessions/current` — revokes the current bearer session.
- `DELETE /account/current` — deletes the authenticated account, sessions, sync events, receipts, and checkpoint. This cannot be undone.
- `GET /events?after=<sequence>&limit=<1..500>` — returns opaque encrypted events, cursor information, and (when the caller is behind) the latest encrypted checkpoint plus events after it.
- `POST /events` — `{ "events": [{ "id": "UUID", "iv": "base64url", "ciphertext": "base64url" }] }`.
- `POST /snapshots` — stores a client-encrypted full-state checkpoint through a server cursor, then prunes covered event rows.

Each account's cursor is independent and every sync read/write is scoped to that account. Checkpoints are encrypted and authenticated by the client; the server cannot inspect their contents. Event delivery is idempotent until a checkpoint compacts the corresponding receipt metadata. Passwords are stored as per-account salted scrypt hashes; random session tokens expire after 30 days and are stored server-side only as SHA-256 hashes.

## Configuration

- `PORT` — listen port (default `8080`).
- `SOUNDTRAIL_HOST` — listen address (default `127.0.0.1`; keep it on loopback behind a TLS proxy).
- `SOUNDTRAIL_DB` — SQLite file (default `server/data/soundtrail.sqlite3`).
- `SOUNDTRAIL_BACKUP_DIR` — snapshot folder (default `<database folder>/backups`).
- `SOUNDTRAIL_BACKUP_INTERVAL_SECONDS` — seconds between automatic snapshots (default `86400`; `0` disables the background thread).
- `SOUNDTRAIL_BACKUP_KEEP` — snapshots retained after a successful backup (default `7`).

The API intentionally has no password-reset or key-recovery endpoint. If a user loses their 256-bit recovery key, resetting the account password cannot recover encrypted content. Account deletion removes remote encrypted data; it does not erase the user's local device library. If the database is lost, only a database backup can restore the encrypted event log and checkpoints.

## Run the API tests

```bash
python3 run.py --test   # or: python3 -m unittest discover -s . -v
```
