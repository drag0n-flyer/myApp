#!/usr/bin/env bash
# Tests for check-android-bundle.sh.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if "${@:2}"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails+1)); fi; }

echo 'void f(void){}' > "$tmp/f.c"
gcc -shared -fPIC -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -o "$tmp/good.so" "$tmp/f.c"
gcc -shared -fPIC -Wl,-z,max-page-size=4096 -Wl,-z,common-page-size=4096 -o "$tmp/bad.so" "$tmp/f.c"

# make_bundle NAME LIB ABI...  (a bundle is a zip with base/lib/<abi>/<file>.so)
make_bundle() {
  local name="$1" so="$2"; shift 2
  local d="$tmp/$name.d"; mkdir -p "$d/base/manifest"
  echo m > "$d/base/manifest/AndroidManifest.xml"
  for abi in "$@"; do mkdir -p "$d/base/lib/$abi"; cp "$so" "$d/base/lib/$abi/libmlsffi.so"; done
  (cd "$d" && zip -qr "$tmp/$name.aab" .)
}
run() { "$here/check-android-bundle.sh" "$@" >"$tmp/out" 2>&1; }

make_bundle ok "$tmp/good.so" arm64-v8a armeabi-v7a x86_64
check "exact ABI list passes" run "$tmp/ok.aab"
check "required library present passes" run --require libmlsffi.so "$tmp/ok.aab"
check "missing required library fails" bash -c '! "$1" --require libnope.so "$2" >/dev/null 2>&1' _ "$here/check-android-bundle.sh" "$tmp/ok.aab"
make_bundle extra "$tmp/good.so" arm64-v8a armeabi-v7a x86_64 x86
check "extra x86 ABI fails" bash -c '! "$1" "$2" >/dev/null 2>&1' _ "$here/check-android-bundle.sh" "$tmp/extra.aab"
make_bundle missing "$tmp/good.so" arm64-v8a x86_64
check "missing ABI fails" bash -c '! "$1" "$2" >/dev/null 2>&1' _ "$here/check-android-bundle.sh" "$tmp/missing.aab"
make_bundle bad "$tmp/bad.so" arm64-v8a armeabi-v7a x86_64
check "4 KB arm64 library fails" bash -c '! "$1" "$2" >/dev/null 2>&1' _ "$here/check-android-bundle.sh" "$tmp/bad.aab"
check "no argument is a usage error" bash -c '! "$1" >/dev/null 2>&1' _ "$here/check-android-bundle.sh"

exit "$fails"
