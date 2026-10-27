#!/bin/sh
# Tests for apply.sh against fixture trees. Run: sh tools/webrtc/apply_test.sh
# Needs git, tar, python3 and the opus 1.6 tarball (downloaded and hash-checked
# by apply.sh; reuses the one opus-probe/run-tests.sh caches when present).
# Exit codes: 0 all pass, 1 a test failed.
set -u

here=$(cd "$(dirname "$0")" && pwd)
. "$here/VERSION"

work=$(mktemp -d "${TMPDIR:-/tmp}/apply-test.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM

if [ -f "${TMPDIR:-/tmp}/opus-probe/opus-$OPUS_VERSION.tar.gz" ]; then
  APPLY_CACHE_DIR="${TMPDIR:-/tmp}/opus-probe"
else
  APPLY_CACHE_DIR="$work/cache"
fi
export APPLY_CACHE_DIR

failures=0
pass() { echo "PASS $1"; }
fail() {
  echo "FAIL $1: $2"
  failures=$((failures + 1))
}
check() { # check <name> <description> <command...>
  name=$1
  desc=$2
  shift 2
  if "$@"; then pass "$name: $desc"; else fail "$name" "$desc"; fi
}

g() { git -c user.name=test -c user.email=test@example.com -c commit.gpgsign=false "$@"; }

# new_tree <dir>: fixture WebRTC tree.
#   <dir>/third_party/opus/BUILD.gn   5 lines, in its own git repo
#   <dir>/hello.txt                   in the git repo <dir>
#   <dir>/modules/audio_coding/codecs/opus/audio_decoder_opus.cc
#                                     holds the Opus PLC trial name (VERSION) unless $2 is "notrial"
new_tree() {
  mkdir -p "$1/third_party/opus"
  printf 'one\ntwo\nthree\nfour\nfive\n' >"$1/third_party/opus/BUILD.gn"
  g -C "$1/third_party" init -q
  g -C "$1/third_party" add opus/BUILD.gn
  g -C "$1/third_party" commit -q -m base
  printf 'hello\nworld\n' >"$1/hello.txt"
  mkdir -p "$1/modules/audio_coding/codecs/opus"
  if [ "${2-}" = notrial ]; then
    printf 'generate_plc_(field_trials.IsEnabled("Some-Other-Trial"))\n' >"$1/modules/audio_coding/codecs/opus/audio_decoder_opus.cc"
  else
    printf 'generate_plc_(field_trials.IsEnabled("%s"))\n' "$FIELD_TRIAL_OPUS_PLC" >"$1/modules/audio_coding/codecs/opus/audio_decoder_opus.cc"
  fi
  g -C "$1" init -q
  g -C "$1" add hello.txt modules
  g -C "$1" commit -q -m base
}

# make_patches <tree> <patch-dir>: stand-in patches that apply to that tree.
# 0001-fixture.patch is relative to third_party (git diff --no-prefix).
make_patches() {
  mkdir -p "$2"
  printf 'one\ntwo\nTHREE\nfour\nfive\nsix\n' >"$1/third_party/opus/BUILD.gn"
  g -C "$1/third_party" diff --no-prefix >"$2/0001-fixture.patch"
  g -C "$1/third_party" checkout -q -- opus/BUILD.gn
  printf 'hello\nworld\nagain\n' >"$1/hello.txt"
  g -C "$1" diff >"$2/0002-fixture.patch"
  g -C "$1" checkout -q -- hello.txt
}

run_apply() { # run_apply <tree> <patch-dir>; sets out and code
  out=$(APPLY_PATCH_DIR="$2" APPLY_THIRD_PARTY_PATCH="$2/0001-fixture.patch" \
    sh "$here/apply.sh" "$1" 2>&1)
  code=$?
}

no_markers() { ! grep -rn '^<<<<<<<' "$1" --exclude-dir=.git --exclude-dir=src >/dev/null 2>&1; }
has_line() { grep -q "$1" "$2"; }

# apply_ok
t="$work/ok"
new_tree "$t"
make_patches "$t" "$work/ok-patches"
run_apply "$t" "$work/ok-patches"
check apply_ok "exit 0" test "$code" -eq 0
check apply_ok "opus 1.6 unpacked (nolace_data.c)" test -f "$t/third_party/opus/src/dnn/nolace_data.c"
check apply_ok "dnn_sources.gni written" has_line '"src/dnn/nolace_data.c"' "$t/third_party/opus/dnn_sources.gni"
check apply_ok "third_party patch applied" has_line '^THREE$' "$t/third_party/opus/BUILD.gn"
check apply_ok "src patch applied" has_line '^again$' "$t/hello.txt"
check apply_ok "probe copied" test -f "$t/sdk/objc/components/app/opus_probe.c" -a -f "$t/sdk/objc/components/app/opus_probe.h"
check apply_ok "marker holds the VERSION hash" test "$(cat "$t/.app-webrtc-applied" 2>/dev/null)" = "$(
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$here/VERSION"; else shasum -a 256 "$here/VERSION"; fi | cut -d ' ' -f 1
)"

