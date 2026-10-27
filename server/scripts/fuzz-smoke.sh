#!/usr/bin/env bash
# Runs every Fuzz* target for 15 seconds each. Run from server/.
set -euo pipefail

cd "$(dirname "$0")/.."

go list ./... | while read -r pkg; do
  # A failing `go test -list` (build error, bad package) must fail the script,
  # so run it on its own: only the grep may come up empty.
  listing=$(go test -list '^Fuzz' "$pkg")
  targets=$(printf '%s\n' "$listing" | grep '^Fuzz' || true)
  for name in $targets; do
    echo "fuzz: $pkg $name"
    go test -run='^$' -fuzz="^$name\$" -fuzztime=15s "$pkg"
  done
done
