#!/bin/sh
# Checks patches 0002-0004 against the real upstream files at WEBRTC_COMMIT.
# Run: sh tools/webrtc/patches_test.sh
#
# Fetches every file the patches modify from googlesource (?format=TEXT, one
# file at a time, cached), commits them in a scratch repo, applies 0002, 0003
# and 0004 in order with git apply, and checks the result. It cannot compile
# Objective-C on Linux; the macOS build in CI does that.
# Exit codes: 0 pass, 1 test failure, 3 cannot fetch from googlesource.
set -u

here=$(cd "$(dirname "$0")" && pwd)
. "$here/VERSION"

work=$(mktemp -d "${TMPDIR:-/tmp}/patches-test.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM
cache="${TMPDIR:-/tmp}/app-webrtc-cache/upstream-$WEBRTC_COMMIT"
mkdir -p "$cache"

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
has() { grep -q -- "$1" "$2"; }

patches=$(ls "$here"/patches/000[234]-*.patch)
# Files the patches modify (not the ones they create).
files=$(for p in $patches; do
  awk '/^diff --git /{f=substr($4,3)} /^--- a\//{print f}' "$p"
done | sort -u)

fetch() { # fetch <path-in-webrtc-src> <dest>: cached
  c="$cache/$(echo "$1" | tr / _)"
  if [ ! -s "$c" ]; then
    curl -fsSL --retry 3 --connect-timeout 30 \
      "https://webrtc.googlesource.com/src/+/$WEBRTC_COMMIT/$1?format=TEXT" | base64 -d >"$c.part" &&
      mv "$c.part" "$c" || {
      echo "cannot fetch $1" >&2
      rm -f "$c.part"
      exit 3
    }
  fi
  mkdir -p "$(dirname "$2")"
  cp "$c" "$2"
}

repo="$work/src"
mkdir -p "$repo"
for f in $files; do fetch "$f" "$repo/$f"; done
# Not patched, but the build relies on them.
fetch modules/audio_coding/codecs/opus/audio_decoder_opus.cc "$work/upstream/audio_decoder_opus.cc"
fetch webrtc.gni "$work/upstream/webrtc.gni"
g -C "$repo" init -q
g -C "$repo" add -A
g -C "$repo" commit -q -m base

for p in $patches; do
  n=$(basename "$p")
  check "$n: git apply --check" g -C "$repo" apply --check "$p"
  g -C "$repo" apply "$p" 2>/dev/null
  g -C "$repo" add -A
  g -C "$repo" commit -q --allow-empty -m "$n"
done

# The pinned names the patches rely on.
check "PLC trial name is in audio_decoder_opus.cc" has "\"$FIELD_TRIAL_OPUS_PLC\"" "$work/upstream/audio_decoder_opus.cc"
check "webrtc.gni has rtc_opus_support_120ms_ptime" has "^  rtc_opus_support_120ms_ptime = true" "$work/upstream/webrtc.gni"

# 0002
d="$repo/modules/audio_coding/codecs/opus"
check "0002: complexity constant is 7" has "kAppOpusDecoderComplexity = 7;" "$d/app_opus_config.h"
check "0002: BWE constant is 1" has "kAppOpusOsceBwe = 1;" "$d/app_opus_config.h"
check "0002: DecoderCreate sets complexity" has "OPUS_SET_COMPLEXITY(webrtc::kAppOpusDecoderComplexity)" "$d/opus_interface.cc"
check "0002: DecoderCreate sets OSCE BWE" has "OPUS_SET_OSCE_BWE(webrtc::kAppOpusOsceBwe)" "$d/opus_interface.cc"
check "0002: ctl results are DCHECKed" has "RTC_DCHECK_EQ(ret, OPUS_OK)" "$d/opus_interface.cc"
check "0002: getter declared" has "int WebRtcOpus_DecoderGetComplexity(OpusDecInst\* inst);" "$d/opus_interface.h"

# 0003, 0004
o="$repo/sdk/objc/api/peerconnection"
for sym in opusVersion deepPLCActive osceActive bweActive webRTCDecoderComplexity opusPLCFieldTrial maxPacketMs; do
  check "0003: RTCOpusInfo.$sym" has "(class, nonatomic, readonly).*[ *]$sym;" "$o/RTCOpusInfo.h"
done
check "0003: encoderFrameMsForPtime" has "+ (NSInteger)encoderFrameMsForPtime:(NSInteger)ptime;" "$o/RTCOpusInfo.h"
check "0003: header exported in both frameworks" test "$(grep -c 'RTCOpusInfo.h' "$repo/sdk/BUILD.gn")" -eq 3
check "0003: define comes from a gn arg" has 'APP_OPUS_PLC_TRIAL=' "$repo/sdk/BUILD.gn"
check "0004: protocol" has "(RTCAudioCapturePostProcessing)<NSObject>" "$o/RTCAudioCapturePostProcessing.h"
check "0004: hasCapturePostProcessingHook" has "hasCapturePostProcessingHook;" "$o/RTCAudioCapturePostProcessing.h"
check "0004: factory initializer" has "capturePostProcessing:" "$o/RTCAudioCapturePostProcessing.h"
check "0004: self-test" has "selfTestCapturePostProcessing:" "$o/RTCAudioCapturePostProcessing.h"
check "0004: header listed as a source and exported in both frameworks" test "$(grep -c 'RTCAudioCapturePostProcessing.h' "$repo/sdk/BUILD.gn")" -eq 3
check "0004: uses SetCapturePostProcessing" has "SetCapturePostProcessing" "$o/RTCAudioCapturePostProcessing.mm"

# A public header that declares a category must import the header of the class
# it extends (the umbrella header lists these alphabetically, so it cannot rely
# on an earlier header).
for h in "$o"/RTC*.h; do
  cls=$(sed -n 's/^@interface RTC_OBJC_TYPE *(\([A-Za-z0-9_]*\)) *(.*/\1/p' "$h" | head -1)
  [ -n "$cls" ] || continue
  case "$(basename "$h")" in "$cls+"*) continue ;; esac # upstream +Private/+Native headers
  check "$(basename "$h") imports $cls.h for its category" has "#import \".*/$cls.h\"" "$h"
done

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all patch checks passed"
