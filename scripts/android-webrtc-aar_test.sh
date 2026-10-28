#!/usr/bin/env bash
# Tests for apps/android/webrtc/build.gradle.kts: the -PwebrtcAar override (warning; refused in CI and for
# release builds unless -PwebrtcAarSha256 matches) and the download's timeouts and rename check.
# Runs Gradle (:webrtc:unpackWebrtcAar and :webrtc:mergeReleaseJniLibFolders), about a minute each.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
android="$here/../apps/android"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if "${@:2}"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails+1)); fi; }
# shellcheck disable=SC1090
[ -f "$HOME/android/env.sh" ] && source "$HOME/android/env.sh"

python3 -I - "$tmp/fixture.aar" <<'PY'
import io, sys, zipfile
jar = io.BytesIO()
zipfile.ZipFile(jar, "w").close()
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("classes.jar", jar.getvalue())
    z.writestr("jni/x86_64/libfixture.so", b"fixture")
PY
good=$(sha256sum "$tmp/fixture.aar" | cut -d' ' -f1)

run() { # run <label> <env-assignments...> -- <gradle args...>; sets rc and $tmp/out.txt
  shift
  local envs=()
  while [ "$1" != "--" ]; do envs+=("$1"); shift; done
  shift
  (cd "$android" && env -u CI ${envs[@]+"${envs[@]}"} ./gradlew --offline "$@" >"$tmp/out.txt" 2>&1)
  rc=$?
}

run x -- :webrtc:unpackWebrtcAar "-PwebrtcAar=$tmp/fixture.aar"
check "override works locally" test "$rc" -eq 0
check "override warns that the hash is not checked" grep -qi "WARNING.*webrtcAar.*not checked\|webrtcAar.*SHA-256 is not checked" "$tmp/out.txt"

run x CI=true -- :webrtc:unpackWebrtcAar "-PwebrtcAar=$tmp/fixture.aar"
check "override is refused when CI is set" test "$rc" -ne 0
check "the CI refusal says why" grep -q "refused when CI is set" "$tmp/out.txt"

run x CI=true -- :webrtc:unpackWebrtcAar "-PwebrtcAar=$tmp/fixture.aar" "-PwebrtcAarSha256=$good"
check "override with the matching hash is accepted in CI" test "$rc" -eq 0

run x -- :webrtc:mergeReleaseJniLibFolders "-PwebrtcAar=$tmp/fixture.aar"
check "override is refused for a release build without a hash" test "$rc" -ne 0
check "the release refusal says why" grep -q "release build" "$tmp/out.txt"

run x -- :webrtc:mergeReleaseJniLibFolders "-PwebrtcAar=$tmp/fixture.aar" "-PwebrtcAarSha256=$good"
check "release build with the matching hash is accepted" test "$rc" -eq 0

# assemble and build make the release variants without naming them.
run x -- :webrtc:assemble "-PwebrtcAar=$tmp/fixture.aar"
check "override is refused for assemble (it builds release) without a hash" test "$rc" -ne 0
check "the assemble refusal says why" grep -q "release build" "$tmp/out.txt"
run x -- :webrtc:assemble "-PwebrtcAar=$tmp/fixture.aar" "-PwebrtcAarSha256=$good"
check "assemble with the matching hash is accepted" test "$rc" -eq 0

run x -- :webrtc:unpackWebrtcAar "-PwebrtcAar=$tmp/fixture.aar" "-PwebrtcAarSha256=0000000000000000000000000000000000000000000000000000000000000000"
check "a wrong hash fails" test "$rc" -ne 0
check "the mismatch is reported" grep -qi "hash mismatch" "$tmp/out.txt"

gradle_file="$android/webrtc/build.gradle.kts"
check "download sets a connect timeout" grep -q "connectTimeout" "$gradle_file"
check "download sets a read timeout" grep -q "readTimeout" "$gradle_file"
check "rename result is checked" bash -c '! grep -q "part.renameTo(file)$" "$1" && grep -q "renameTo(file)) *{\|if (!part.renameTo\|Files.move" "$1"' _ "$gradle_file"

[ "$fails" = 0 ] && echo "all webrtc AAR tests passed" || { echo "$fails failed"; exit 1; }
