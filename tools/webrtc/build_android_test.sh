#!/bin/sh
# Linux tests for the Android WebRTC build: build-android.sh (arguments and gn
# arguments), package-android.sh (the AAR), apply.sh's platform switch, and
# patches 0005 and 0006 against the real upstream files at WEBRTC_COMMIT.
# Run: sh tools/webrtc/build_android_test.sh
#
# Needs git, python3, curl and base64. The patch checks fetch the files the
# patches modify from googlesource (cached under $TMPDIR/app-webrtc-cache); the
# apply.sh checks download the Opus tarball (hash-checked; reuse the one
# opus-probe/run-tests.sh caches). It cannot compile Java or C++; the Android
# build in CI does that.
# Exit codes: 0 all pass, 1 a test failed, 3 cannot fetch from googlesource.
set -u

here=$(cd "$(dirname "$0")" && pwd)
. "$here/VERSION"

work=$(mktemp -d "${TMPDIR:-/tmp}/build-android-test.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM
failures=0

if [ -f "${TMPDIR:-/tmp}/opus-probe/opus-$OPUS_VERSION.tar.gz" ]; then
  APPLY_CACHE_DIR="${TMPDIR:-/tmp}/opus-probe"
else
  APPLY_CACHE_DIR="${TMPDIR:-/tmp}/app-webrtc-cache"
fi
export APPLY_CACHE_DIR

pass() { echo "ok   $1"; }
fail() {
  echo "FAIL $1"
  failures=$((failures + 1))
}
check() { # check <description> <command...>
  desc=$1
  shift
  if "$@" >/dev/null 2>&1; then pass "$desc"; else fail "$desc"; fi
}
expect_exit() { # expect_exit <description> <code> <command...>
  desc=$1
  want=$2
  shift 2
  "$@" >"$work/out.txt" 2>&1
  got=$?
  if [ "$got" -eq "$want" ]; then
    pass "$desc"
  else
    echo "FAIL $desc: exit $got, wanted $want"
    sed 's/^/     /' "$work/out.txt"
    failures=$((failures + 1))
  fi
}
out_has() { grep -q -- "$1" "$work/out.txt"; }
has() { grep -q -- "$1" "$2"; }
g() { git -c user.name=test -c user.email=test@example.com -c commit.gpgsign=false "$@"; }

# --- build-android.sh ---
expect_exit "build-android: no arguments" 1 sh "$here/build-android.sh"
expect_exit "build-android: unknown ABI" 1 env WEBRTC_WORKDIR="$work/w" sh "$here/build-android.sh" mips
check "build-android: unknown ABI is named" out_has "unknown ABI 'mips'"
expect_exit "build-android: unknown ABI (gn args)" 1 sh "$here/build-android.sh" --print-gn-args x86
expect_exit "build-android: gn args need an ABI" 1 sh "$here/build-android.sh" --print-gn-args
expect_exit "build-android: a build needs Linux" 1 env APP_UNAME=Darwin WEBRTC_WORKDIR="$work/w" sh "$here/build-android.sh" arm64-v8a
check "build-android: says Linux" out_has "needs Linux"
expect_exit "build-android: a second argument is rejected" 1 sh "$here/build-android.sh" arm64-v8a "$work/w"
check "build-android: no workdir directory made on a bad call" test ! -e "$work/w"

for abi in arm64-v8a armeabi-v7a x86_64; do
  expect_exit "build-android: gn args for $abi" 0 sh "$here/build-android.sh" --print-gn-args "$abi"
  cp "$work/out.txt" "$work/args-$abi.txt"
  for a in 'target_os="android"' 'is_debug=false' 'symbol_level=1' 'rtc_use_h264=false' \
    'rtc_include_dav1d_in_internal_decoder_factory=false' 'rtc_opus_support_120ms_ptime=true' \
    'is_component_build=false' 'rtc_include_tests=false'; do
    check "build-android: $abi has $a" has "$a" "$work/args-$abi.txt"
  done
  check "build-android: $abi is one line" test "$(wc -l <"$work/args-$abi.txt")" -eq 1
  check "build-android: $abi has no dot-product flag" sh -c '! grep -q -i "dotprod\|dot_prod\|arm_version\|arm_use_neon=false" "$1"' _ "$work/args-$abi.txt"
done
check "build-android: arm64-v8a is target_cpu arm64" has 'target_cpu="arm64"' "$work/args-arm64-v8a.txt"
check "build-android: armeabi-v7a is target_cpu arm" has 'target_cpu="arm"' "$work/args-armeabi-v7a.txt"
check "build-android: x86_64 is target_cpu x64" has 'target_cpu="x64"' "$work/args-x86_64.txt"
# The pinned Apple-only argument must not leak in.
check "build-android: no app_opus_plc_trial" sh -c '! grep -q app_opus_plc_trial "$1" "$2"' _ "$work/args-arm64-v8a.txt" "$work/args-x86_64.txt"
check "build-android: no Apple arguments" sh -c '! grep -q "ios\|mac_\|enable_dsyms" "$1" "$2"' _ "$work/args-arm64-v8a.txt" "$work/args-x86_64.txt"
# The whole line, exactly. An added or dropped argument fails here.
common='target_os="android" is_debug=false is_component_build=false rtc_include_tests=false android_static_analysis="off" use_siso=true symbol_level=1 rtc_use_h264=false rtc_include_dav1d_in_internal_decoder_factory=false rtc_opus_support_120ms_ptime=true'
check "build-android: arm64-v8a exact args" test "$(cat "$work/args-arm64-v8a.txt")" = "$common target_cpu=\"arm64\""
check "build-android: armeabi-v7a exact args" test "$(cat "$work/args-armeabi-v7a.txt")" = "$common target_cpu=\"arm\""
check "build-android: x86_64 exact args" test "$(cat "$work/args-x86_64.txt")" = "$common target_cpu=\"x64\""
# The exact gn arguments of the brief, in the exact form.
for a in 'rtc_use_h264=false' 'rtc_include_dav1d_in_internal_decoder_factory=false' \
  'rtc_opus_support_120ms_ptime=true' 'is_debug=false' 'symbol_level=1' 'target_os="android"'; do
  n=$(grep -o -- " $a\( \|$\)\|^$a\( \|$\)" "$work/args-arm64-v8a.txt" | wc -l)
  check "build-android: $a appears exactly once" test "$n" -eq 1
done

# --- package-android.sh ---
make_layout() { # make_layout <dir> <abi...>: a fake build output
  d=$1
  shift
  mkdir -p "$d"
  for abi in "$@"; do
    mkdir -p "$d/$abi"
    printf 'ELF-%s\n' "$abi" >"$d/$abi/libjingle_peerconnection_so.so"
  done
  python3 -I - "$d/arm64-v8a/libwebrtc.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    for c in ("org/webrtc/PeerConnectionFactory", "org/webrtc/OpusInfo", "org/webrtc/AudioCapturePostProcessing"):
        z.writestr(c + ".class", "x")
PY
  printf '<manifest package="org.webrtc"/>\n' >"$d/AndroidManifest.xml"
  printf 'WebRTC\nBSD 3-clause\n' >"$d/LICENSE.md"
}
zip_names() { python3 -I -c 'import sys, zipfile; print("\n".join(sorted(zipfile.ZipFile(sys.argv[1]).namelist())))' "$1"; }

expect_exit "package-android: no arguments" 1 sh "$here/package-android.sh"
expect_exit "package-android: missing directory" 1 sh "$here/package-android.sh" "$work/none"

make_layout "$work/ok" arm64-v8a armeabi-v7a x86_64
expect_exit "package-android: three ABIs" 0 sh "$here/package-android.sh" "$work/ok"
check "package-android: AAR written" test -s "$work/ok/libwebrtc.aar"
zip_names "$work/ok/libwebrtc.aar" >"$work/names.txt"
check "package-android: jni/ holds exactly the three ABIs" test "$(grep '^jni/' "$work/names.txt")" = "$(printf 'jni/arm64-v8a/libjingle_peerconnection_so.so\njni/armeabi-v7a/libjingle_peerconnection_so.so\njni/x86_64/libjingle_peerconnection_so.so')"
check "package-android: AndroidManifest.xml" has '^AndroidManifest.xml$' "$work/names.txt"
check "package-android: classes.jar" has '^classes.jar$' "$work/names.txt"
check "package-android: classes.jar is the jar" sh -c 'python3 -I -c "
import io, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
j = zipfile.ZipFile(io.BytesIO(z.read(\"classes.jar\")))
sys.exit(0 if \"org/webrtc/OpusInfo.class\" in j.namelist() else 1)" "$1"' _ "$work/ok/libwebrtc.aar"
check "package-android: .sha256 matches" sh -c 'cd "$1" && sha256sum -c libwebrtc.aar.sha256 >/dev/null' _ "$work/ok"
check "package-android: .sha256 is 'hash  name'" sh -c 'grep -q "^[0-9a-f]\{64\}  libwebrtc.aar$" "$1"' _ "$work/ok/libwebrtc.aar.sha256"
check "package-android: LICENSE.md kept" test -s "$work/ok/LICENSE.md"
cp "$work/ok/libwebrtc.aar" "$work/first.aar"
sh "$here/package-android.sh" "$work/ok" >/dev/null 2>&1
check "package-android: the AAR is reproducible" cmp -s "$work/first.aar" "$work/ok/libwebrtc.aar"

make_layout "$work/one" arm64-v8a armeabi-v7a
expect_exit "package-android: a missing ABI fails" 1 sh "$here/package-android.sh" "$work/one"
check "package-android: names x86_64" out_has "x86_64"
make_layout "$work/three" arm64-v8a armeabi-v7a x86_64 x86
expect_exit "package-android: an extra ABI fails" 1 sh "$here/package-android.sh" "$work/three"
check "package-android: names the extra ABI" out_has "unexpected ABI .x86."
make_layout "$work/gpl" arm64-v8a armeabi-v7a x86_64
printf 'dep\nGNU General Public License v3\n' >"$work/gpl/LICENSE.md"
expect_exit "package-android: a GNU license fails" 4 sh "$here/package-android.sh" "$work/gpl"
check "package-android: no AAR for a GNU license" test ! -e "$work/gpl/libwebrtc.aar"
make_layout "$work/nolic" arm64-v8a armeabi-v7a x86_64
rm "$work/nolic/LICENSE.md"
expect_exit "package-android: a missing license fails" 1 sh "$here/package-android.sh" "$work/nolic"
make_layout "$work/nojar" arm64-v8a armeabi-v7a x86_64
rm "$work/nojar/arm64-v8a/libwebrtc.jar"
expect_exit "package-android: a missing jar fails" 1 sh "$here/package-android.sh" "$work/nojar"
make_layout "$work/nocls" arm64-v8a armeabi-v7a x86_64
python3 -I - "$work/nocls/arm64-v8a/libwebrtc.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("org/webrtc/PeerConnectionFactory.class", "x")
PY
expect_exit "package-android: a jar without our classes fails" 1 sh "$here/package-android.sh" "$work/nocls"
check "package-android: names OpusInfo" out_has "org/webrtc/OpusInfo.class"

# --- apply.sh platform switch ---
new_tree() { # new_tree <dir>: fixture WebRTC tree
  mkdir -p "$1/third_party/opus" "$1/modules/audio_coding/codecs/opus"
  printf 'one\ntwo\nthree\nfour\nfive\n' >"$1/third_party/opus/BUILD.gn"
  g -C "$1/third_party" init -q
  g -C "$1/third_party" add opus/BUILD.gn
  g -C "$1/third_party" commit -q -m base
  for f in f3 f4 f5 f6; do printf 'base\n' >"$1/$f.txt"; done
  printf 'generate_plc_(field_trials.IsEnabled("%s"))\n' "$FIELD_TRIAL_OPUS_PLC" >"$1/modules/audio_coding/codecs/opus/audio_decoder_opus.cc"
  g -C "$1" init -q
  g -C "$1" add f3.txt f4.txt f5.txt f6.txt modules
  g -C "$1" commit -q -m base
}
# Fixture patches 0001..0006, each changing one file (0001 is for third_party).
pd="$work/patches"
mkdir -p "$pd"
t0="$work/t0"
new_tree "$t0"
printf 'one\ntwo\nTHREE\nfour\nfive\n' >"$t0/third_party/opus/BUILD.gn"
g -C "$t0/third_party" diff --no-prefix >"$pd/0001-fixture.patch"
g -C "$t0/third_party" checkout -q -- opus/BUILD.gn
for n in 3 4 5 6; do
  printf 'patched\n' >"$t0/f$n.txt"
  g -C "$t0" diff -- "f$n.txt" >"$pd/000$n-fixture.patch"
  g -C "$t0" checkout -q -- "f$n.txt"
done

run_apply() { # run_apply <tree> [APPLY_PLATFORM value]; sets code
  if [ -n "${2-}" ]; then
    APPLY_PLATFORM=$2 APPLY_PATCH_DIR="$pd" APPLY_THIRD_PARTY_PATCH="$pd/0001-fixture.patch" sh "$here/apply.sh" "$1" >"$work/apply.txt" 2>&1
  else
    APPLY_PATCH_DIR="$pd" APPLY_THIRD_PARTY_PATCH="$pd/0001-fixture.patch" sh "$here/apply.sh" "$1" >"$work/apply.txt" 2>&1
  fi
  code=$?
}
is_patched() { [ "$(cat "$1/f$2.txt")" = patched ]; }

ta="$work/android"
new_tree "$ta"
run_apply "$ta" android
check "apply android: exit 0" test "$code" -eq 0
check "apply android: 0005 and 0006 applied" sh -c '[ "$(cat "$1/f5.txt")" = patched ] && [ "$(cat "$1/f6.txt")" = patched ]' _ "$ta"
check "apply android: 0003 and 0004 not applied" sh -c '[ "$(cat "$1/f3.txt")" = base ] && [ "$(cat "$1/f4.txt")" = base ]' _ "$ta"
check "apply android: 0001 applied" has '^THREE$' "$ta/third_party/opus/BUILD.gn"
check "apply android: probe in sdk/android/src/jni/app" test -f "$ta/sdk/android/src/jni/app/opus_probe.c" -a -f "$ta/sdk/android/src/jni/app/opus_probe.h"
check "apply android: no probe in the Objective-C tree" test ! -e "$ta/sdk/objc/components/app/opus_probe.c"

tp="$work/apple"
new_tree "$tp"
run_apply "$tp"
check "apply default (Apple): exit 0" test "$code" -eq 0
check "apply default (Apple): 0003 and 0004 applied" sh -c '[ "$(cat "$1/f3.txt")" = patched ] && [ "$(cat "$1/f4.txt")" = patched ]' _ "$tp"
check "apply default (Apple): 0005 and 0006 not applied" sh -c '[ "$(cat "$1/f5.txt")" = base ] && [ "$(cat "$1/f6.txt")" = base ]' _ "$tp"
check "apply default (Apple): probe in the Objective-C tree" test -f "$tp/sdk/objc/components/app/opus_probe.c"
check "apply default (Apple): no probe in sdk/android" test ! -e "$tp/sdk/android/src/jni/app/opus_probe.c"

tb="$work/badplat"
new_tree "$tb"
run_apply "$tb" windows
check "apply: unknown platform fails" test "$code" -eq 1
check "apply: unknown platform is named" has "unknown APPLY_PLATFORM 'windows'" "$work/apply.txt"

# --- patches 0005 and 0006 against the upstream files ---
cache="${TMPDIR:-/tmp}/app-webrtc-cache/upstream-$WEBRTC_COMMIT"
mkdir -p "$cache"
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
patch_05=$(ls "$here"/patches/0005-*.patch 2>/dev/null | head -1)
patch_06=$(ls "$here"/patches/0006-*.patch 2>/dev/null | head -1)
if [ ! -f "$patch_05" ] || [ ! -f "$patch_06" ]; then
  fail "patches 0005 and 0006 exist"
  echo
  echo "$failures check(s) failed"
  exit 1
fi
files=$(for p in "$patch_05" "$patch_06"; do awk '/^diff --git /{f=substr($4,3)} /^--- a\//{print f}' "$p"; done | sort -u)
mkrepo() { # mkrepo <dir>: the upstream files the patches modify, committed
  mkdir -p "$1"
  for f in $files; do fetch "$f" "$1/$f"; done
  g -C "$1" init -q
  g -C "$1" add -A
  g -C "$1" commit -q -m base
}
repo="$work/src"
mkrepo "$repo"
check "0005: git apply --check --3way (alone)" g -C "$repo" apply --check --3way "$patch_05"
check "0006: git apply --check --3way (alone)" g -C "$repo" apply --check --3way "$patch_06"
g -C "$repo" apply --3way "$patch_05" >/dev/null 2>&1
g -C "$repo" add -A
g -C "$repo" commit -q -m 0005
check "0006: git apply --check --3way (after 0005)" g -C "$repo" apply --check --3way "$patch_06"
g -C "$repo" apply --3way "$patch_06" >/dev/null 2>&1
check "0005 and 0006 leave no conflict markers" sh -c '! grep -rn "^<<<<<<<" "$1" --exclude-dir=.git' _ "$repo"
# Applying in the other order must work too (apply.sh runs them in name order,
# but a rebase of one patch must not need the other).
repo2="$work/src2"
mkrepo "$repo2"
g -C "$repo2" apply --3way "$patch_06" >/dev/null 2>&1
g -C "$repo2" add -A
g -C "$repo2" commit -q -m 0006
check "0005: git apply --check --3way (after 0006)" g -C "$repo2" apply --check --3way "$patch_05"

a="$repo/sdk/android"
check "0005: OpusInfo.java declares the four natives" sh -c 'for m in decoderComplexity hasDeepPlc hasOsce maxPacketMs; do grep -q "public static .* $m()" "$1" || exit 1; done' _ "$a/api/org/webrtc/OpusInfo.java"
check "0005: JNI functions" sh -c 'for m in DecoderComplexity HasDeepPlc HasOsce MaxPacketMs; do grep -q "JNI_OpusInfo_$m" "$1" || exit 1; done' _ "$a/src/jni/app/opus_info.cc"
check "0005: JNI file ends with DEFINE_JNI" has '^DEFINE_JNI(OpusInfo)' "$a/src/jni/app/opus_info.cc"
check "0005: probe files are listed as sources" sh -c 'grep -q "src/jni/app/opus_probe.c" "$1" && grep -q "src/jni/app/opus_probe.h" "$1"' _ "$a/BUILD.gn"
check "0005: Java library is in libwebrtc.jar" has '":app_opus_java",' "$a/BUILD.gn"
check "0005: JNI library is linked into the .so" has '":app_opus_jni",' "$a/BUILD.gn"
check "0005: depends on //third_party/opus" has '"//third_party/opus",' "$a/BUILD.gn"
check "0006: AudioCapturePostProcessing implements AudioProcessingFactory" has 'class AudioCapturePostProcessing implements AudioProcessingFactory' "$a/api/org/webrtc/AudioCapturePostProcessing.java"
check "0006: no jni_zero::ToJavaFloatArray (not in jni_zero at the pin)" sh -c '! grep -q "jni_zero::ToJavaFloatArray" "$1"' _ "$a/src/jni/app/audio_capture_post_processing.cc"
check "0006: float array built with NewFloatArray and AdoptRef" sh -c 'grep -q "NewFloatArray" "$1" && grep -q "jni_zero::AdoptRef" "$1"' _ "$a/src/jni/app/audio_capture_post_processing.cc"
check "0006: Java object is single use" sh -c 'grep -q "IllegalStateException" "$1" && grep -q "nativeProcessor = 0;" "$1"' _ "$a/api/org/webrtc/AudioCapturePostProcessing.java"
check "0006: createNative and selfTest both take the handle" sh -c '[ "$(grep -c "take())" "$1")" -eq 2 ]' _ "$a/api/org/webrtc/AudioCapturePostProcessing.java"
check "0006: header says the struct must stay valid" has "must stay valid until createNative" "$a/src/jni/app/audio_capture_processor.h"
check "0006: C ABI header" test -f "$a/src/jni/app/audio_capture_processor.h"
check "0006: uses SetCapturePostProcessing" has "SetCapturePostProcessing" "$a/src/jni/app/audio_capture_post_processing.cc"
check "0006: JNI file ends with DEFINE_JNI" has '^DEFINE_JNI(AudioCapturePostProcessing)' "$a/src/jni/app/audio_capture_post_processing.cc"
check "0006: Java library is in libwebrtc.jar" has '":capture_post_processing_java",' "$a/BUILD.gn"
check "0006: JNI library is linked into the .so" has '":capture_post_processing_jni",' "$a/BUILD.gn"
check "0006: factory takes ownership of the processor reference" has 'TakeOwnershipOfRefPtr<AudioProcessing>(native_audio_processor)' "$a/src/jni/pc/peer_connection_factory.cc"
check "0006: the leaking conversion is gone" sh -c '! grep -q "reinterpret_cast<AudioProcessing\*>(native_audio_processor)" "$1"' _ "$a/src/jni/pc/peer_connection_factory.cc"
# Every .cc, .h and .java a patch adds must be listed in a BUILD.gn source list.
for f in api/org/webrtc/OpusInfo.java api/org/webrtc/AudioCapturePostProcessing.java \
  src/jni/app/opus_info.cc src/jni/app/audio_capture_post_processing.cc src/jni/app/audio_capture_processor.h; do
  check "BUILD.gn lists $f" has "\"$f\"" "$a/BUILD.gn"
done

if [ "$failures" -ne 0 ]; then
  echo
  echo "$failures check(s) failed"
  exit 1
fi
echo
echo "all Android build tests passed"
