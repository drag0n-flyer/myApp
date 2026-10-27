#!/bin/sh
# Builds libopus twice from the hash-pinned release tarball and runs the feature
# probe against both builds:
#   features  Deep PLC + OSCE on, DRED off   -> every probe must report 1
#   plain     neither                        -> every probe must report 0
# The plain build is the negative control: it proves the probe can fail.
# Needs: curl, tar, cmake, a C compiler, sha256sum or shasum.
# Exit codes: 0 pass, 1 test failure, 2 tarball hash mismatch.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
. "$here/../VERSION"

work="${TMPDIR:-/tmp}/opus-probe"
tarball="$work/opus-$OPUS_VERSION.tar.gz"
src="$work/opus-$OPUS_VERSION"
cc="${CC:-cc}"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f 1
  else
    shasum -a 256 "$1" | cut -d ' ' -f 1
  fi
}

mkdir -p "$work"
if [ ! -f "$tarball" ]; then
  curl -fsSL -o "$tarball.part" "$OPUS_URL"
  mv "$tarball.part" "$tarball"
fi

actual=$(sha256_of "$tarball")
if [ "$actual" != "$OPUS_SHA256" ]; then
  echo "error: SHA-256 mismatch for $tarball" >&2
  echo "  expected $OPUS_SHA256" >&2
  echo "  actual   $actual" >&2
  rm -f "$tarball"
  exit 2
fi

rm -rf "$src"
tar -xzf "$tarball" -C "$work"

# build_opus <name> <cmake options...>: static libopus into $work/<name>
build_opus() {
  name=$1
  shift
  rm -rf "$work/$name"
  cmake -S "$src" -B "$work/$name" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF \
    -DOPUS_INSTALL_PKG_CONFIG_MODULE=OFF -DOPUS_INSTALL_CMAKE_CONFIG_MODULE=OFF \
    "$@" >"$work/$name.configure.log" 2>&1 ||
    { cat "$work/$name.configure.log" >&2; exit 1; }
  cmake --build "$work/$name" --parallel >"$work/$name.build.log" 2>&1 ||
    { tail -n 40 "$work/$name.build.log" >&2; exit 1; }
}

# run_probe <name> <expect-on|expect-off>: compile the probe against build <name>
run_probe() {
  name=$1
  expectation=$2
  echo "== $name ($expectation)"
  "$cc" -std=c99 -O1 -Wall -Wextra -Werror -I"$src/include" -I"$here" \
    -o "$work/probe_test-$name" \
    "$here/probe_test.c" "$here/opus_probe.c" \
    "$work/$name/libopus.a" -lm || return 1
  "$work/probe_test-$name" "$expectation"
}

# libopus 1.6's CMake build defines ENABLE_DEEP_PLC and ENABLE_OSCE but not
# ENABLE_OSCE_BWE (only configure.ac does), so pass it by hand. The WebRTC build
# defines it explicitly in its own BUILD.gn patch for the same reason.
build_opus features -DOPUS_DEEP_PLC=ON -DOPUS_OSCE=ON -DOPUS_DRED=OFF \
  -DCMAKE_C_FLAGS=-DENABLE_OSCE_BWE
build_opus plain

status=0
run_probe features expect-on || status=1
run_probe plain expect-off || status=1
exit $status
