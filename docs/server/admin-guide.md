# [App] server admin guide

For the person who runs a server. Commands run from the `deploy/` directory unless said otherwise.

## Install

You need a Linux host with Docker and the compose plugin, a DNS name pointing at it, and ports 80 and 443 (reverse proxy) open. Calls need more ports; see "Calls and TURN" below.

1. `cp .env.example .env` and edit it. Set `POSTGRES_PASSWORD`, `APP_HOST` (your public host name) and `APP_TURN_SECRET` (a long random string). Every variable is described in the server design, section 5.
2. Put secrets (escrow key, push key) in `deploy/secrets/` and TLS files for coturn in `deploy/certs/`. Neither directory is committed. The server runs as user 65532 (not root), so every key file it reads must be owned by that user and private: `sudo chown 65532:65532 secrets/*.pem && sudo chmod 0400 secrets/*.pem`. (`chmod 600` alone would leave the file owned by you and unreadable to the server.) Do the same for the push gateway's HPKE and APNs (`.p8`) keys if you run a gateway.
   The attachments volume is created owned by that user; no step is needed.
3. Render the coturn config, which needs the secret: `TURN_SECRET=<APP_TURN_SECRET> envsubst '${TURN_SECRET}' < turnserver.conf > turnserver.rendered.conf`. Set `realm` in `turnserver.conf` to your host name first.
4. `docker compose up -d`. The server applies its database migrations on start.
5. Check it: `curl -s http://127.0.0.1:9090/readyz`.

`APP_ADMIN_LISTEN` in `.env` has no effect under compose: `compose.yaml` sets it to `0.0.0.0:9090` inside the container, and publishes it on `127.0.0.1:9090` only.

`APP_APPLE_APP_IDS` must list both apps, for example `TEAMID.org.example.app,TEAMID.org.example.app.mac`. Invite and link URLs open in the app only if it is correct.

The server policy (invite requirement, call quality limits, retention, upload limits, abuse contact) is a JSON file named by `APP_POLICY_FILE`; see section 5 of the design for the fields and defaults.

## Security contact and legal pages

The server serves four files from `APP_DOCS_DIR`, read on every request, so edits show without a restart. Under compose, `deploy/docs/` on the host is mounted read-only at `/docs` in the container, and `.env.example` already sets `APP_DOCS_DIR=/docs`.

| URL | File in `deploy/docs/` |
|---|---|
| `/.well-known/security.txt` | `security.txt` |
| `/legal/privacy` | `privacy.html` |
| `/legal/terms` | `terms.html` |
| `/legal/disclosure` | `disclosure.html` |

A missing file gives 404. To publish a security contact:

1. `cp docs/security.txt.example docs/security.txt`
2. Edit `docs/security.txt`: set `Contact`, your host name in `Policy` and `Canonical`, and an `Expires` date less than a year ahead.
3. Create `docs/disclosure.html` (the `Policy` line points to `/legal/disclosure`, which is 404 until it exists), or remove the `Policy` line.
4. `docker compose up -d` (needed once, so the container picks up `APP_DOCS_DIR` and the mount; later edits to the files need no restart).

At start the server logs a warning if `security.txt` has expired, expires within 30 days, or has no valid `Expires`. Renew the date before then (RFC 9116 treats an expired file as stale). The privacy and terms pages are drafts that need a lawyer's review before use.

## Reverse proxy

The server speaks plain HTTP on `127.0.0.1:8080`. A reverse proxy terminates TLS. The `/v1/ws` endpoint is a WebSocket, so the proxy must pass the upgrade through. Caddy does this by default:

```
chat.example.org {
    reverse_proxy 127.0.0.1:8080
}
```

The compose file also has an optional Caddy service with this setup (`deploy/Caddyfile`, automatic HTTPS for `APP_HOST`): `docker compose --profile caddy up -d`. Leave it off if you run your own proxy on ports 80 and 443.

With nginx, add `proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";` and a long `proxy_read_timeout` for `/v1/ws`. Allow request bodies up to your `attachmentBytes` limit (100 MiB by default) on `/v1/attachments`.

Set `APP_TRUST_PROXY=true` only when the proxy sets `X-Forwarded-For`. The server then uses the last hop as the client address for rate limits. If clients can reach the server directly, leave it `false`, or anyone can fake their address.

