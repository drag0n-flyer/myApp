# [App] server admin guide

For the person who runs a server. Commands run from the `deploy/` directory unless said otherwise.

## Install

You need a Linux host with Docker and the compose plugin, a DNS name pointing at it, and ports 80 and 443 (reverse proxy) and 3478 and 443 UDP/TCP (calls) open.

1. `cp .env.example .env` and edit it. Set `POSTGRES_PASSWORD`, `APP_HOST` (your public host name) and `APP_TURN_SECRET` (a long random string). Every variable is described in the server design, section 5.
2. Put secrets (escrow key, push key) in `deploy/secrets/` and TLS files for coturn in `deploy/certs/`. Neither directory is committed.
3. Render the coturn config, which needs the secret: `TURN_SECRET=<APP_TURN_SECRET> envsubst '${TURN_SECRET}' < turnserver.conf > turnserver.rendered.conf`. Set `realm` in `turnserver.conf` to your host name first.
4. `docker compose up -d`. The server applies its database migrations on start.
5. Check it: `curl -s http://127.0.0.1:9090/readyz`.

`APP_APPLE_APP_IDS` must list both apps, for example `TEAMID.org.example.app,TEAMID.org.example.app.mac`. Invite and link URLs open in the app only if it is correct.

The server policy (invite requirement, call quality limits, retention, upload limits, abuse contact) is a JSON file named by `APP_POLICY_FILE`; see section 5 of the design for the fields and defaults.

## Reverse proxy

The server speaks plain HTTP on `127.0.0.1:8080`. A reverse proxy terminates TLS. The `/v1/ws` endpoint is a WebSocket, so the proxy must pass the upgrade through. Caddy does this by default:

```
chat.example.org {
    reverse_proxy 127.0.0.1:8080
}
```

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

1. Make a signing key for this server: `docker compose exec appserver /appserver keygen push > secrets/push.pem`, then set `APP_PUSH_KEY_FILE=/secrets/push.pem`.
2. Register it with the gateway: `docker compose exec appserver /appserver push register --gateway https://push.example.org --name "my server" --contact you@example.org`. It prints a server ID; set `APP_PUSH_SERVER_ID` to it, and `APP_PUSH_GATEWAY_URL` to the gateway URL.
3. `docker compose up -d` to restart with the new settings.

Without a gateway URL, push is off and the apps only update while open.

### Running your own gateway (optional)

The compose file has a `pushgw` service, off by default: `docker compose --profile gateway up -d`. It needs an Apple push key (`.p8`), which only its owner should hold. Set the `PUSHGW_*` variables in `.env`: `PUSHGW_HPKE_KEY_FILE`, `PUSHGW_APNS_KEY_FILE`, `PUSHGW_APNS_KEY_ID`, `PUSHGW_APNS_TEAM_ID`, `PUSHGW_BUNDLE_IDS` (the iPhone and Mac bundle IDs). Behind a reverse proxy, set `PUSHGW_TRUST_PROXY=true` so it sees client addresses; otherwise leave it `false`. Put the gateway behind TLS like the server.

The gateway sees raw APNs tokens: it opens each sealed handle to send the push to Apple. Treat its database, its logs and its HPKE key as sensitive. This is a known part of the threat model (OPEN-13).

## Escrow key

Only needed if the policy lets conversations be server-readable (`"newConversations": "server_readable"`). The server then refuses to start without a key.

```
docker compose exec appserver /appserver keygen escrow > secrets/escrow.pem
chmod 600 secrets/escrow.pem
```

Set `APP_ESCROW_KEY_FILE=/secrets/escrow.pem`. The public half is published in `GET /v1/server`. Back the file up separately from the database: without it the stored copies cannot be read, and anyone who holds it can read them. Do not change the key while escrowed messages exist you still need to read.

## Reports

Users can report an account and share up to 50 messages. If `abuseContact` is set in the policy, each report is emailed there. List them: `docker compose exec appserver /appserver reports list`.

## Backups, restore, upgrade, rollback

**Back up** the database and the attachments directory together:

```
docker compose exec -T postgres pg_dump -U appserver -Fc appserver > backup-$(date +%F).dump
docker run --rm -v deploy_appdata:/data -v "$PWD":/out alpine tar czf /out/appdata-$(date +%F).tgz -C /data .
```

(`deploy_appdata` is the volume behind `APP_DATA_DIR`; the prefix is your compose project name.) Also keep `.env`, `secrets/` and the policy file. Attachments are encrypted by the apps and expire after `attachmentDays`, so losing the directory costs only files still within that window.

**Restore** on a fresh install: start only postgres (`docker compose up -d postgres`), then `docker compose exec -T postgres pg_restore -U appserver -d appserver --clean --if-exists < backup.dump`, unpack the attachments archive into the data volume, and `docker compose up -d`.

**Upgrade:** take a backup, then `git pull`, `docker compose build`, `docker compose run --rm appserver /appserver migrate`, `docker compose up -d`. Migrations only add; they are applied in order and never edited.

**Rollback:** `git checkout` the previous version and `docker compose up -d --build`. If the new version ran a migration, the old binary still works with the added tables and columns; if anything is wrong, restore the backup taken before the upgrade.

## Monitoring

On the admin port (`127.0.0.1:9090`):

- `/healthz` answers 200 while the process runs.
- `/readyz` answers 200 only when the database is reachable; use it for restarts and uptime checks.
- `/debug/vars` shows counters (connections, requests, errors) as JSON.

Logs go to stdout (`docker compose logs appserver`). The server never logs tokens, codes, email addresses, push handles, message data or client addresses.

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
