#!/bin/sh
# Puts libopus 1.6 and our patches into a fresh WebRTC checkout.
#
# Usage: sh tools/webrtc/apply.sh <webrtc-src-dir>
#
# Steps:
#   1. read VERSION
#   2. check the SHA-256 of the opus tarball (exit 2 on a mismatch)
#   3. replace third_party/opus/src with the tarball contents
#   4. write third_party/opus/dnn_sources.gni (gen_dnn_gni.py)
#   5. copy the Opus probe into sdk/objc/components/app/ (Apple) or
#      sdk/android/src/jni/app/ (Android)
#   6. apply patches/0001-* inside third_party, the other patches inside src
#      (Apple: not 0005 and 0006; Android: not 0003 and 0004)
#   7. fail on any reject or conflict marker
#   8. fail (exit 3) if the Opus PLC field trial named in VERSION
#      (FIELD_TRIAL_OPUS_PLC) is not in audio_decoder_opus.cc at this pin
#
# Run it once on a clean checkout. On any failure it resets the tracked files
# in third_party and src to HEAD, so running it again starts clean. When it
# succeeds it writes .app-webrtc-applied (the VERSION hash) into <webrtc-src-dir>;
# a second run then prints "already applied" and exits 0.
#
# Platform (environment): APPLY_PLATFORM=apple (the default) or android.
# 0001 and 0002 are C/C++ and apply on both. 0003 and 0004 are Objective-C
# (Apple only). 0005 and 0006 are Java and JNI (Android only).
#
# Test overrides (environment): OPUS_SHA256, APPLY_PATCH_DIR,
# APPLY_THIRD_PARTY_PATCH, APPLY_CACHE_DIR.
#
# Exit codes: 0 ok, 1 failure, 2 tarball hash mismatch, 3 Opus PLC field trial missing.
# Needs: git, tar, python3, curl (only to download), sha256sum or shasum.
set -eu

here=$(cd "$(dirname "$0")" && pwd)

die() {
  echo "error: $*" >&2
  exit 1
}

if [ "$#" -ne 1 ]; then
  echo "usage: sh tools/webrtc/apply.sh <webrtc-src-dir>" >&2
  exit 1
fi
[ -d "$1" ] || die "$1 is not a directory"
platform=${APPLY_PLATFORM:-apple}
case "$platform" in
  apple) skip_patches="0005 0006"; probe_dir=sdk/objc/components/app ;;
  android) skip_patches="0003 0004"; probe_dir=sdk/android/src/jni/app ;;
  *) die "unknown APPLY_PLATFORM '$platform' (apple or android)" ;;
esac
srcdir=$(cd "$1" && pwd)

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f 1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d ' ' -f 1
  else
    die "need sha256sum or shasum"
  fi
}

# 1. VERSION. An OPUS_SHA256 in the environment wins (tests only).
override_sha=${OPUS_SHA256-}
. "$here/VERSION"
if [ -n "$override_sha" ]; then
  OPUS_SHA256=$override_sha
fi
version_hash=$(sha256_of "$here/VERSION")

marker="$srcdir/.app-webrtc-applied"
if [ -f "$marker" ]; then
  if [ "$(cat "$marker")" = "$version_hash" ]; then
    echo "already applied"
    exit 0
  fi
  die "$marker holds a different VERSION hash; start from a fresh checkout"
fi

patch_dir=${APPLY_PATCH_DIR:-$here/patches}
cache_dir=${APPLY_CACHE_DIR:-${TMPDIR:-/tmp}/app-webrtc-cache}
tp="$srcdir/third_party"

[ -d "$tp/opus" ] || die "$tp/opus not found; is $srcdir a WebRTC src directory?"
git -C "$tp" rev-parse --git-dir >/dev/null 2>&1 || die "$tp is not a git repository"
git -C "$srcdir" rev-parse --git-dir >/dev/null 2>&1 || die "$srcdir is not a git repository"
for repo in "$tp" "$srcdir"; do
  git -C "$repo" diff --quiet HEAD || die "$repo has uncommitted changes to tracked files; use a clean checkout"
done