Never publish port 9090 (admin). It is bound to `127.0.0.1` on purpose.

## First invite

Sign-up needs an invite while `requiresInvite` is true. Create one:

```
docker compose exec appserver /appserver invite create
```

It prints a code like `ABCDE-FGHJK`. Share the link `https://<host>/invite/ABCDE-FGHJK`; on a phone with the app it opens the app, otherwise it shows a short page.

## Push notifications

Push goes through a gateway, which holds the Apple key. Use the gateway of the app's owner, or run your own (below).

1. Make a signing key for this server: `docker compose exec appserver /appserver keygen push > secrets/push.pem`, run `sudo chown 65532:65532 secrets/push.pem && sudo chmod 0400 secrets/push.pem`, then set `APP_PUSH_KEY_FILE=/secrets/push.pem`.
2. Register it with the gateway: `docker compose exec appserver /appserver push register --gateway https://push.example.org --name "my server" --contact you@example.org`. It prints a server ID; set `APP_PUSH_SERVER_ID` to it, and `APP_PUSH_GATEWAY_URL` to the gateway URL.
3. `docker compose up -d` to restart with the new settings.

Without a gateway URL, push is off and the apps only update while open.

### Running your own gateway (optional)

The compose file has a `pushgw` service, off by default: `docker compose --profile gateway up -d`. It needs an Apple push key (`.p8`), which only its owner should hold. Give the key files (`.p8` and the HPKE key) owner 65532 and mode 0400, as for the escrow key.

The gateway uses its own database role and database (`pushgw`), apart from the server's, because it is a separate trust boundary. Set `PUSHGW_DB_PASSWORD` in `.env` before the first start and the postgres container creates them. On an existing database volume the init script does not run; create them by hand: `docker compose exec postgres psql -U appserver -c "create role pushgw login password '<password>'" -c "create database pushgw owner pushgw"`. Set the `PUSHGW_*` variables in `.env`: `PUSHGW_HPKE_KEY_FILE`, `PUSHGW_APNS_KEY_FILE`, `PUSHGW_APNS_KEY_ID`, `PUSHGW_APNS_TEAM_ID`, `PUSHGW_BUNDLE_IDS` (the iPhone and Mac bundle IDs). Behind a reverse proxy, set `PUSHGW_TRUST_PROXY=true` so it sees client addresses; otherwise leave it `false`. Put the gateway behind TLS like the server.

The gateway sees raw APNs tokens: it opens each sealed handle to send the push to Apple. Treat its database, its logs and its HPKE key as sensitive. This is a known part of the threat model (OPEN-13).

## Calls and TURN

Calls relay through coturn. The server hands out short-lived credentials made from `APP_TURN_SECRET`, and `APP_RELAYS` lists the relay hosts the apps may use.

**Production topology:** run coturn on dedicated relay hosts, separate from the server, each with its own DNS name and TLS certificate, serving TLS on port 443. This lets calls get through networks that only allow HTTPS. The beta plan has two relay nodes (for example `turn1.example.org@eu` and `turn2.example.org@us`). On a relay host, use the same `turnserver.conf` but set `tls-listening-port=443`; Linux only lets non-root processes bind ports below 1024 if the container is given that right (for example `sysctl net.ipv4.ip_unprivileged_port_start=0` on the host, or `cap_add: [NET_BIND_SERVICE]`), so set one of those. Put every relay in `APP_RELAYS`.

**Single-host compose:** the bundled coturn shares the server's host, where the reverse proxy owns 443, so it listens on 3478 and `tls-listening-port=5349`. It has **no TLS-on-443 fallback**: callers on networks that block everything except 443 will not be relayed. Use it for trying things out or a small private server.

**Firewall and addresses.** Open 3478 (UDP and TCP), 5349 (TCP) and the relay port range `min-port` to `max-port` (49152-49999 in the shipped file, UDP). If the host is behind NAT or has several addresses, set `external-ip=<public ip>[/<private ip>]` in `turnserver.conf`. coturn refuses to relay to private and local address ranges (`denied-peer-ip`), so a call cannot be used to probe your internal network; do not remove those lines.

## Escrow key

Only needed if the policy lets conversations be server-readable (`"newConversations": "server_readable"`). The server then refuses to start without a key.

```
docker compose exec appserver /appserver keygen escrow > secrets/escrow.pem
sudo chown 65532:65532 secrets/escrow.pem
sudo chmod 0400 secrets/escrow.pem
```

