#!/bin/sh
# Checks that apps/apple/Packages/WebRTCBinary/Package.swift points at the
# right release of our WebRTC build.
#
# Usage: sh tools/webrtc/check-package-pin.sh
#
# Steps:
#   1. read the url: and checksum: from Package.swift
#   2. download that asset and the release's VERSION with gh release download
#   3. exit 0 only if the asset's checksum (swift package compute-checksum on
#      macOS, shasum -a 256 or sha256sum elsewhere) equals the checksum, and the
#      release's VERSION equals tools/webrtc/VERSION byte for byte
#
# Test overrides (environment): PIN_PACKAGE_SWIFT, PIN_VERSION_FILE, and
# PIN_DOWNLOAD_DIR (a folder that already holds the asset and VERSION; gh is
# then not called).
#
# Exit codes: 0 ok, 1 failure (mismatch, missing release, bad Package.swift).
# Needs: gh (with GH_TOKEN in CI), and swift, shasum or sha256sum.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
package_swift=${PIN_PACKAGE_SWIFT:-$root/apps/apple/Packages/WebRTCBinary/Package.swift}
version_file=${PIN_VERSION_FILE:-$here/VERSION}

die() {
  echo "error: $*" >&2
  exit 1
}

[ -f "$package_swift" ] || die "$package_swift not found"
[ -f "$version_file" ] || die "$version_file not found"

# 1. url and checksum (the binaryTarget's, not WEBRTC_LOCAL_XCFRAMEWORK's path).
url=$(sed -n 's/^[[:space:]]*url:[[:space:]]*"\([^"]*\)".*/\1/p' "$package_swift" | head -n 1)
checksum=$(sed -n 's/^[[:space:]]*checksum:[[:space:]]*"\([^"]*\)".*/\1/p' "$package_swift" | head -n 1)
[ -n "$url" ] || die "no url: in $package_swift"
[ -n "$checksum" ] || die "no checksum: in $package_swift"

# https://github.com/<owner>/<repo>/releases/download/<tag>/<asset>
repo=$(echo "$url" | sed -n 's#^https://github\.com/\([^/]*/[^/]*\)/releases/download/.*#\1#p')
tag=$(echo "$url" | sed -n 's#^https://github\.com/[^/]*/[^/]*/releases/download/\([^/]*\)/.*#\1#p')
asset=${url##*/}
[ -n "$repo" ] && [ -n "$tag" ] && [ -n "$asset" ] ||
  die "url is not a GitHub release asset: $url"
echo "Package.swift pins $repo release $tag, asset $asset"

# 2. download.
if [ -n "${PIN_DOWNLOAD_DIR-}" ]; then
  dir=$PIN_DOWNLOAD_DIR
else
  dir=$(mktemp -d "${TMPDIR:-/tmp}/pin-check.XXXXXX")
  trap 'rm -rf "$dir"' EXIT INT TERM
  if ! gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
    die "release $tag not found in $repo. It is not published yet, or Package.swift names the wrong tag."
  fi
  gh release download "$tag" --repo "$repo" --dir "$dir" --pattern "$asset" --pattern VERSION ||
    die "gh release download $tag failed"
fi
[ -f "$dir/$asset" ] || die "release $tag has no asset $asset"
[ -f "$dir/VERSION" ] || die "release $tag has no VERSION asset"

# 3. compare.
if [ "$(uname -s)" = Darwin ] && command -v swift >/dev/null 2>&1; then
  actual=$(swift package compute-checksum "$dir/$asset")
elif command -v shasum >/dev/null 2>&1; then
  actual=$(shasum -a 256 "$dir/$asset" | cut -d' ' -f1)
else
  actual=$(sha256sum "$dir/$asset" | cut -d' ' -f1)
fi

status=0
if [ "$actual" != "$checksum" ]; then
  echo "checksum mismatch: Package.swift has $checksum, $asset in $tag is $actual" >&2
  status=1
fi
if ! cmp -s "$dir/VERSION" "$version_file"; then
  echo "VERSION mismatch: the VERSION in release $tag differs from tools/webrtc/VERSION" >&2
  diff "$dir/VERSION" "$version_file" >&2 || true
  status=1
fi
[ "$status" -eq 0 ] && echo "ok: Package.swift matches release $tag"
exit "$status"
