#!/bin/sh
# Turns the slice builds into the release files. Runs on macOS (CI).
#
# Usage:
#   sh tools/webrtc/package.sh <artifacts-dir> <out-dir>
#   sh tools/webrtc/package.sh --check-license <LICENSE.md>   # no macOS needed
#
# <artifacts-dir> holds one directory per slice (the output of build.sh:
# <slice>/WebRTC.framework and <slice>/WebRTC.dSYM) and licenses/LICENSE.md
# (from WebRTC's generate_licenses.py). <out-dir> gets:
#   WebRTC.xcframework.zip  (ditto -c -k --sequesterRsrc --keepParent)
#   WebRTC.dSYMs.zip
#   LICENSE.md
#   checksum.txt            (swift package compute-checksum)
#   VERSION                 (a copy of tools/webrtc/VERSION)
#
# XCFramework: ios-arm64 (device), ios-sim-arm64 + ios-sim-x64 (lipo), mac-arm64
# + mac-x64 (lipo). It then runs check-symbols.sh on every slice's binary.
#
# Exit codes: 0 ok, 1 failure or bad usage, 4 LICENSE.md names a GPL, LGPL or
# AGPL license, 5 check-symbols.sh found a forbidden symbol.
set -eu

here=$(cd "$(dirname "$0")" && pwd)

usage() {
  echo "usage: sh tools/webrtc/package.sh <artifacts-dir> <out-dir>" >&2
  echo "       sh tools/webrtc/package.sh --check-license <LICENSE.md>" >&2
  exit 1
}

check_license() { # check_license <file>: exit 4 on a copyleft license
  [ -f "$1" ] || { echo "error: $1 not found" >&2; exit 1; }
  if grep -E -n 'GNU General Public License|GNU Lesser General Public License|GNU Affero' "$1" >&2; then
    echo "error: $1 names a GPL, LGPL or AGPL license; the release must not ship it" >&2
    exit 4
  fi
}

if [ "${1-}" = "--check-license" ]; then
  [ "$#" -eq 2 ] || usage
  check_license "$2"
  echo "ok: $2 has no GPL, LGPL or AGPL text"
  exit 0
fi

[ "$#" -eq 2 ] || usage
[ -d "$1" ] || { echo "error: $1 is not a directory" >&2; exit 1; }
artifacts=$(cd "$1" && pwd)
mkdir -p "$2"
out=$(cd "$2" && pwd)
[ "$(uname -s)" = "Darwin" ] || { echo "error: packaging needs macOS" >&2; exit 1; }

slices="ios-arm64 ios-sim-arm64 ios-sim-x64 mac-arm64 mac-x64"
for s in $slices; do
  [ -d "$artifacts/$s/WebRTC.framework" ] || { echo "error: $artifacts/$s/WebRTC.framework missing" >&2; exit 1; }
  [ -d "$artifacts/$s/WebRTC.dSYM" ] || { echo "error: $artifacts/$s/WebRTC.dSYM missing" >&2; exit 1; }
done

# 1. License: fail before any packaging work.
license="$artifacts/licenses/LICENSE.md"
check_license "$license"

fw_binary() { # fw_binary <WebRTC.framework>: the Mach-O file
  if [ -f "$1/WebRTC" ]; then echo "$1/WebRTC"; else echo "$1/Versions/A/WebRTC"; fi
}

work=$(mktemp -d "${TMPDIR:-/tmp}/webrtc-package.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM

# 2. Combine the simulator and macOS slices (the framework from the arm64 build,
# with a universal binary).
merge() { # merge <name> <arm64-slice> <x64-slice>
  mkdir -p "$work/$1"
  ditto "$artifacts/$2/WebRTC.framework" "$work/$1/WebRTC.framework"
  lipo -create "$(fw_binary "$artifacts/$2/WebRTC.framework")" "$(fw_binary "$artifacts/$3/WebRTC.framework")" \
    -output "$work/lipo.bin"
  cp "$work/lipo.bin" "$(fw_binary "$work/$1/WebRTC.framework")"
  rm "$work/lipo.bin"
}
merge sim ios-sim-arm64 ios-sim-x64
merge mac mac-arm64 mac-x64

# 3. XCFramework.
rm -rf "$work/WebRTC.xcframework"
xcodebuild -create-xcframework \
  -framework "$artifacts/ios-arm64/WebRTC.framework" \
  -framework "$work/sim/WebRTC.framework" \
  -framework "$work/mac/WebRTC.framework" \
  -output "$work/WebRTC.xcframework"

# 4. Symbols, on every slice of the finished XCFramework.
for fw in "$work"/WebRTC.xcframework/*/WebRTC.framework; do
  echo "check-symbols: $fw"
  sh "$here/check-symbols.sh" "$(fw_binary "$fw")"
done

# 5. Zips and the rest.
rm -f "$out/WebRTC.xcframework.zip" "$out/WebRTC.dSYMs.zip"
ditto -c -k --sequesterRsrc --keepParent "$work/WebRTC.xcframework" "$out/WebRTC.xcframework.zip"
mkdir -p "$work/dSYMs"
for s in $slices; do
  mkdir -p "$work/dSYMs/$s"
  ditto "$artifacts/$s/WebRTC.dSYM" "$work/dSYMs/$s/WebRTC.dSYM"
done
ditto -c -k --sequesterRsrc --keepParent "$work/dSYMs" "$out/WebRTC.dSYMs.zip"
cp "$license" "$out/LICENSE.md"
cp "$here/VERSION" "$out/VERSION"
swift package compute-checksum "$out/WebRTC.xcframework.zip" >"$out/checksum.txt"
echo "checksum: $(cat "$out/checksum.txt")"
ls -l "$out"
