#!/bin/sh
# Linux tests for check-package-pin.sh. They use local fixture files in place of
# the GitHub release (PIN_DOWNLOAD_DIR), so they need no network.
# Run: sh tools/webrtc/check-package-pin_test.sh
set -u

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d "${TMPDIR:-/tmp}/pin-test.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM
failures=0

expect_exit() { # expect_exit <description> <code> <command...>
  desc=$1
  want=$2
  shift 2
  "$@" >"$work/out.txt" 2>&1
  got=$?
  if [ "$got" -eq "$want" ]; then
    echo "ok   $desc"
  else
    echo "FAIL $desc: exit $got, wanted $want"
    sed 's/^/     /' "$work/out.txt"
    failures=$((failures + 1))
  fi
}
expect_output() { # expect_output <description> <text>: grep the last output
  if grep -q -- "$2" "$work/out.txt"; then
    echo "ok   $1"
  else
    echo "FAIL $1: output lacks '$2'"
    sed 's/^/     /' "$work/out.txt"
    failures=$((failures + 1))
  fi
}

sha256() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    sha256sum "$1" | cut -d' ' -f1
  fi
}

# Fixtures: a fake release asset and the release's VERSION.
mkdir -p "$work/release"
printf 'not really a zip\n' >"$work/release/WebRTC.xcframework.zip"
cp "$here/VERSION" "$work/release/VERSION"
real_sum=$(sha256 "$work/release/WebRTC.xcframework.zip")
url=https://github.com/drag0n-flyer/myApp/releases/download/webrtc-m156.1/WebRTC.xcframework.zip

package() { # package <file> <checksum>
  cat >"$1" <<EOF
let webrtc: Target = local.map { .binaryTarget(name: "WebRTC", path: \$0) }
    ?? .binaryTarget(
        name: "WebRTC",
        url: "$url",
        checksum: "$2")
EOF
}
package "$work/placeholder.swift" 0000000000000000000000000000000000000000000000000000000000000000
package "$work/pinned.swift" "$real_sum"
printf 'let x = 1\n' >"$work/nourl.swift"
sed 's/^RELEASE=.*/RELEASE=99/' "$here/VERSION" >"$work/other-VERSION"

run() { # run <package.swift> <VERSION>
  PIN_PACKAGE_SWIFT=$1 PIN_VERSION_FILE=$2 PIN_DOWNLOAD_DIR="$work/release" \
    sh "$here/check-package-pin.sh"
}

expect_exit "placeholder checksum fails" 1 run "$work/placeholder.swift" "$here/VERSION"
expect_output "placeholder checksum names the mismatch" "checksum mismatch"

expect_exit "pinned checksum and same VERSION pass" 0 run "$work/pinned.swift" "$here/VERSION"
expect_output "pass prints the tag" "webrtc-m156.1"

expect_exit "different VERSION fails" 1 run "$work/pinned.swift" "$work/other-VERSION"
expect_output "different VERSION names the mismatch" "VERSION mismatch"

expect_exit "Package.swift without url fails" 1 run "$work/nourl.swift" "$here/VERSION"
expect_output "missing url is named" "no url"

if [ "$failures" -eq 0 ]; then
  echo "all package pin tests passed"
else
  echo "$failures package pin test(s) failed"
  exit 1
fi
