#!/bin/sh
# Downloads the pinned RNNoise model and extracts rnnoise_data.c into Sources/CRNNoise/.
set -e
cd "$(dirname "$0")/.."
HASH=0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37
TAR=$(mktemp -d)/rnnoise_data-$HASH.tar.gz
curl -fsSL -o "$TAR" "https://media.xiph.org/rnnoise/models/rnnoise_data-$HASH.tar.gz"
if command -v sha256sum >/dev/null; then GOT=$(sha256sum "$TAR" | awk '{print $1}'); else GOT=$(shasum -a 256 "$TAR" | awk '{print $1}'); fi
[ "$GOT" = "$HASH" ] || { echo "checksum mismatch: $GOT" >&2; exit 1; }
tar -xzOf "$TAR" src/rnnoise_data.c > Sources/CRNNoise/rnnoise_data.c
echo "ok: Sources/CRNNoise/rnnoise_data.c"
