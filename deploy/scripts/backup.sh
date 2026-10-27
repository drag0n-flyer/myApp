#!/bin/sh
# Backs up the app database and the attachments directory into one dated
# directory, then removes old backups (keeps 14 daily and 8 weekly).
#
#   sh scripts/backup.sh [--dry-run]         make a backup
#   sh scripts/backup.sh prune [--dry-run]   only remove old backups
#
# Run it from deploy/ (or set COMPOSE). Output: $BACKUP_DIR/YYYY-MM-DD/ with
#   db.dump       pg_dump --format=custom of the app database
#   appdata.tgz   tar of APP_DATA_DIR (the attachments)
#   SHA256SUMS    checksums of both
# The files are not encrypted by this script: keep them on encrypted storage.
# Adding age (or any other tool) needs the project owner's approval.
#
# Settings (environment):
#   BACKUP_DIR     where backups go (default: ./backups)
#   COMPOSE        compose command (default: docker compose)
#   DB_SERVICE, DB_USER, DB_NAME   (default: postgres, appserver, appserver)
#   SKIP_DATA=1    database only (for the push gateway, which has no data dir)
#   KEEP_DAILY, KEEP_WEEKLY        (default: 14, 8; weekly = Sunday backups)
#   HELPER_IMAGE   image that runs tar (default: alpine:3.22)
# For tests, DB_DUMP_CMD and DATA_TAR_CMD replace the docker commands; each
# is run by sh and must write its result to standard output.
set -eu
umask 077

BACKUP_DIR=${BACKUP_DIR:-./backups}
COMPOSE=${COMPOSE:-docker compose}
DB_SERVICE=${DB_SERVICE:-postgres}
DB_USER=${DB_USER:-appserver}
DB_NAME=${DB_NAME:-appserver}
SKIP_DATA=${SKIP_DATA:-0}
KEEP_DAILY=${KEEP_DAILY:-14}
KEEP_WEEKLY=${KEEP_WEEKLY:-8}
HELPER_IMAGE=${HELPER_IMAGE:-alpine:3.22}

mode=backup
dry=0
for a in "$@"; do
	case "$a" in
	--dry-run) dry=1 ;;
	prune) mode=prune ;;
	*) echo "usage: backup.sh [prune] [--dry-run]" >&2; exit 2 ;;
	esac
done

# prune: the newest KEEP_DAILY dated directories stay, and so do the newest
# KEEP_WEEKLY directories whose date is a Sunday. Every other dated one goes.
prune() {
	[ -d "$BACKUP_DIR" ] || return 0
	days=$(ls -1 "$BACKUP_DIR" | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' | sort -r || true)
	daily=0
	weekly=0
	for d in $days; do
		keep=0
		if [ "$daily" -lt "$KEEP_DAILY" ]; then
			daily=$((daily + 1))
			keep=1
		fi
		if [ "$(date -d "$d" +%u)" = 7 ] && [ "$weekly" -lt "$KEEP_WEEKLY" ]; then
			weekly=$((weekly + 1))
			keep=1
		fi
		if [ "$keep" = 0 ]; then
			if [ "$dry" = 1 ]; then
				echo "would remove $BACKUP_DIR/$d"
			else
				rm -rf "${BACKUP_DIR:?}/$d"
				echo "removed $BACKUP_DIR/$d"
			fi
		fi
	done
}

if [ "$mode" = prune ]; then
	prune
	exit 0
fi

today=$(date +%F)
final="$BACKUP_DIR/$today"
work="$BACKUP_DIR/$today.partial"

db_cmd=${DB_DUMP_CMD:-"$COMPOSE exec -T $DB_SERVICE pg_dump -U $DB_USER --format=custom $DB_NAME"}
if [ -n "${DATA_TAR_CMD:-}" ]; then
	data_cmd=$DATA_TAR_CMD
else
	# The appserver image has no tar, so a helper container reads its volume.
	data_cmd='docker run --rm --volumes-from "$($COMPOSE ps -aq appserver)":ro '"$HELPER_IMAGE"' tar czf - -C /var/lib/appserver .'
fi

if [ "$dry" = 1 ]; then
	echo "would write $final/db.dump with: $db_cmd"
	[ "$SKIP_DATA" = 1 ] || echo "would write $final/appdata.tgz with: $data_cmd"
	echo "would write $final/SHA256SUMS"
	prune
	exit 0
fi

[ ! -e "$final" ] || { echo "backup: $final already exists; one backup per day" >&2; exit 1; }
mkdir -p "$BACKUP_DIR"
rm -rf "$work"
mkdir "$work"
trap 'rm -rf "$work"' EXIT

eval "$db_cmd" > "$work/db.dump"
[ -s "$work/db.dump" ] || { echo "backup: the database dump is empty" >&2; exit 1; }
files="db.dump"
if [ "$SKIP_DATA" != 1 ]; then
	eval "$data_cmd" > "$work/appdata.tgz"
	tar tzf "$work/appdata.tgz" >/dev/null
	files="db.dump appdata.tgz"
fi
(cd "$work" && sha256sum $files > SHA256SUMS)
mv "$work" "$final"
trap - EXIT
echo "backup written to $final"
prune
