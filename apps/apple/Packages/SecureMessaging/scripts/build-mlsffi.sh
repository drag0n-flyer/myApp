#!/usr/bin/env bash
# Builds Artifacts/MLSFFI.xcframework (ios-arm64, ios-arm64-simulator, macos-arm64_x86_64)
# and regenerates Sources/MLSFFIBindings/mlsffi.swift. Needs macOS with Xcode and rustup.
set -euo pipefail

PKG="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE="$PKG/mlsffi"
OUT="$PKG/Artifacts"
export IPHONEOS_DEPLOYMENT_TARGET=17.0
export MACOSX_DEPLOYMENT_TARGET=14.0

rustup target add aarch64-apple-ios aarch64-apple-ios-sim aarch64-apple-darwin x86_64-apple-darwin

cd "$CRATE"
for target in aarch64-apple-ios aarch64-apple-ios-sim aarch64-apple-darwin x86_64-apple-darwin; do
    echo "== cargo build $target"
    cargo build --release --lib --target "$target"
done

rm -rf build "$OUT"
mkdir -p build/macos build/headers "$OUT"
lipo -create \
    target/aarch64-apple-darwin/release/libmlsffi.a \
    target/x86_64-apple-darwin/release/libmlsffi.a \
    -output build/macos/libmlsffi.a

# --no-format: the output must not depend on whether swift-format is installed
# (the binding-drift check compares it byte for byte with the committed file).
cargo run --release --bin uniffi-bindgen -- generate \
    --library target/aarch64-apple-darwin/release/libmlsffi.dylib \
    --language swift --no-format --out-dir build/bindings

mkdir -p "$PKG/Sources/MLSFFIBindings"
cp build/bindings/mlsffi.swift "$PKG/Sources/MLSFFIBindings/mlsffi.swift"
# Headers dir: only the C header and the modulemap (module mlsffiFFI).
cp build/bindings/mlsffiFFI.h build/headers/mlsffiFFI.h
cp build/bindings/mlsffiFFI.modulemap build/headers/module.modulemap

xcodebuild -create-xcframework \
    -library target/aarch64-apple-ios/release/libmlsffi.a -headers build/headers \
    -library target/aarch64-apple-ios-sim/release/libmlsffi.a -headers build/headers \
    -library build/macos/libmlsffi.a -headers build/headers \
    -output "$OUT/MLSFFI.xcframework"

# The CryptoKit Swift bridge must be inside the static library.
symbols="$(nm -g "$OUT/MLSFFI.xcframework/ios-arm64/libmlsffi.a" 2>/dev/null || true)"
if ! grep -q _signature_key_generate <<<"$symbols"; then
    echo "error: CryptoKit bridge symbol _signature_key_generate missing from ios-arm64 libmlsffi.a" >&2
    exit 1
fi

echo "XCFramework slices:"
plutil -p "$OUT/MLSFFI.xcframework/Info.plist" | grep -E 'LibraryIdentifier|AvailableLibraries'

(cd "$OUT" && rm -f MLSFFI.xcframework.zip && ditto -c -k --sequesterRsrc --keepParent MLSFFI.xcframework MLSFFI.xcframework.zip)
shasum -a 256 "$OUT/MLSFFI.xcframework.zip"
