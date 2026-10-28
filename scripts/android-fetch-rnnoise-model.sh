#!/bin/sh
# Downloads the pinned RNNoise model (the same one as iPhone's
# apps/apple/Packages/CallMedia/scripts/fetch-rnnoise-model.sh) and extracts rnnoise_data.c
# into the Android calls module. See calls-android/src/main/cpp/rnnoise/VENDORED.md.
set -e
cd "$(dirname "$0")/.."
OUT=apps/android/calls-android/src/main/cpp/rnnoise/rnnoise_data.c
HASH=0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37
TAR=$(mktemp -d)/rnnoise_data-$HASH.tar.gz
curl -fsSL -o "$TAR" "https://media.xiph.org/rnnoise/models/rnnoise_data-$HASH.tar.gz"
if command -v sha256sum >/dev/null; then GOT=$(sha256sum "$TAR" | awk '{print $1}'); else GOT=$(shasum -a 256 "$TAR" | awk '{print $1}'); fi
[ "$GOT" = "$HASH" ] || { echo "checksum mismatch: $GOT" >&2; exit 1; }
# Extract next to the target, then move into place only when the extraction succeeded.
TMP="$OUT.tmp.$$"
trap 'rm -f "$TMP"' EXIT
tar -xzOf "$TAR" src/rnnoise_data.c > "$TMP"
mv "$TMP" "$OUT"
echo "ok: $OUT"
