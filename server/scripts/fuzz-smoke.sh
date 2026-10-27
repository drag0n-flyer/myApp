#!/usr/bin/env bash
# Runs every Fuzz* target for 15 seconds each. Run from server/.
set -euo pipefail

cd "$(dirname "$0")/.."

go list ./... | while read -r pkg; do
  targets=$(go test -list '^Fuzz' "$pkg" | grep '^Fuzz' || true)
  for name in $targets; do
    echo "fuzz: $pkg $name"
    go test -run='^$' -fuzz="^$name\$" -fuzztime=15s "$pkg"
  done
done
