#!/bin/bash
# Fails if the app project links the test-only ServerKitTesting library.
set -euo pipefail
cd "$(dirname "$0")/.."
if grep -n "ServerKitTesting" project.yml; then
  echo "error: project.yml must not mention ServerKitTesting (test-only library)" >&2
  exit 1
fi
echo "ok: no test-only libraries in project.yml"
