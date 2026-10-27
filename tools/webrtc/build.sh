#!/bin/sh
# Builds one WebRTC.framework slice from a fresh checkout. Runs on macOS (CI).
#
# Usage:
#   sh tools/webrtc/build.sh <slice> <workdir>
#   sh tools/webrtc/build.sh --print-gn-args <slice>    # no macOS needed
#   sh tools/webrtc/build.sh --print-target <slice>     # no macOS needed
#
# Slices: ios-arm64 ios-sim-arm64 ios-sim-x64 mac-arm64 mac-x64
#
# Steps:
#   1. clone depot_tools into <workdir>
#   2. fetch --nohooks --no-history webrtc_ios
#   3. check out WEBRTC_COMMIT (tools/webrtc/VERSION), gclient sync, gclient runhooks
#   4. apply.sh (Opus 1.6 and our patches)
#   5. gn gen out/<slice>, then build the target
#   6. copy WebRTC.framework and WebRTC.dSYM to <workdir>/artifacts/<slice>/
#
# Exit codes: 0 ok, 1 failure or bad usage; apply.sh's codes 2 and 3 pass through.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
. "$here/VERSION"

usage() {
  echo "usage: sh tools/webrtc/build.sh <slice> <workdir>" >&2
  echo "slices: ios-arm64 ios-sim-arm64 ios-sim-x64 mac-arm64 mac-x64" >&2
  exit 1
}

check_slice() {
  case "$1" in
    ios-arm64 | ios-sim-arm64 | ios-sim-x64 | mac-arm64 | mac-x64) ;;
    *) echo "error: unknown slice '$1'" >&2; usage ;;
  esac
}

gn_target() { # gn_target <slice>
  case "$1" in
    ios-*) echo "//sdk:framework_objc" ;;
    mac-*) echo "//sdk:mac_framework_objc" ;;
  esac
}

gn_args() { # gn_args <slice>: one line, the gn arguments for the slice
  slice=$1
  # Every slice. The Opus, codec and symbol arguments are ours.
  common='is_debug=false is_component_build=false rtc_include_tests=false rtc_build_examples=false'
  common="$common rtc_enable_protobuf=false rtc_use_h264=false"
  common="$common rtc_include_dav1d_in_internal_decoder_factory=false"
  common="$common rtc_opus_support_120ms_ptime=true enable_dsyms=true enable_stripping=true"
  # Copied from tools_webrtc/ios/build_ios_libs.py at the pin (BuildWebRTC):
  # lld, siso and Objective-C symbol export. The iOS script also sets
  # rtc_libvpx_build_vp9 (its default is kept here) and is_debug/is_component_build
  # (above).
  common="$common use_lld=true rtc_enable_objc_symbol_export=true use_siso=true"
  # RTCOpusInfo reports the Opus PLC field trial name (patch 0003).
  common="$common app_opus_plc_trial=\"$FIELD_TRIAL_OPUS_PLC\""
  case "$slice" in
    ios-arm64) plat='target_os="ios" ios_deployment_target="17.0" ios_enable_code_signing=false target_environment="device" target_cpu="arm64"' ;;
    ios-sim-arm64) plat='target_os="ios" ios_deployment_target="17.0" ios_enable_code_signing=false target_environment="simulator" target_cpu="arm64"' ;;
    ios-sim-x64) plat='target_os="ios" ios_deployment_target="17.0" ios_enable_code_signing=false target_environment="simulator" target_cpu="x64"' ;;
    mac-arm64) plat='target_os="mac" mac_deployment_target="14.0" target_cpu="arm64"' ;;
    mac-x64) plat='target_os="mac" mac_deployment_target="14.0" target_cpu="x64"' ;;
  esac
  echo "$common $plat"
}

if [ "${1-}" = "--print-gn-args" ] || [ "${1-}" = "--print-target" ]; then
  mode=$1
  [ "$#" -eq 2 ] || usage
  check_slice "$2"
  if [ "$mode" = "--print-gn-args" ]; then gn_args "$2"; else gn_target "$2"; fi
  exit 0
fi

[ "$#" -eq 2 ] || usage
slice=$1
check_slice "$slice"
mkdir -p "$2"
workdir=$(cd "$2" && pwd)
[ "$(uname -s)" = "Darwin" ] || { echo "error: building needs macOS" >&2; exit 1; }

step() { echo "=== $(date -u +%H:%M:%S) $*"; }

# 1. depot_tools
export DEPOT_TOOLS_UPDATE=0
export PATH="$workdir/depot_tools:$PATH"
if [ ! -d "$workdir/depot_tools" ]; then
  step "clone depot_tools"
  git clone --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git "$workdir/depot_tools"
fi

# 2. fetch
cd "$workdir"
if [ ! -d "$workdir/src" ]; then
  step "fetch webrtc_ios"
  fetch --nohooks --no-history webrtc_ios
fi
case "$slice" in
  mac-*) # The macOS framework needs the mac dependencies as well.
    sed -i "" "s/^target_os = .*/target_os = [\"ios\", \"mac\"]/" .gclient
    grep target_os .gclient ;;
esac

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

# 4. our patches
step "apply.sh"
sh "$here/apply.sh" "$workdir/src"

# 5. gn and the build
out="out/$slice"
target=$(gn_target "$slice")
step "gn gen $out"
gn gen "$out" --args="$(gn_args "$slice")"
step "build $target"
if [ -x third_party/siso/cipd/siso ]; then
  third_party/siso/cipd/siso ninja -C "$out" "${target#//sdk:}" || third_party/siso/cipd/siso ninja -C "$out" "$target"
else
  autoninja -C "$out" "$target"
fi

# 6. artifacts
step "collect artifacts"
dest="$workdir/artifacts/$slice"
rm -rf "$dest"
mkdir -p "$dest"
[ -d "$out/WebRTC.framework" ] || { echo "error: $out/WebRTC.framework not built" >&2; ls "$out" >&2; exit 1; }
cp -R "$out/WebRTC.framework" "$dest/"
[ -d "$out/WebRTC.dSYM" ] || { echo "error: $out/WebRTC.dSYM not built (enable_dsyms)" >&2; exit 1; }
cp -R "$out/WebRTC.dSYM" "$dest/"
step "done: $dest"
ls "$dest"
