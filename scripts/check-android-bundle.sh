#!/usr/bin/env bash
# Checks a release .aab: its native ABIs are exactly arm64-v8a, armeabi-v7a and x86_64, every
# 64-bit .so is 16 KB aligned (check-16kb.sh skips armeabi-v7a), and each --require library is
# present for every ABI.
# Usage: scripts/check-android-bundle.sh [--require libname.so]... <bundle.aab>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
required=()
while [ "$#" -gt 1 ]; do
  case "$1" in
    --require) required+=("$2"); shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ "$#" -eq 1 ] || { echo "usage: $0 [--require libname.so]... <bundle.aab>" >&2; exit 2; }
bundle="$1"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
unzip -q "$bundle" 'base/lib/*' -d "$tmp" 2>/dev/null || true
abis="$(ls "$tmp/base/lib" 2>/dev/null | sort | tr '\n' ' ')"
want="arm64-v8a armeabi-v7a x86_64 "
if [ "$abis" != "$want" ]; then
  echo "FAIL ABI list is '${abis% }', expected '${want% }'" >&2
  exit 1
fi
echo "ok   ABIs: ${abis% }"
for abi in $abis; do
  for lib in "${required[@]+"${required[@]}"}"; do
    [ -f "$tmp/base/lib/$abi/$lib" ] || { echo "FAIL $abi has no $lib" >&2; exit 1; }
  done
done
"$here/check-16kb.sh" "$tmp/base/lib"
