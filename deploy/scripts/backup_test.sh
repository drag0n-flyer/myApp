#!/bin/sh
# Tests backup.sh and restore.sh without docker, root or a real server: they run
# against a local Postgres with two temporary databases. Needs psql, pg_dump,
# pg_restore, createdb/dropdb-capable access.
#
#   TEST_DATABASE_URL='postgres:///postgres?host=/var/run/postgresql' sh deploy/scripts/backup_test.sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
admin=${TEST_DATABASE_URL:-postgres:///postgres?host=/var/run/postgresql}
suffix="$$_$(date +%s)"
src_db="bk_src_$suffix"
dst_db="bk_dst_$suffix"
# Build per-database URLs by replacing the database name in the path.
url_for() { printf '%s' "$admin" | sed "s#^\(postgres[a-z]*://[^/]*\)/[^?]*#\1/$1#"; }
src_url=$(url_for "$src_db")
dst_url=$(url_for "$dst_db")
tmp=$(mktemp -d)
cleanup() {
	psql -q "$admin" -c "drop database if exists $src_db" -c "drop database if exists $dst_db" >/dev/null 2>&1 || true
	rm -rf "$tmp"
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

psql -q "$admin" -c "create database $src_db" -c "create database $dst_db"
psql -q "$src_url" -c "create table accounts (id int primary key, name text)" -c "insert into accounts values (1, 'alice')"
mkdir -p "$tmp/data/attachments/ab"
printf 'attachment bytes' > "$tmp/data/attachments/ab/abcdef"

export BACKUP_DIR="$tmp/backups"
export DB_DUMP_CMD="pg_dump --format=custom '$src_url'"
export DATA_TAR_CMD="tar czf - -C '$tmp/data' ."
export COMPOSE=false

# 1. dry run writes nothing and shows the plan
out=$(sh "$here/backup.sh" --dry-run)
[ ! -e "$BACKUP_DIR" ] || fail "dry run created $BACKUP_DIR"
printf '%s' "$out" | grep -q 'would write .*db.dump' || fail "dry run plan missing"

# 2. a real backup writes the three files, and a second one the same day is refused
sh "$here/backup.sh" >/dev/null
d="$BACKUP_DIR/$(date +%F)"
for f in db.dump appdata.tgz SHA256SUMS; do [ -s "$d/$f" ] || fail "missing $f"; done
(cd "$d" && sha256sum -c SHA256SUMS >/dev/null) || fail "checksums"
[ ! -e "$d.partial" ] || fail "partial directory left behind"
if sh "$here/backup.sh" >/dev/null 2>&1; then fail "second backup on one day was allowed"; fi
[ "$(stat -c %a "$d/db.dump")" = 600 ] || fail "db.dump is not private (umask)"

# 3. a failing dump leaves nothing behind
rm -rf "$BACKUP_DIR"
if DB_DUMP_CMD=false sh "$here/backup.sh" >/dev/null 2>&1; then fail "failed dump reported success"; fi
[ -z "$(ls -A "$BACKUP_DIR")" ] || fail "failed dump left files"
sh "$here/backup.sh" >/dev/null

# 4. restore refuses a database that already has accounts, unless --force
export DB_RESTORE_CMD="pg_restore -d '$dst_url' --clean --if-exists"
export DB_PSQL_CMD="psql -Atq '$dst_url'"
export PREPARE_CMD=true
export MIGRATE_CMD="echo migrated > '$tmp/migrated'"
export DATA_UNTAR_CMD="tar xzf - -C '$tmp/restored'"
mkdir "$tmp/restored"
psql -q "$dst_url" -c "create table accounts (id int primary key)"
if sh "$here/restore.sh" "$d" >/dev/null 2>&1; then fail "restore overwrote a database with accounts"; fi
psql -q "$dst_url" -c "drop table accounts"

# 5. restore into the empty database brings back rows, files, and runs migrate
sh "$here/restore.sh" --dry-run "$d" | grep -q 'would restore' || fail "restore dry run"
sh "$here/restore.sh" "$d" >/dev/null
[ "$(psql -Atq "$dst_url" -c "select name from accounts where id = 1")" = alice ] || fail "row not restored"
[ "$(cat "$tmp/restored/attachments/ab/abcdef")" = 'attachment bytes' ] || fail "file not restored"
[ -f "$tmp/migrated" ] || fail "migrate not run"

# 6. a damaged backup is refused before anything is touched
printf x >> "$d/db.dump"
if sh "$here/restore.sh" --force "$d" >/dev/null 2>&1; then fail "damaged backup accepted"; fi

# 7. prune keeps the newest 3 days and the newest 2 Sundays
rm -rf "$BACKUP_DIR"
mkdir "$BACKUP_DIR"
for n in $(seq 1 31); do mkdir "$BACKUP_DIR/2026-01-$(printf %02d "$n")"; done
mkdir "$BACKUP_DIR/not-a-backup"
KEEP_DAILY=3 KEEP_WEEKLY=2 sh "$here/backup.sh" prune --dry-run >/dev/null
[ "$(ls "$BACKUP_DIR" | wc -l)" = 32 ] || fail "prune dry run removed something"
KEEP_DAILY=3 KEEP_WEEKLY=2 sh "$here/backup.sh" prune >/dev/null
got=$(ls "$BACKUP_DIR" | tr '\n' ' ')
want="2026-01-18 2026-01-25 2026-01-29 2026-01-30 2026-01-31 not-a-backup "
[ "$got" = "$want" ] || fail "prune kept: $got"

echo "backup tests ok"
