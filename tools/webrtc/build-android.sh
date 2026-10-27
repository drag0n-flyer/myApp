#!/bin/sh
# Builds the Android WebRTC library for one ABI from a fresh checkout. Runs on
# Linux (CI: ubuntu-24.04), the only supported host for Android WebRTC builds.
#
# Usage:
#   sh tools/webrtc/build-android.sh <abi>
#   sh tools/webrtc/build-android.sh --print-gn-args <abi>    # no checkout needed
#
# ABIs: arm64-v8a armeabi-v7a x86_64
#
# <workdir> is $WEBRTC_WORKDIR, or ./webrtc-android when that is not set.
#
# Ruling (design 8.1 names tools_webrtc/android/build_aar.py): this script runs
# gn and ninja itself with the gn arguments build_aar.py uses at the pin, plus
# ours. build_aar.py builds a fat AAR in one go and cannot build one ABI per
# job, and package-android.sh assembles the AAR from the per-ABI outputs
# (reproducibly, with the license check). The libraries are the same.
#
# Steps:
#   1. clone depot_tools into <workdir>
#   2. fetch --nohooks --no-history webrtc_android
#   3. check out WEBRTC_COMMIT (tools/webrtc/VERSION), gclient sync, gclient runhooks
#   4. apply.sh with APPLY_PLATFORM=android (Opus 1.6 and patches 0001, 0002,
#      0005, 0006; the Objective-C patches 0003 and 0004 are not applied)
#   5. gn gen src/out/android/<abi>, then build //sdk/android:libjingle_peerconnection_so
#      and //sdk/android:libwebrtc (the Java classes jar)
#   6. copy the results to <workdir>/out/android/<abi>/ (the plan's out/android/<abi>):
#        libjingle_peerconnection_so.so
#        libwebrtc.jar
#      and, once, to <workdir>/out/android/:
#        AndroidManifest.xml   (sdk/android/AndroidManifest.xml)
#        LICENSE.md            (generate_licenses.py over the ABIs built so far)
#
# Build each ABI in turn with the same <workdir>, then run package-android.sh on
# <workdir>/out/android. LICENSE.md is regenerated after every ABI, so the last
# build's file covers both.
#
# Exit codes: 0 ok, 1 failure or bad usage; apply.sh's codes 2 and 3 pass through.
# Test override (environment): APP_UNAME replaces `uname -s`.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
. "$here/VERSION"

usage() {
  echo "usage: sh tools/webrtc/build-android.sh <abi>   (workdir: \$WEBRTC_WORKDIR, default ./webrtc-android)" >&2
  echo "       sh tools/webrtc/build-android.sh --print-gn-args <abi>" >&2
  echo "abis: arm64-v8a armeabi-v7a x86_64" >&2
  exit 1
}

check_abi() {
  case "$1" in
    arm64-v8a | armeabi-v7a | x86_64) ;;
    *) echo "error: unknown ABI '$1'" >&2; usage ;;
  esac
}

gn_args() { # gn_args <abi>: one line, the gn arguments for the ABI
  # Every ABI. The codec, Opus and symbol arguments are ours (the same as the
  # Apple build); the rest is what tools_webrtc/android/build_aar.py sets at the
  # pin (is_debug, is_component_build, rtc_include_tests, android_static_analysis,
  # use_siso).
  common='target_os="android" is_debug=false is_component_build=false rtc_include_tests=false'
  common="$common android_static_analysis=\"off\" use_siso=true symbol_level=1"
  common="$common rtc_use_h264=false rtc_include_dav1d_in_internal_decoder_factory=false"
  common="$common rtc_opus_support_120ms_ptime=true"
  # CPU features: arm64 gets NEON only. There is no dot-product argument and no
  # arm_version here (a Snapdragon 835 is ARMv8.0 without dot-product). x86_64
  # relies on Opus's runtime-dispatched SIMD.
  case "$1" in
    arm64-v8a) cpu='target_cpu="arm64"' ;;
    # armeabi-v7a: NEON on (every ARMv7 phone we support has it), no dot-product (arm64 only).
    # Opus 1.6 Deep PLC and OSCE are compiled for arm32 with the NEON neural kernels
    # (patch 0001 adds dnn_neon_sources for arm and arm64).
    armeabi-v7a) cpu='target_cpu="arm" arm_use_neon=true' ;;
    x86_64) cpu='target_cpu="x64"' ;;
  esac
  echo "$common $cpu"
}

