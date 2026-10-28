#!/bin/bash
# Starts the Go server for the Android LiveSmokeTest on a Linux runner and leaves it running in the background:
# a throwaway Postgres (Docker, as in server.yml), the SMTP sink (tools/smtp-sink) and the server, all with
# generated config and nothing real. The emulator reaches the host's loopback as 10.0.2.2.
#
#   127.0.0.1:8080  the server (APP_HOST=localhost)
#   127.0.0.1:8025  the mail directory over plain http: <n>.eml from the sink, and invite.txt (an invite code)
#
# Set LIVE_DATABASE_URL to use an existing empty database instead of Docker (local runs).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${RUNNER_TEMP:-$(mktemp -d)}/android-live"
rm -rf "$WORK"
mkdir -p "$WORK/data" "$WORK/mail" "$WORK/bin"

# 1. Postgres.
if [ -n "${LIVE_DATABASE_URL:-}" ]; then
  DB_URL="$LIVE_DATABASE_URL"
else
  docker run -d --name android-live-pg -p 127.0.0.1:55432:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:18 >/dev/null
  for _ in $(seq 1 60); do
    if docker exec android-live-pg pg_isready -U postgres >/dev/null 2>&1; then break; fi
    sleep 1
  done
  DB_URL='postgres://postgres@127.0.0.1:55432/postgres?sslmode=disable'
fi

# 2. The server and the mail sink.
(cd "$ROOT/server" && go build -o "$WORK/bin/appserver" ./cmd/appserver)
(cd "$ROOT/tools/smtp-sink" && go build -o "$WORK/bin/smtp-sink" .)
nohup "$WORK/bin/smtp-sink" -listen 127.0.0.1:2525 -out "$WORK/mail" >"$WORK/smtp-sink.log" 2>&1 &

# 3. Throwaway config. The sink needs no credentials; the server allows plain SMTP on loopback.
export APP_HOST=localhost
export APP_LISTEN=127.0.0.1:8080
export APP_ADMIN_LISTEN=127.0.0.1:9090
export APP_DATABASE_URL="$DB_URL"
export APP_SMTP_URL=smtp://127.0.0.1:2525
export APP_DATA_DIR="$WORK/data"

"$WORK/bin/appserver" migrate
"$WORK/bin/appserver" invite create -uses 100 | sed 's#.*/invite/##' >"$WORK/mail/invite.txt"
nohup "$WORK/bin/appserver" serve >"$WORK/appserver.log" 2>&1 &
nohup python3 -m http.server 8025 --bind 127.0.0.1 --directory "$WORK/mail" >"$WORK/mail-http.log" 2>&1 &

ready=0
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:9090/readyz >/dev/null 2>&1 && curl -fsS http://127.0.0.1:8025/invite.txt >/dev/null 2>&1; then
    ready=1; break
  fi
  sleep 0.5
done
if [ "$ready" != 1 ]; then
  echo "appserver or the mail directory did not become ready in 30 s" >&2
  tail -n 30 "$WORK/appserver.log" >&2 || true
  exit 1
fi
echo "android live server ready (logs in $WORK)"
