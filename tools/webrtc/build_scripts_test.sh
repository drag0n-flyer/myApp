#!/bin/sh
# Linux tests for the parts of check-symbols.sh, package.sh and build.sh that
# need no macOS: symbol filtering, the GPL license scan, argument checks and the
# gn arguments. Run: sh tools/webrtc/build_scripts_test.sh
set -u

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d "${TMPDIR:-/tmp}/build-scripts-test.XXXXXX")
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

# --- check-symbols.sh ---
if command -v cc >/dev/null 2>&1; then
  printf 'int sign(void){return 1;}\nint RTCFoo(void){return 2;}\n' | cc -shared -fPIC -x c - -o "$work/s.so"
  expect_exit "check-symbols: exported _sign" 5 sh "$here/check-symbols.sh" "$work/s.so"
  expect_output "check-symbols: names _sign" '^_sign$'
  printf 'int RTCFoo(void){return 2;}\nint WebRTCBar(void){return 3;}\n' | cc -shared -fPIC -x c - -o "$work/ok.so"
  expect_exit "check-symbols: only RTC and WebRTC names" 0 sh "$here/check-symbols.sh" "$work/ok.so"
  printf 'int RTCFoo(void){return 2;}\nint some_helper(void){return 3;}\n' | cc -shared -fPIC -x c - -o "$work/h.so"
  expect_exit "check-symbols: unprefixed helper" 5 sh "$here/check-symbols.sh" "$work/h.so"
  expect_output "check-symbols: names _some_helper" '^_some_helper$'
  printf 'int RTCFoo(void){return 2;}\n__attribute__((visibility("hidden"))) int sign(void){return 3;}\n' | cc -shared -fPIC -x c - -o "$work/hid.so"
  expect_exit "check-symbols: hidden sign is fine" 0 sh "$here/check-symbols.sh" "$work/hid.so"
  printf '_RTCFoo\n' >"$work/res.txt"
  expect_exit "check-symbols: custom reserved list" 5 env RESERVED_SYMBOLS_FILE="$work/res.txt" sh "$here/check-symbols.sh" "$work/ok.so"
else
  echo "skip check-symbols tests: no cc"
fi
expect_exit "check-symbols: no argument" 1 sh "$here/check-symbols.sh"
expect_exit "check-symbols: missing file" 1 sh "$here/check-symbols.sh" "$work/nope"

# --- package.sh license scan ---
printf 'WebRTC\nBSD 3-clause\n' >"$work/ok.md"
expect_exit "package: BSD license passes" 0 sh "$here/package.sh" --check-license "$work/ok.md"
for text in 'GNU General Public License' 'GNU Lesser General Public License' 'GNU Affero General Public License'; do
  printf 'some dependency\n%s version 3\n' "$text" >"$work/bad.md"
  expect_exit "package: '$text' fails" 4 sh "$here/package.sh" --check-license "$work/bad.md"
done
expect_exit "package: missing license file" 1 sh "$here/package.sh" --check-license "$work/none.md"
expect_exit "package: no arguments" 1 sh "$here/package.sh"
expect_exit "package: missing artifacts dir" 1 sh "$here/package.sh" "$work/none" "$work/o"

# --- build.sh ---
expect_exit "build: no arguments" 1 sh "$here/build.sh"
expect_exit "build: unknown slice" 1 sh "$here/build.sh" watchos "$work/w"
for slice in ios-arm64 ios-sim-arm64 ios-sim-x64 mac-arm64 mac-x64; do
  expect_exit "build: gn args for $slice" 0 sh "$here/build.sh" --print-gn-args "$slice"
  for a in 'is_debug=false' 'rtc_use_h264=false' 'rtc_opus_support_120ms_ptime=true' 'enable_dsyms=true' 'app_opus_plc_trial="WebRTC-Audio-OpusGeneratePlc"'; do
    expect_output "build: $slice has $a" "$a"
  done
done
sh "$here/build.sh" --print-gn-args ios-arm64 >"$work/a.txt"
grep -q 'target_environment="device"' "$work/a.txt" && grep -q 'target_cpu="arm64"' "$work/a.txt" && grep -q 'ios_deployment_target="17.0"' "$work/a.txt" \
  && echo "ok   build: ios-arm64 device arm64 17.0" || { echo "FAIL build: ios-arm64 args"; failures=$((failures + 1)); }
sh "$here/build.sh" --print-gn-args ios-sim-x64 >"$work/a.txt"
grep -q 'target_environment="simulator"' "$work/a.txt" && grep -q 'target_cpu="x64"' "$work/a.txt" \
  && echo "ok   build: ios-sim-x64 simulator x64" || { echo "FAIL build: ios-sim-x64 args"; failures=$((failures + 1)); }
sh "$here/build.sh" --print-gn-args mac-arm64 >"$work/a.txt"
grep -q 'target_os="mac"' "$work/a.txt" && grep -q 'mac_deployment_target="14.0"' "$work/a.txt" && ! grep -q 'ios_deployment' "$work/a.txt" \
  && echo "ok   build: mac-arm64 args" || { echo "FAIL build: mac-arm64 args"; failures=$((failures + 1)); }
sh "$here/build.sh" --print-target mac-x64 >"$work/a.txt"
[ "$(cat "$work/a.txt")" = "//sdk:mac_framework_objc" ] && echo "ok   build: mac target" || { echo "FAIL build: mac target"; failures=$((failures + 1)); }
sh "$here/build.sh" --print-target ios-sim-arm64 >"$work/a.txt"
[ "$(cat "$work/a.txt")" = "//sdk:framework_objc" ] && echo "ok   build: ios target" || { echo "FAIL build: ios target"; failures=$((failures + 1)); }

echo
if [ "$failures" -eq 0 ]; then
  echo "all passed"
  exit 0
fi
echo "$failures failed"
exit 1
