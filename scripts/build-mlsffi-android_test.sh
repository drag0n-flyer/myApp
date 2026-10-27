#!/usr/bin/env bash
# Test for build-mlsffi-android.sh --host-only: generated Kotlin exists, has the right package,
# exports the mlsffi API, and a second run changes nothing (drift check).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KT="$ROOT/apps/android/mls/src/main/kotlin/org/example/app/mls/ffi/mlsffi.kt"
fail() { echo "FAIL: $*" >&2; exit 1; }

rm -f "$KT"
"$ROOT/scripts/build-mlsffi-android.sh" --host-only
[ -f "$KT" ] || fail "missing $KT"
grep -q '^package org.example.app.mls.ffi$' "$KT" || fail "wrong package"
grep -q 'fun `generateSignatureKeypair`(' "$KT" || fail "missing generateSignatureKeypair"
grep -q 'fun `hpkeSeal`(' "$KT" || fail "missing hpkeSeal"

before="$(sha256sum "$KT")"
"$ROOT/scripts/build-mlsffi-android.sh" --host-only
[ "$before" = "$(sha256sum "$KT")" ] || fail "second run changed the bindings"
if git -C "$ROOT" ls-files --error-unmatch "$KT" >/dev/null 2>&1; then
    git -C "$ROOT" diff --exit-code -- "$KT" || fail "generated bindings drift from committed file"
fi
echo "PASS"
