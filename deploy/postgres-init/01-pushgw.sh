#!/bin/sh
# Runs once, when the postgres volume is first created. Gives the optional push
# gateway its own role and database, a separate trust boundary from the server.
# Skipped when PUSHGW_DB_PASSWORD is empty. On an existing volume, run the same
# statements by hand (see the admin guide).
set -eu
[ -n "${PUSHGW_DB_PASSWORD:-}" ] || exit 0
psql -v ON_ERROR_STOP=1 -v pw="$PUSHGW_DB_PASSWORD" -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL'
create role pushgw login password :'pw';
create database pushgw owner pushgw;
SQL
