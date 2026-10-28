#!/usr/bin/env bash
# Builds the Swift interop peer (scripts/interop.sh) and prints the path of the executable on stdout.
#   $1  the directory with the host build of libmlsffi (Linux only; macOS uses the XCFramework)
# INTEROP_SWIFT_SCRATCH overrides the build directory (default .build).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SM="$HERE/../Packages/SecureMessaging"
SCRATCH="${INTEROP_SWIFT_SCRATCH:-$HERE/.build}"

if [ "$(uname)" = Darwin ]; then
  # The real engine needs Artifacts/MLSFFI.xcframework (built from the mlsffi crate, as in mls.yml).
  if [ ! -d "$SM/Artifacts/MLSFFI.xcframework" ]; then "$SM/scripts/build-mlsffi.sh" >&2; fi
  swift build --package-path "$HERE" --scratch-path "$SCRATCH" >&2
  echo "$(swift build --package-path "$HERE" --scratch-path "$SCRATCH" --show-bin-path)/interop-peer"
  exit 0
fi

# Linux: the C module of the bindings is generated from the host library, and SecureMessaging's
# LiveMLSEngine.swift is compiled here against this package's copy of the bindings (module InteropMLSFFI).
LIB_DIR="${1:?usage: build.sh <directory of the host libmlsffi>}"
GEN="$SCRATCH/mlsffi-gen"
mkdir -p "$GEN"
(cd "$SM/mlsffi" && cargo run --release --quiet --bin uniffi-bindgen -- generate \
  --library "$LIB_DIR/libmlsffi.so" --language swift --no-format --out-dir "$GEN" >&2)
cmp -s "$GEN/mlsffi.swift" "$SM/Sources/MLSFFIBindings/mlsffi.swift" \
  || { echo "the committed Swift bindings do not match the library" >&2; exit 1; }
# The generated module map names no library: link libmlsffi through it.
sed 's/^}/    link "mlsffi"\n}/' "$GEN/mlsffiFFI.modulemap" >"$GEN/module.modulemap"
mkdir -p "$HERE/Linux/InteropLinuxMLS/Generated"
sed 's/MLSFFIBindings/InteropMLSFFI/g' "$SM/Sources/SecureMessaging/LiveMLSEngine.swift" \
  >"$HERE/Linux/InteropLinuxMLS/Generated/LiveMLSEngine.swift"
swift build --package-path "$HERE" --scratch-path "$SCRATCH" \
  -Xswiftc -I -Xswiftc "$GEN" -Xlinker -L"$LIB_DIR" -Xlinker -rpath -Xlinker "$LIB_DIR" >&2
echo "$(swift build --package-path "$HERE" --scratch-path "$SCRATCH" --show-bin-path)/interop-peer"
