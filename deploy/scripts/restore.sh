#!/bin/sh
# Restores a backup made by backup.sh into an empty stack, then runs the
# database migrations.
#
#   sh scripts/restore.sh [--dry-run] [--force] BACKUP_DIR
#
# Run it from deploy/ (or set COMPOSE), on a host where the stack has been
# created but not started. It starts postgres itself. It refuses to run when
# the database already has an accounts table, unless you pass --force. After
# it finishes, start the stack: docker compose up -d
#
# Settings (environment): COMPOSE, DB_SERVICE, DB_USER, DB_NAME, SKIP_DATA,
# HELPER_IMAGE, as in backup.sh. For tests, DB_RESTORE_CMD (reads the dump on
# standard input), DB_PSQL_CMD (reads SQL on standard input), DATA_UNTAR_CMD
# (reads the tar.gz on standard input), PREPARE_CMD and MIGRATE_CMD replace the
# docker commands; each is run by sh.
set -eu

COMPOSE=${COMPOSE:-docker compose}
DB_SERVICE=${DB_SERVICE:-postgres}
DB_USER=${DB_USER:-appserver}
DB_NAME=${DB_NAME:-appserver}
SKIP_DATA=${SKIP_DATA:-0}
HELPER_IMAGE=${HELPER_IMAGE:-alpine:3.22}

dry=0
force=0
src=
for a in "$@"; do
	case "$a" in
	--dry-run) dry=1 ;;
	--force) force=1 ;;
	-*) echo "usage: restore.sh [--dry-run] [--force] BACKUP_DIR" >&2; exit 2 ;;
	*) src=$a ;;
	esac
done
[ -n "$src" ] || { echo "usage: restore.sh [--dry-run] [--force] BACKUP_DIR" >&2; exit 2; }

prepare_cmd=${PREPARE_CMD:-"$COMPOSE up -d --wait $DB_SERVICE && $COMPOSE stop appserver && $COMPOSE create appserver"}
psql_cmd=${DB_PSQL_CMD:-"$COMPOSE exec -T $DB_SERVICE psql -U $DB_USER -d $DB_NAME -Atq"}
restore_cmd=${DB_RESTORE_CMD:-"$COMPOSE exec -T $DB_SERVICE pg_restore -U $DB_USER -d $DB_NAME --clean --if-exists"}
untar_cmd=${DATA_UNTAR_CMD:-'docker run --rm -i --volumes-from "$($COMPOSE ps -aq appserver)" '"$HELPER_IMAGE"' sh -c "tar xzf - -C /var/lib/appserver && chown -R 65532:65532 /var/lib/appserver"'}
migrate_cmd=${MIGRATE_CMD:-"$COMPOSE run --rm appserver migrate"}

if [ "$dry" = 1 ]; then
	echo "would check the checksums in $src/SHA256SUMS"
	echo "would prepare the stack with: $prepare_cmd"
	echo "would refuse if the database already has accounts (unless --force)"
	echo "would restore $src/db.dump with: $restore_cmd"
	[ "$SKIP_DATA" = 1 ] || echo "would unpack $src/appdata.tgz with: $untar_cmd"
	echo "would run the migrations with: $migrate_cmd"
	exit 0
fi

[ -f "$src/db.dump" ] || { echo "restore: $src/db.dump not found" >&2; exit 1; }
[ -f "$src/SHA256SUMS" ] || { echo "restore: $src/SHA256SUMS not found" >&2; exit 1; }
echo "checking checksums"
(cd "$src" && sha256sum -c SHA256SUMS)

echo "preparing the stack"
eval "$prepare_cmd"

has=$(printf "select to_regclass('public.accounts') is not null;\n" | eval "$psql_cmd")
if [ "$has" = t ] && [ "$force" != 1 ]; then
	echo "restore: the database already has data. Restore into an empty stack, or pass --force to overwrite it." >&2
	exit 1
fi

echo "restoring the database"
eval "$restore_cmd" < "$src/db.dump"
if [ "$SKIP_DATA" != 1 ]; then
	[ -f "$src/appdata.tgz" ] || { echo "restore: $src/appdata.tgz not found" >&2; exit 1; }
	echo "restoring the attachments"
	eval "$untar_cmd" < "$src/appdata.tgz"
fi
echo "running migrations"
eval "$migrate_cmd"
echo "restore finished. Start the stack: docker compose up -d"