Set `APP_ESCROW_KEY_FILE=/secrets/escrow.pem`. The public half is published in `GET /v1/server`. Back the file up separately from the database: without it the stored copies cannot be read, and anyone who holds it can read them. Do not change the key while escrowed messages exist you still need to read. Keep the key for as long as any server-readable conversation exists, even if you later switch new conversations back to end-to-end: those conversations keep producing escrow copies, and the server refuses to start if the key file is set but missing or invalid.

## Reports

Users can report an account and share up to 50 messages. If `abuseContact` is set in the policy, each report is emailed there. List them: `docker compose exec appserver /appserver reports list`.

## Backups, restore, upgrade, rollback

**Back up** the database and the attachments directory together:

```
docker compose exec -T postgres pg_dump -U appserver -Fc appserver > backup-$(date +%F).dump
docker run --rm -v deploy_appdata:/data -v "$PWD":/out alpine tar czf /out/appdata-$(date +%F).tgz -C /data .
```

(`deploy_appdata` is the volume behind `APP_DATA_DIR`; the prefix is your compose project name.) Also keep `.env`, `secrets/` and the policy file. Attachments are encrypted by the apps and expire after `attachmentDays`, so losing the directory costs only files still within that window.

The scripts `deploy/scripts/backup.sh` and `restore.sh` do the same, write a dated folder with checksums, and keep 14 daily and 8 weekly backups (`sh scripts/backup.sh --dry-run` shows what it would do). For a fresh install, `sh scripts/restore.sh <backup folder>` does the restore steps below and runs the migrations. The beta operations runbook (`docs/ops/beta-runbook.md`) covers the drill that proves a backup works.

**Restore** on a fresh install: start only postgres (`docker compose up -d postgres`), then `docker compose exec -T postgres pg_restore -U appserver -d appserver --clean --if-exists < backup.dump`, unpack the attachments archive into the data volume, and `docker compose up -d`.

**Upgrade:** take a backup, then `git pull`, `docker compose build`, `docker compose run --rm appserver migrate`, `docker compose up -d`. The server also migrates when it starts. Running `migrate` first, as its own step, means a failed migration shows up on your screen while the old version is still serving, instead of as a restart loop. Migrations only add; they are applied in order and never edited.

**Rollback:** `git checkout` the previous version and `docker compose up -d --build`. If the new version ran a migration, the old binary still works with the added tables and columns; if anything is wrong, restore the backup taken before the upgrade.

## Monitoring

On the admin port (`127.0.0.1:9090`):

- `/healthz` answers 200 while the process runs.
- `/readyz` answers 200 only when the database is reachable; use it for restarts and uptime checks.
- `/debug/vars` shows the Go runtime's numbers as JSON. The dashboard's overview (see "Admin dashboard") shows the server's own: connections, accounts, pushes and call relays.

Logs go to stdout (`docker compose logs appserver`). The server never logs tokens, codes, email addresses, push handles, message data or client addresses.

## Admin dashboard

A small web page on the admin port for the operator. It is plain HTML served by the server itself, with no outside scripts or fonts.

What it shows:
- Overview: health, version, uptime, and counts of accounts, devices, conversations, messages today, live connections, pushes sent and failed, and call relays.
- Invites: list, create (with the link shown once) and revoke.
- Accounts: search by username, see devices, sign out a device, delete an account.
- Reports: who reported whom and why, with counts of shared messages; mark a report handled.
- Admins: add admins, disable or enable them, reset a password, remove an admin.
- Server: the loaded policy and non-secret settings, read-only.
- Audit log: every change made in the dashboard or with `admin` commands, and every account or report opened. Searches, the overview and the server page are not logged.

What it never shows:
- message or attachment content, or escrow plaintext (escrow export stays a command-line action);
- session tokens, admin tokens, push handles, link secrets, email codes or IP addresses;
- secrets from the configuration (`APP_SMTP_URL`, `APP_DATABASE_URL`, `APP_TURN_SECRET`, key files): only "configured" or "not configured";
- full email addresses (shown as `a•••@example.org`) or the text of reports.

How to reach it: open an SSH tunnel, then browse to it.

```
ssh -N -L 9090:127.0.0.1:9090 you@<host>
```

Then open `http://localhost:9090/admin/`.

