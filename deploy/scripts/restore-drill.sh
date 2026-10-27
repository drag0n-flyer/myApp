#!/bin/sh
# Restore drill: proves that a backup made by backup.sh brings a fresh stack
# back with the same data. Needs docker with the compose plugin, curl, openssl
# and sha256sum. Uses its own temporary compose project and a temporary
# directory; it never touches deploy/.env or your real volumes.
#
#   sh deploy/scripts/restore-drill.sh
#
# It starts the app stack (postgres and appserver) with test settings and
# requiresInvite=false, creates an account (lab mode, test only), one
# attachment and one inbox row, runs backup.sh, deletes the stack and its
# volumes, starts a fresh stack, runs restore.sh and checks the data.
# Prints RESTORE DRILL OK on success. Set DRILL_PORT to change the local port.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
deploy=$(dirname "$here")
server=$(dirname "$deploy")/server
port=${DRILL_PORT:-18080}
proj="appdrill$$"

for tool in docker curl openssl sha256sum; do
	command -v "$tool" >/dev/null 2>&1 || { echo "restore drill: $tool is required" >&2; exit 2; }
done
for f in backup.sh restore.sh; do
	[ -f "$here/$f" ] || { echo "restore drill: $here/$f is missing" >&2; exit 1; }
done

tmp=$(mktemp -d)
COMPOSE="docker compose --project-directory $tmp -p $proj -f $deploy/compose.yaml -f $tmp/override.yaml"
export COMPOSE
cleanup() {
	status=$?
	# shellcheck disable=SC2086
	$COMPOSE down -v >/dev/null 2>&1 || true
	rm -rf "$tmp"
	[ "$status" -eq 0 ] || echo "RESTORE DRILL FAILED" >&2
	exit "$status"
}
trap cleanup EXIT INT TERM

# Test-only settings. Every value here is generated or a placeholder.
mkdir -p "$tmp/secrets" "$tmp/docs" "$tmp/backups"
cp -R "$deploy/postgres-init" "$tmp/postgres-init"
cat > "$tmp/.env" <<ENV
POSTGRES_PASSWORD=$(openssl rand -hex 16)
APP_HOST=drill.invalid
APP_TRUST_PROXY=false
APP_LAB_MODE=1
APP_POLICY_FILE=/policy.json
ENV
echo '{"requiresInvite": false}' > "$tmp/policy.json"
cat > "$tmp/override.yaml" <<YAML
services:
  appserver:
    build:
      context: $server
      dockerfile: $deploy/Dockerfile
    ports: !override
      - "127.0.0.1:$port:8080"
    volumes:
      - ./policy.json:/policy.json:ro
YAML

base="http://127.0.0.1:$port"
wait_ready() {
	i=0
	until curl -fsS "$base/v1/server" >/dev/null 2>&1; do
		i=$((i + 1))
		[ "$i" -le 60 ] || { echo "restore drill: server did not become ready" >&2; $COMPOSE logs appserver | tail -20 >&2; exit 1; }
		sleep 2
	done
}
psql_app() {
	# shellcheck disable=SC2086
	$COMPOSE exec -T postgres psql -U appserver -d appserver -Atq -v ON_ERROR_STOP=1 "$@"
}

echo "1/7 start the app stack"
# shellcheck disable=SC2086
$COMPOSE up -d --build --wait postgres
# shellcheck disable=SC2086
$COMPOSE up -d --build appserver
wait_ready

echo "2/7 create an account and an attachment"
# shellcheck disable=SC2086
session=$($COMPOSE exec -T appserver /appserver lab session --username drilluser --device drillphone --platform iphone)
set -- $session
device_id=$2
token=$3
head -c 200000 /dev/urandom > "$tmp/blob"
want=$(sha256sum "$tmp/blob" | cut -d' ' -f1)
resp=$(curl -fsS -X POST -H "Authorization: Bearer $token" -H 'Content-Type: application/octet-stream' \
	--data-binary "@$tmp/blob" "$base/v1/attachments")
att=$(printf '%s' "$resp" | sed -n 's/.*"id":"\([0-9a-f]*\)".*/\1/p')
[ -n "$att" ] || { echo "restore drill: upload gave no id: $resp" >&2; exit 1; }

echo "3/7 insert an inbox row"
psql_app -c "insert into inbox (device_id, kind, conversation_id, seq, epoch, sender_account, sender_device, payload, sent_at)
  values ('$device_id', 'drill', gen_random_uuid(), 1, 1, gen_random_uuid(), gen_random_uuid(), 'restore-drill'::bytea, now())"

echo "4/7 run backup.sh"
BACKUP_DIR="$tmp/backups" sh "$here/backup.sh"
dir=$(ls -d "$tmp/backups"/*/ | head -n 1)
dir=${dir%/}

echo "5/7 delete the stack and its volumes"
# shellcheck disable=SC2086
$COMPOSE down -v

echo "6/7 start a fresh stack and run restore.sh"
# shellcheck disable=SC2086
$COMPOSE up -d --wait postgres
sh "$here/restore.sh" "$dir"
# shellcheck disable=SC2086
$COMPOSE up -d appserver
wait_ready

echo "7/7 check the data"
n=$(psql_app -c "select count(*) from accounts where username = 'drilluser'")
[ "$n" = 1 ] || { echo "restore drill: account row missing" >&2; exit 1; }
n=$(psql_app -c "select count(*) from inbox where kind = 'drill' and payload = 'restore-drill'::bytea")
[ "$n" = 1 ] || { echo "restore drill: inbox row missing" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer $token" "$base/v1/attachments/$att" -o "$tmp/blob.back"
got=$(sha256sum "$tmp/blob.back" | cut -d' ' -f1)
[ "$got" = "$want" ] || { echo "restore drill: attachment bytes differ" >&2; exit 1; }

echo "RESTORE DRILL OK"
