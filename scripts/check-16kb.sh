#!/usr/bin/env bash
# Fails if any LOAD segment of a .so is aligned below 16 KB (0x4000).
# Usage: scripts/check-16kb.sh <file.so | directory>...
set -euo pipefail
[ "$#" -ge 1 ] || { echo "usage: $0 <file.so|dir>..." >&2; exit 2; }
files=()
for p in "$@"; do
  if [ -d "$p" ]; then
    while IFS= read -r f; do files+=("$f"); done < <(find "$p" -name '*.so' -type f | sort)
  else
    files+=("$p")
  fi
done
[ "${#files[@]}" -gt 0 ] || { echo "no .so files found" >&2; exit 2; }
bad=0
for f in "${files[@]}"; do
  min=""
  while read -r align; do
    v=$((align))
    if [ -z "$min" ] || [ "$v" -lt "$min" ]; then min=$v; fi
  done < <(readelf -lW "$f" | awk '$1 == "LOAD" { print $NF }')
  if [ -z "$min" ]; then echo "FAIL $f: no LOAD segments"; bad=1
  elif [ "$min" -lt 16384 ]; then echo "FAIL $f: LOAD alignment $min < 16384"; bad=1
  else echo "ok   $f: LOAD alignment $min"; fi
done
exit "$bad"
