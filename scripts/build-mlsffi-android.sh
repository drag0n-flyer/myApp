#!/usr/bin/env bash
# Cross-builds the Rust mlsffi crate for Android (arm64-v8a, armeabi-v7a, x86_64) and regenerates the
# Kotlin bindings. Usage: build-mlsffi-android.sh [--host-only]
#   --host-only  skip the NDK build; build the host libmlsffi.so (for JVM tests) and bindings only.
# The NDK build needs ANDROID_NDK_HOME (see scripts/android-setup.sh) and cargo-ndk.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE="$ROOT/apps/apple/Packages/SecureMessaging/mlsffi"
MLS="$ROOT/apps/android/mls"
KT_REL="org/example/app/mls/ffi/mlsffi.kt"

host_only=0
case "${1:-}" in
    "") ;;
    --host-only) host_only=1 ;;
    *) echo "usage: $0 [--host-only]" >&2; exit 2 ;;
esac

# Every .so must have 16 KB aligned LOAD segments (Android 15+ page size).
check_16kb() {
    "$ROOT/scripts/check-16kb.sh" "$@"
}

cd "$CRATE"
built=()

if [ "$host_only" -eq 0 ]; then
    : "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is not set (source scripts/android-setup.sh output)}"
    rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
    cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 --platform 29 \
        -o "$MLS/src/main/jniLibs" build --release --lib --locked
    for abi in arm64-v8a armeabi-v7a x86_64; do
        built+=("$MLS/src/main/jniLibs/$abi/libmlsffi.so")
    done
fi

cargo build --release --lib --locked
echo "HOST_LIB=$CRATE/target/release/libmlsffi.so"

# --no-format: the output must not depend on installed formatters (drift check is byte for byte).
rm -rf build/kotlin
cargo run --release --locked --bin uniffi-bindgen -- generate \
    --library target/release/libmlsffi.so --language kotlin --no-format \
    --config "$MLS/uniffi.toml" --out-dir build/kotlin

mkdir -p "$MLS/src/main/kotlin/$(dirname "$KT_REL")"
cp "build/kotlin/$KT_REL" "$MLS/src/main/kotlin/$KT_REL"

if [ "${#built[@]}" -gt 0 ]; then
    check_16kb "${built[@]}"
fi