### First visit: create the first admin

When no admin account exists yet, the sign-in page shows "Create the first admin" instead. Choose a username (3 to 32 characters: letters, digits, dot, underscore, hyphen) and a password (12 to 128 characters), type the password twice, and you are signed in. There is no setup code and no other check.

**Warning: until the first admin exists, anyone who can reach the admin port can create it.** The admin listener defaults to `127.0.0.1` and you reach it through an SSH tunnel, so in the normal setup that is only you. If you changed `APP_ADMIN_LISTEN`, or put a proxy in front of the admin port, create the first admin before you open the port to anyone else, or create it from the command line with `appserver admin user add <username>`.

### Sign in and manage admins

Sign in with your username and password. After five wrong tries in a minute from one address, ten wrong tries in an hour for one username, or thirty in an hour in total, sign-in stops for a while. A wrong username, a wrong password and a disabled account all give the same answer.

The Admins page lists the admins. You can add an admin, disable or enable one, reset a password and remove an admin. Disabling, removing or changing the password of an admin signs that admin out everywhere. You cannot disable or remove yourself (ask another admin, or use the command line), and the last enabled admin cannot be disabled or removed. You can change your own password, which signs you out everywhere.

### Command line

The commands read the same `.env`. Use `docker compose exec appserver /appserver ...` (Docker) or `bin/appserver ...` after `make run`.

| Command | What it does |
|---|---|
| `admin user list` | Lists admins, whether each is enabled, and the last sign-in. |
| `admin user add <username>` | Adds an admin. The password is read from standard input, one line; pipe it in, for example `printf '%s\n' "$PW" \| appserver admin user add alice`. Run in a terminal, the typing is shown, because the binary has no way to hide it without a new dependency. |
| `admin user add <username> --generate` | Adds an admin with a random 20-character password, printed once. |
| `admin user reset-password <username>` | Sets a new password (from standard input, or `--generate`) and signs that admin out everywhere. It does not enable a disabled admin. |
| `admin user disable <username>` / `enable <username>` | Disables (signs the admin out everywhere) or enables an admin. From the command line the last admin can be disabled; it prints a warning. |

Every command is written to the audit log as the command line.

### Locked out?

Run `appserver admin user reset-password <username>` for a forgotten password. If every admin is disabled, run `appserver admin user enable <username>`, or `appserver admin user add <username>` for a new one. The one-time token sign-in below always works too.

### One-time token sign-in (recovery)

On the sign-in page, "Sign in with a one-time token" shows the token field. How to use it:
1. On the server run `docker compose exec appserver /appserver admin token create --label laptop` (Docker), or `bin/appserver admin token create --label laptop` from the repository after `make run` (the binary reads the same `.env`).
2. Paste the token into the sign-in page once. It works one time only, and an unused token expires after 15 minutes (`--minutes N` changes this, at most 60).
3. A session ends after 15 minutes without use, and after 8 hours at most. The overview's automatic refresh does not count as use. For a lost laptop, run `docker compose exec appserver /appserver admin sessions end` (or `bin/appserver admin sessions end`) to end every session.

Never publish port 9090. It must stay on `127.0.0.1`. Set `APP_ADMIN_HOSTS` only when you put your own HTTPS proxy in front of it, and give that proxy its own access control.

Every action is written to the audit log and kept 365 days. The log holds IDs, token labels and admin usernames, not passwords or email addresses. Escrow export stays a command-line action.

## What the operator can see

The server can see:
- accounts, usernames, emails, display names
- device names, platforms, last-seen times
- which accounts share a conversation, and which devices are in it
- message timing and sizes
- call timing between accounts
- block lists and reports
- attachment sizes and timing

The gateway can see which server sends to which opaque handle, and when. Apple can see the push token, and the timing and size of pushes.

## Lab mode

`APP_LAB_MODE=1` turns on `appserver lab session --username <u> --device <name> --platform iphone|mac`. It creates a session for any username without passkey, email or invite, and prints `accountID deviceID token`. It exists only for the Call Lab (measuring call quality).

**Warning: never set `APP_LAB_MODE` on a real server.** Anyone with shell access to the server can then sign in as any account, including existing ones. `serve` logs `LAB MODE: sessions can be created without sign-in; never enable on a real server` at start when it is on. Leave it unset, or set to anything other than `1`, for normal operation.