# 2. Tarball and hash.
mkdir -p "$cache_dir"
tarball="$cache_dir/opus-$OPUS_VERSION.tar.gz"
if [ ! -f "$tarball" ]; then
  curl -fsSL --retry 8 --retry-all-errors --retry-delay 15 --connect-timeout 30 -o "$tarball.part" "$OPUS_URL" || {
    rm -f "$tarball.part"
    die "download of $OPUS_URL failed"
  }
  actual=$(sha256_of "$tarball.part")
  if [ "$actual" != "$OPUS_SHA256" ]; then
    rm -f "$tarball.part"
    echo "error: SHA-256 mismatch for the download of $OPUS_URL" >&2
    echo "  expected $OPUS_SHA256" >&2
    echo "  actual   $actual" >&2
    exit 2
  fi
  mv "$tarball.part" "$tarball"
fi
actual=$(sha256_of "$tarball")
if [ "$actual" != "$OPUS_SHA256" ]; then
  echo "error: SHA-256 mismatch for $tarball" >&2
  echo "  expected $OPUS_SHA256" >&2
  echo "  actual   $actual" >&2
  echo "  If the file is corrupt, delete it and run again." >&2
  exit 2
fi

restore_and_fail() {
  echo "error: $*" >&2
  git -C "$tp" reset -q --hard HEAD || true
  git -C "$srcdir" reset -q --hard HEAD || true
  exit 1
}

# 3. Replace third_party/opus/src.
rm -rf "$tp/opus/src"
mkdir "$tp/opus/src"
tar -xzf "$tarball" -C "$tp/opus/src" --strip-components=1 || die "cannot unpack $tarball"
for f in lpcnet_sources.mk package_version dnn/nolace_data.c; do
  [ -f "$tp/opus/src/$f" ] || die "$f is missing from the unpacked tarball"
done
pkg_version=$(sed -n 's/^PACKAGE_VERSION="\(.*\)"$/\1/p' "$tp/opus/src/package_version")
[ "$pkg_version" = "$OPUS_VERSION" ] ||
  die "tarball says opus $pkg_version, VERSION says $OPUS_VERSION"

# 4. dnn_sources.gni.
python3 -I "$here/opus/gen_dnn_gni.py" "$tp/opus/src/lpcnet_sources.mk" \
  "$tp/opus/dnn_sources.gni" --version "$pkg_version" || die "gen_dnn_gni.py failed"

# 5. The Opus probe.
mkdir -p "$srcdir/$probe_dir"
cp "$here/opus-probe/opus_probe.c" "$here/opus-probe/opus_probe.h" "$srcdir/$probe_dir/"

# 6. Patches. 0001 is relative to third_party (git diff --no-prefix, so -p0);
#    the others apply inside src.
third_party_patch=${APPLY_THIRD_PARTY_PATCH:-}
if [ -z "$third_party_patch" ]; then
  for p in "$patch_dir"/0001-*.patch; do
    [ -f "$p" ] && third_party_patch=$p
  done
fi
[ -n "$third_party_patch" ] || die "no 0001-*.patch in $patch_dir"
[ -f "$third_party_patch" ] || die "$third_party_patch not found"

git -C "$tp" apply --3way -p0 "$third_party_patch" ||
  restore_and_fail "$(basename "$third_party_patch") did not apply cleanly"
for p in "$patch_dir"/*.patch; do
  [ -f "$p" ] || continue
  [ "$p" = "$third_party_patch" ] && continue
  case "$(basename "$p")" in 0001-*) continue ;; esac
  num=$(basename "$p" | cut -c 1-4)
  case " $skip_patches " in *" $num "*) continue ;; esac
  git -C "$srcdir" apply --3way "$p" || restore_and_fail "$(basename "$p") did not apply cleanly"
done

# 7. Conflict markers in any file the patches touched.
for repo in "$tp" "$srcdir"; do
  for f in $(git -C "$repo" diff --name-only HEAD); do
    if grep -n '^<<<<<<<' "$repo/$f" >/dev/null 2>&1; then
      restore_and_fail "conflict marker in $repo/$f"
    fi
  done
done

# 8. Build guard: the Opus PLC field trial must still exist at this pin.
grep -q "\"$FIELD_TRIAL_OPUS_PLC\"" "$srcdir/modules/audio_coding/codecs/opus/audio_decoder_opus.cc" 2>/dev/null || {
  echo "Opus PLC field trial '$FIELD_TRIAL_OPUS_PLC' not found at pin" >&2
  git -C "$tp" reset -q --hard HEAD || true
  git -C "$srcdir" reset -q --hard HEAD || true
  exit 3
}

printf '%s\n' "$version_hash" >"$marker"
echo "applied opus $pkg_version and patches ($platform) to $srcdir"
