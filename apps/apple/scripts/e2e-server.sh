#!/bin/bash
# Runs the ServerKit end-to-end tests against the real Go server, on a Mac runner.
# Starts a throwaway Postgres, an SMTP sink (tools/smtp-sink) and the server, all with
# generated config and nothing real. Everything is stopped on exit.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
WORK="${RUNNER_TEMP:-$(mktemp -d)}/e2e"
rm -rf "$WORK"
mkdir -p "$WORK/data" "$WORK/mail" "$WORK/bin"

PIDS=()
PGDATA="$WORK/pgdata"
PG_STARTED=0
cleanup() {
  for pid in ${PIDS[@]+"${PIDS[@]}"}; do kill "$pid" 2>/dev/null || true; done
  if [ "$PG_STARTED" = 1 ]; then pg_ctl -D "$PGDATA" -m fast stop >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# 1. Postgres, listening on a Unix socket in /tmp only.
brew list postgresql@18 >/dev/null 2>&1 || brew install postgresql@18
export PATH="$(brew --prefix postgresql@18)/bin:$PATH"
initdb -D "$PGDATA" --auth=trust --encoding=UTF8 >/dev/null
pg_ctl -D "$PGDATA" -l "$WORK/postgres.log" -o "-k /tmp -c listen_addresses=''" -w start >/dev/null
PG_STARTED=1
createdb -h /tmp app

# 2. The server and the mail sink.
(cd "$ROOT/server" && go build -o "$WORK/bin/appserver" ./cmd/appserver)
(cd "$ROOT/tools/smtp-sink" && go build -o "$WORK/bin/smtp-sink" .)
"$WORK/bin/smtp-sink" -listen 127.0.0.1:2525 -out "$WORK/mail" >"$WORK/smtp-sink.log" 2>&1 &
PIDS+=($!)

# 3. Throwaway config. The sink needs no credentials; the server allows plain SMTP on loopback.
export APP_HOST=localhost
export APP_LISTEN=127.0.0.1:8080
export APP_ADMIN_LISTEN=127.0.0.1:9090
export APP_DATABASE_URL='postgres:///app?host=/tmp'
export APP_SMTP_URL=smtp://127.0.0.1:2525
export APP_DATA_DIR="$WORK/data"

"$WORK/bin/appserver" migrate
E2E_INVITE_CODE=$("$WORK/bin/appserver" invite create -uses 10 | sed 's#.*/invite/##')
export E2E_INVITE_CODE

"$WORK/bin/appserver" serve >"$WORK/appserver.log" 2>&1 &
PIDS+=($!)
ready=0
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:9090/readyz >/dev/null 2>&1; then ready=1; break; fi
  sleep 0.5
done
if [ "$ready" != 1 ]; then
  echo "appserver did not become ready in 30 s" >&2
  tail -n 30 "$WORK/appserver.log" >&2 || true
  exit 1
fi

# 4. The tests.
set +e
E2E_BASE_URL=http://127.0.0.1:8080 E2E_MAIL_DIR="$WORK/mail" E2E_ORIGIN=https://localhost \
  swift test --package-path "$ROOT/apps/apple/Packages/ServerKit" --filter ServerKitE2ETests
status=$?
set -e
if [ "$status" != 0 ]; then
  echo "--- appserver log (tail) ---" >&2
  tail -n 40 "$WORK/appserver.log" >&2 || true
fi
exit "$status"
