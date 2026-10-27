#!/bin/sh
# Checks that deploy/compose.yaml is valid, with and without the optional
# push gateway profile. Needs docker with the compose plugin.
set -eu
dir=$(cd "$(dirname "$0")" && pwd)
# compose.yaml reads .env; use the example when there is no real one.
if [ ! -e "$dir/.env" ]; then
	cp "$dir/.env.example" "$dir/.env"
	trap 'rm -f "$dir/.env"' EXIT
fi
docker compose -f "$dir/compose.yaml" config -q
docker compose -f "$dir/compose.yaml" --profile gateway config -q
docker compose -f "$dir/compose.yaml" --profile caddy config -q
echo "compose config ok"
