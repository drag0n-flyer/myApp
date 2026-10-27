#!/bin/sh
# Checks patches/0001-third-party-opus-1.6.patch against the real
# third_party/opus/BUILD.gn at the revision the pinned WebRTC DEPS names for
# src/third_party. Run: sh tools/webrtc/patch_test.sh
#
# Fetches DEPS and BUILD.gn from googlesource (cached) and needs the opus 1.6
# tarball (apply.sh downloads and hash-checks it). It applies the patch with
# apply.sh to a fixture tree that holds the real BUILD.gn, then checks the result.
# Exit codes: 0 pass, 1 test failure, 3 cannot fetch from googlesource.
set -u

here=$(cd "$(dirname "$0")" && pwd)
. "$here/VERSION"

work=$(mktemp -d "${TMPDIR:-/tmp}/patch-test.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM
cache="${TMPDIR:-/tmp}/app-webrtc-cache" # fetched DEPS and BUILD.gn
mkdir -p "$cache"
# The tarball: reuse the one opus-probe/run-tests.sh caches when present.
if [ -z "${APPLY_CACHE_DIR-}" ]; then
  if [ -f "${TMPDIR:-/tmp}/opus-probe/opus-$OPUS_VERSION.tar.gz" ]; then
    export APPLY_CACHE_DIR="${TMPDIR:-/tmp}/opus-probe"
  else
    export APPLY_CACHE_DIR="$cache"
  fi
fi

failures=0
check() { # check <description> <command...>
  desc=$1
  shift
  if "$@"; then echo "PASS $desc"; else
    echo "FAIL $desc"
    failures=$((failures + 1))
  fi
}
g() { git -c user.name=test -c user.email=test@example.com -c commit.gpgsign=false "$@"; }

gitiles() { # gitiles <url> <out>: fetch a file through the ?format=TEXT (base64) endpoint
  curl -fsSL --retry 3 --connect-timeout 30 "$1?format=TEXT" |
    python3 -I -c 'import base64,sys; sys.stdout.buffer.write(base64.b64decode(sys.stdin.read()))' >"$2.part" &&
    mv "$2.part" "$2"
}

# The Chromium third_party revision named by the pinned WebRTC DEPS.
deps="$cache/webrtc-DEPS.$WEBRTC_COMMIT"
if [ ! -s "$deps" ]; then
  gitiles "https://webrtc.googlesource.com/src/+/$WEBRTC_COMMIT/DEPS" "$deps" || {
    echo "cannot fetch DEPS at $WEBRTC_COMMIT" >&2
    exit 3
  }
fi
rev=$(sed -n "/'src\/third_party':/{n;s/.*third_party@\([0-9a-f]\{40\}\)'.*/\1/p;}" "$deps")
[ -n "$rev" ] || {
  echo "no src/third_party revision in DEPS" >&2
  exit 3
}
echo "third_party revision $rev"

build_gn="$cache/opus-BUILD.gn.$rev"
if [ ! -s "$build_gn" ]; then
  gitiles "https://chromium.googlesource.com/chromium/src/third_party/+/$rev/opus/BUILD.gn" "$build_gn" || {
    echo "cannot fetch third_party/opus/BUILD.gn at $rev" >&2
    exit 3
  }
fi

# Fixture tree: the real BUILD.gn in a git repo at third_party, plus an outer repo.
tree="$work/tree"
mkdir -p "$tree/third_party/opus" "$work/no-patches"
cp "$build_gn" "$tree/third_party/opus/BUILD.gn"
g -C "$tree/third_party" init -q
g -C "$tree/third_party" add opus/BUILD.gn
g -C "$tree/third_party" commit -q -m base
echo x >"$tree/x"
# apply.sh's last step looks for the Opus PLC field trial in this file.
mkdir -p "$tree/modules/audio_coding/codecs/opus"
printf '"%s"\n' "$FIELD_TRIAL_OPUS_PLC" >"$tree/modules/audio_coding/codecs/opus/audio_decoder_opus.cc"
g -C "$tree" init -q
g -C "$tree" add x modules
g -C "$tree" commit -q -m base

# git apply --check, as the plan's step 6 asks.
check "patch passes git apply --check on the real BUILD.gn" \
  g -C "$tree/third_party" apply --check -p0 "$here/patches/0001-third-party-opus-1.6.patch"

out=$(APPLY_PATCH_DIR="$work/no-patches" \
  APPLY_THIRD_PARTY_PATCH="$here/patches/0001-third-party-opus-1.6.patch" \
  sh "$here/apply.sh" "$tree" 2>&1)
code=$?
check "apply.sh applies the patch (exit 0)" test "$code" -eq 0
[ "$code" -eq 0 ] || echo "$out"

b="$tree/third_party/opus/BUILD.gn"
has() { grep -q "$1" "$b"; }
check 'imports dnn_sources.gni' has '^import("dnn_sources.gni")$'
check 'defines ENABLE_DEEP_PLC' has '^ *"ENABLE_DEEP_PLC",$'
check 'defines ENABLE_OSCE' has '^ *"ENABLE_OSCE",$'
check 'defines ENABLE_OSCE_BWE (CMake does not)' has '^ *"ENABLE_OSCE_BWE",$'
check 'defines PACKAGE_VERSION="1.6"' has '^ *"PACKAGE_VERSION=\\"1.6\\"",$'
check 'does not define ENABLE_DRED' sh -c '! grep -q ENABLE_DRED "$1"' _ "$b"
check 'adds src/dnn to include_dirs' has '^ *"src/dnn",$'
check 'adds deep PLC and OSCE sources' has 'sources += deep_plc_sources + osce_sources'
check 'adds arm64 neon sources' has 'sources += dnn_neon_sources'
# No dot-product: iOS 17 runs on the A12, which lacks it.
check 'no -march=armv8.2-a+dotprod' sh -c '! grep -v "^ *#" "$1" | grep -q "dotprod"' _ "$b"
check 'no OPUS_ARM_MAY_HAVE_DOTPROD' sh -c '! grep -v "^ *#" "$1" | grep -q "OPUS_ARM_MAY_HAVE_DOTPROD"' _ "$b"
check 'no OPUS_ARM_PRESUME_DOTPROD' sh -c '! grep -v "^ *#" "$1" | grep -q "OPUS_ARM_PRESUME_DOTPROD"' _ "$b"
check 'no opus_dotprod source set or dnn_dotprod_sources use' sh -c '! grep -v "^ *#" "$1" | grep -q "opus_dotprod\|dnn_dotprod_sources"' _ "$b"
check 'floating point only (no FIXED_POINT define, no silk/fixed sources)' \
  sh -c '! grep -v "^ *#" "$1" | grep -q "FIXED_POINT\|src/silk/fixed/.*\.c"' _ "$b"

# Every src/ path in BUILD.gn and dnn_sources.gni exists in the opus 1.6 tarball.
# (The arm-only 32-bit and ne10 files are not listed in BUILD.gn.)
missing=""
for f in $(grep -oh '"src/[^"]*"' "$b" "$tree/third_party/opus/dnn_sources.gni" | tr -d '"' | sort -u); do
  [ -e "$tree/third_party/opus/$f" ] || missing="$missing $f"
done
check "every src/ path in BUILD.gn and dnn_sources.gni exists in opus 1.6${missing:+ (missing:$missing)}" test -z "$missing"

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all patch checks passed"
