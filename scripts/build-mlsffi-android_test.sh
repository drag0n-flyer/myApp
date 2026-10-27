#!/usr/bin/env bash
# Test for build-mlsffi-android.sh --host-only: generated Kotlin exists, has the right package,
# exports the mlsffi API, and a second run changes nothing (drift check).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KT="$ROOT/apps/android/mls/src/main/kotlin/org/example/app/mls/ffi/mlsffi.kt"
# Full NDK build when ANDROID_NDK_HOME is set, else --host-only.
MODE="--host-only"; [ -n "${ANDROID_NDK_HOME:-}" ] && MODE=""
fail() { echo "FAIL: $*" >&2; exit 1; }

rm -f "$KT"
"$ROOT/scripts/build-mlsffi-android.sh" $MODE
[ -f "$KT" ] || fail "missing $KT"
grep -q '^package org.example.app.mls.ffi$' "$KT" || fail "wrong package"
grep -q 'fun `generateSignatureKeypair`(' "$KT" || fail "missing generateSignatureKeypair"
grep -q 'fun `hpkeSeal`(' "$KT" || fail "missing hpkeSeal"

before="$(sha256sum "$KT")"
"$ROOT/scripts/build-mlsffi-android.sh" $MODE
[ "$before" = "$(sha256sum "$KT")" ] || fail "second run changed the bindings"
if git -C "$ROOT" ls-files --error-unmatch "$KT" >/dev/null 2>&1; then
    git -C "$ROOT" diff --exit-code -- "$KT" || fail "generated bindings drift from committed file"
fi
for abi in arm64-v8a x86_64; do
    [ -z "$MODE" ] && { [ -f "$ROOT/apps/android/mls/src/main/jniLibs/$abi/libmlsffi.so" ] || fail "missing $abi .so"; }
done
echo "PASS${MODE:+ (host-only)}"