# idempotent (second run on the tree from apply_ok)
before=$(cat "$t/third_party/opus/BUILD.gn")
run_apply "$t" "$work/ok-patches"
check idempotent "exit 0" test "$code" -eq 0
check idempotent "prints 'already applied'" test "$out" = "already applied"
check idempotent "BUILD.gn unchanged" test "$(cat "$t/third_party/opus/BUILD.gn")" = "$before"

# bad_hash
t="$work/bad"
new_tree "$t"
make_patches "$t" "$work/bad-patches"
out=$(OPUS_SHA256=0000000000000000000000000000000000000000000000000000000000000000 \
  APPLY_PATCH_DIR="$work/bad-patches" APPLY_THIRD_PARTY_PATCH="$work/bad-patches/0001-fixture.patch" \
  sh "$here/apply.sh" "$t" 2>&1)
code=$?
check bad_hash "exit 2" test "$code" -eq 2
check bad_hash "mentions mismatch" sh -c 'printf "%s" "$1" | grep -q "SHA-256 mismatch"' _ "$out"
check bad_hash "tree untouched (no opus/src, no marker)" test ! -e "$t/third_party/opus/src" -a ! -e "$t/.app-webrtc-applied"

# reject_fails: patch context does not match
t="$work/rej"
new_tree "$t"
make_patches "$t" "$work/rej-patches"
cat >"$work/rej-patches/0001-fixture.patch" <<'PATCH'
diff --git opus/BUILD.gn opus/BUILD.gn
index 1111111..2222222 100644
--- opus/BUILD.gn
+++ opus/BUILD.gn
@@ -1,5 +1,5 @@
 x
 y
-three
+THREE
 z
 w
PATCH
run_apply "$t" "$work/rej-patches"
check reject_fails "non-zero exit" test "$code" -ne 0
check reject_fails "no conflict markers left" no_markers "$t"
check reject_fails "no marker file" test ! -e "$t/.app-webrtc-applied"
check reject_fails "BUILD.gn restored" test "$(cat "$t/third_party/opus/BUILD.gn")" = "$(printf 'one\ntwo\nthree\nfour\nfive')"
# After the failure a corrected patch applies, so the tree can be retried.
make_patches "$t" "$work/rej-patches"
run_apply "$t" "$work/rej-patches"
check reject_fails "retry with a good patch succeeds" test "$code" -eq 0

# conflict_fails: 3-way merge conflicts must be cleaned up too
t="$work/conf"
new_tree "$t"
make_patches "$t" "$work/conf-patches"
printf 'one\ntwo\nOTHER\nfour\nfive\n' >"$t/third_party/opus/BUILD.gn"
g -C "$t/third_party" commit -q -am other
run_apply "$t" "$work/conf-patches"
check conflict_fails "non-zero exit" test "$code" -ne 0
check conflict_fails "no conflict markers left" no_markers "$t"
check conflict_fails "BUILD.gn back at HEAD" test "$(cat "$t/third_party/opus/BUILD.gn")" = "$(printf 'one\ntwo\nOTHER\nfour\nfive')"

# missing_trial_fails: the Opus PLC field trial is not in audio_decoder_opus.cc
t="$work/notrial"
new_tree "$t" notrial
make_patches "$t" "$work/notrial-patches"
run_apply "$t" "$work/notrial-patches"
check missing_trial_fails "exit 3" test "$code" -eq 3
check missing_trial_fails "names the trial" sh -c 'printf "%s" "$1" | grep -q "Opus PLC field trial"' _ "$out"
check missing_trial_fails "no marker file" test ! -e "$t/.app-webrtc-applied"
check missing_trial_fails "patched files restored" test "$(cat "$t/hello.txt")" = "$(printf 'hello\nworld')"

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all apply tests passed"
