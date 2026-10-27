#!/usr/bin/env bash
# Installs the Android toolchain under ~/android (no sudo, nothing system-wide).
#   scripts/android-setup.sh                   JDK 17, SDK, NDK, cargo-ndk, Rust targets
#   scripts/android-setup.sh --with-emulator   also the emulator and system images (CI)
#   scripts/android-setup.sh --dry-run         print what would be installed, change nothing
# On success it writes ~/android/env.sh and ~/android/.setup-complete.
set -euo pipefail

ANDROID_ROOT="${ANDROID_ROOT:-$HOME/android}"
JDK_VERSION="17.0.21+9"
JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz"
JDK_SHA256="8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip"
CMDLINE_TOOLS_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
PLATFORM="platforms;android-36"
BUILD_TOOLS="build-tools;36.0.0"
NDK="ndk;28.2.13676358"
EMULATOR_PACKAGES=("emulator" "system-images;android-29;default;x86_64" "system-images;android-36;aosp_atd;x86_64")
RUST_TARGETS=(aarch64-linux-android x86_64-linux-android)

DRY_RUN=0
WITH_EMULATOR=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --with-emulator) WITH_EMULATOR=1 ;;
    *) echo "usage: $0 [--dry-run] [--with-emulator]" >&2; exit 2 ;;
  esac
done

SDK_PACKAGES=("platform-tools" "$PLATFORM" "$BUILD_TOOLS" "$NDK")
if [ "$WITH_EMULATOR" = 1 ]; then SDK_PACKAGES+=("${EMULATOR_PACKAGES[@]}"); fi

if [ "$DRY_RUN" = 1 ]; then
  echo "install root: $ANDROID_ROOT"
  echo "jdk: Temurin $JDK_VERSION ($JDK_URL, sha256 $JDK_SHA256)"
  echo "cmdline-tools: $CMDLINE_TOOLS_URL (sha256 $CMDLINE_TOOLS_SHA256)"
  for p in "${SDK_PACKAGES[@]}"; do echo "sdk package: $p"; done
  if [ "$WITH_EMULATOR" = 0 ]; then
    for p in "${EMULATOR_PACKAGES[@]}"; do echo "skipped (needs --with-emulator): $p"; done
  fi
  echo "cargo: cargo install --locked cargo-ndk"
  for t in "${RUST_TARGETS[@]}"; do echo "rust target: $t"; done
  echo "licenses: accepted non-interactively"
  exit 0
fi

rm -f "$ANDROID_ROOT/.setup-complete"
mkdir -p "$ANDROID_ROOT/downloads"
cd "$ANDROID_ROOT/downloads"

fetch_verified() { # url sha256 file; resumes partial downloads
  if ! { [ -f "$3" ] && echo "$2  $3" | sha256sum -c --status; }; then
    local try
    for try in 1 2 3 4 5; do
      curl -fL --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 20 \
        --speed-limit 20000 --speed-time 30 -C - -o "$3" "$1" && break
    done
  fi
  if ! echo "$2  $3" | sha256sum -c -; then rm -f "$3"; echo "checksum mismatch for $3" >&2; exit 1; fi
}

JAVA_HOME="$ANDROID_ROOT/jdk-17"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  fetch_verified "$JDK_URL" "$JDK_SHA256" jdk17.tar.gz
  rm -rf "$ANDROID_ROOT/jdk-17.tmp"; mkdir "$ANDROID_ROOT/jdk-17.tmp"
  tar -xzf jdk17.tar.gz -C "$ANDROID_ROOT/jdk-17.tmp" --strip-components=1
  mv "$ANDROID_ROOT/jdk-17.tmp" "$JAVA_HOME"
fi
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

ANDROID_HOME="$ANDROID_ROOT/sdk"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  fetch_verified "$CMDLINE_TOOLS_URL" "$CMDLINE_TOOLS_SHA256" cmdline-tools.zip
  rm -rf "$ANDROID_ROOT/ct.tmp"; mkdir -p "$ANDROID_ROOT/ct.tmp" "$ANDROID_HOME/cmdline-tools"
  unzip -q cmdline-tools.zip -d "$ANDROID_ROOT/ct.tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$ANDROID_ROOT/ct.tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$ANDROID_ROOT/ct.tmp"
fi

set +o pipefail
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
set -o pipefail
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install "${SDK_PACKAGES[@]}"

cargo install --locked cargo-ndk
rustup target add "${RUST_TARGETS[@]}"

cat > "$ANDROID_ROOT/env.sh" <<ENV
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/${NDK#ndk;}"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$HOME/.cargo/bin:\$PATH"
ENV
cat "$ANDROID_ROOT/env.sh"
cd "$ANDROID_ROOT" && rm -rf "$ANDROID_ROOT/downloads"
touch "$ANDROID_ROOT/.setup-complete"
echo "Done. Add to your shell: source $ANDROID_ROOT/env.sh"