if [ "${1-}" = "--print-gn-args" ]; then
  [ "$#" -eq 2 ] || usage
  check_abi "$2"
  gn_args "$2"
  exit 0
fi

[ "$#" -eq 1 ] || usage
abi=$1
check_abi "$abi"
[ "${APP_UNAME:-$(uname -s)}" = "Linux" ] || { echo "error: building the Android library needs Linux" >&2; exit 1; }
mkdir -p "${WEBRTC_WORKDIR:-webrtc-android}"
workdir=$(cd "${WEBRTC_WORKDIR:-webrtc-android}" && pwd)

step() { echo "=== $(date -u +%H:%M:%S) $*"; }

# 1. depot_tools
export DEPOT_TOOLS_UPDATE=0
export PATH="$workdir/depot_tools:$PATH"
if [ ! -d "$workdir/depot_tools" ]; then
  step "clone depot_tools"
  git clone --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git "$workdir/depot_tools"
  "$workdir/depot_tools/ensure_bootstrap"
fi

# 2. fetch
cd "$workdir"
if [ ! -d "$workdir/src" ]; then
  step "fetch webrtc_android"
  fetch --nohooks --no-history webrtc_android
fi

# 3. pin, sync, hooks
cd "$workdir/src"
step "check out $WEBRTC_COMMIT"
git fetch --depth 1 origin "$WEBRTC_COMMIT" || git fetch --depth 1 origin "+refs/$WEBRTC_BRANCH:refs/pin"
git checkout --force "$WEBRTC_COMMIT"
[ "$(git rev-parse HEAD)" = "$WEBRTC_COMMIT" ] || { echo "error: HEAD is not $WEBRTC_COMMIT" >&2; exit 1; }
step "gclient sync"
gclient sync --no-history -D --nohooks
step "gclient runhooks"
gclient runhooks
df -h "$workdir" | tail -1

# 4. our patches (a second ABI build in the same workdir prints "already applied")
step "apply.sh (android)"
APPLY_PLATFORM=android sh "$here/apply.sh" "$workdir/src"

# 5. gn and the build
out="out/android/$abi"
step "gn gen $out"
gn gen "$out" --args="$(gn_args "$abi")"
step "build libjingle_peerconnection_so and libwebrtc"
if [ -x third_party/siso/cipd/siso ]; then
  third_party/siso/cipd/siso ninja -k 0 -C "$out" sdk/android:libjingle_peerconnection_so sdk/android:libwebrtc
else
  autoninja -k 0 -C "$out" sdk/android:libjingle_peerconnection_so sdk/android:libwebrtc
fi

# 6. artifacts
step "collect artifacts"
dest="$workdir/out/android/$abi"
rm -rf "$dest"
mkdir -p "$dest"
so="$out/libjingle_peerconnection_so.so"
jar="$out/lib.java/sdk/android/libwebrtc.jar"
[ -f "$so" ] || { echo "error: $so not built" >&2; ls "$out" >&2; exit 1; }
[ -f "$jar" ] || { echo "error: $jar not built" >&2; exit 1; }
cp "$so" "$dest/libjingle_peerconnection_so.so"
cp "$jar" "$dest/libwebrtc.jar"
cp sdk/android/AndroidManifest.xml "$workdir/out/android/AndroidManifest.xml"
# The license covers every ABI built so far in this workdir.
builds=""
for d in "$workdir"/src/out/android/*/; do
  [ -f "${d}libjingle_peerconnection_so.so" ] && builds="$builds ${d%/}"
done
# shellcheck disable=SC2086
python3 tools_webrtc/libs/generate_licenses.py \
  --target //sdk/android:libjingle_peerconnection_so --target //sdk/android:libwebrtc \
  "$workdir/out/android" $builds
[ -f "$workdir/out/android/LICENSE.md" ] || { echo "error: LICENSE.md not generated" >&2; exit 1; }
step "done: $dest"
ls "$dest"
