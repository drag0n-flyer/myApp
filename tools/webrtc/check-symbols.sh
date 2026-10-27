#!/bin/sh
# Fails if a WebRTC framework binary exports symbols it must not.
#
# Usage: sh tools/webrtc/check-symbols.sh <framework-binary>
#
# Lists the exported symbols (macOS: nm -gU; Linux, for the tests: nm -D
# --defined-only, adding the leading underscore Mach-O names have) and exits 5,
# printing the offending names, if
#   - a name from reserved-symbols.txt is exported, or
#   - an unprefixed plain C symbol is exported that matches none of the
#     allowed prefixes: _RTC* _OBJC_* _kRTC* __Z* _WebRTC*
#
# Environment (tests only): RESERVED_SYMBOLS_FILE.
# Exit codes: 0 ok, 1 usage or tool error, 5 forbidden symbol exported.
set -eu

here=$(cd "$(dirname "$0")" && pwd)

if [ "$#" -ne 1 ]; then
  echo "usage: sh tools/webrtc/check-symbols.sh <framework-binary>" >&2
  exit 1
fi
bin=$1
[ -f "$bin" ] || { echo "error: $bin not found" >&2; exit 1; }
reserved=${RESERVED_SYMBOLS_FILE:-$here/reserved-symbols.txt}
[ -f "$reserved" ] || { echo "error: $reserved not found" >&2; exit 1; }

work=$(mktemp -d "${TMPDIR:-/tmp}/check-symbols.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM

if [ "$(uname -s)" = "Darwin" ]; then
  nm -gU "$bin" >"$work/nm.txt" || { echo "error: nm failed on $bin" >&2; exit 1; }
  prefix=""
else
  nm -D --defined-only "$bin" >"$work/nm.txt" || { echo "error: nm failed on $bin" >&2; exit 1; }
  prefix="_"
fi

# Lines look like "<addr> <type> <name>". Fat-file header lines and blanks have
# a different shape and are skipped.
awk -v p="$prefix" 'NF == 3 && $2 ~ /^[A-Za-z]$/ { n = $3; sub(/@.*/, "", n); print p n }' \
  "$work/nm.txt" | sort -u >"$work/exported.txt"

grep -v '^[[:space:]]*#' "$reserved" | grep -v '^[[:space:]]*$' >"$work/reserved.txt" || true

: >"$work/bad.txt"
# 1. Reserved names.
grep -Fx -f "$work/reserved.txt" "$work/exported.txt" >>"$work/bad.txt" || true
# 2. Unprefixed plain C symbols.
while IFS= read -r name; do
  case "$name" in
    _RTC* | _OBJC_* | _kRTC* | __Z* | _WebRTC* | __mh_*) ;;
    # Linker-provided names a Linux shared object always exports (tests only).
    __init | __fini | ___bss_start | __edata | __end) [ "$prefix" = "_" ] || echo "$name" >>"$work/bad.txt" ;;
    *) echo "$name" >>"$work/bad.txt" ;;
  esac
done <"$work/exported.txt"

if [ -s "$work/bad.txt" ]; then
  echo "error: $bin exports symbols it must not:" >&2
  sort -u "$work/bad.txt" >&2
  exit 5
fi
echo "ok: $bin exports no forbidden symbols ($(wc -l <"$work/exported.txt" | tr -d ' ') exported)"
