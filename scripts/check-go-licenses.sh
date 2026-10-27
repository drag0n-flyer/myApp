#!/usr/bin/env bash
# Fail when a Go dependency has a license outside the allowlist
# (docs/security/license-allowlist.md). Run from anywhere.
set -euo pipefail
cd "$(dirname "$0")/../server"
ALLOWED='Apache-2.0,MIT,BSD-2-Clause,BSD-3-Clause,ISC,Zlib,0BSD,Unlicense,MPL-2.0'
exec go run github.com/google/go-licenses/v2@v2.0.1 check ./... --allowed_licenses="$ALLOWED" \
  --ignore github.com/drag0n-flyer/myApp/server
