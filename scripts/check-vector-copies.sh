#!/usr/bin/env bash
# The Swift tests read content-v1.json and safety-v1.json from the Messaging test resources. The files in
# docs/protocol/test-vectors are the originals; fail when a copy differs.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/docs/protocol/test-vectors"
dst="$root/apps/apple/Packages/Messaging/Tests/MessagingTests/Resources"
status=0
for f in content-v1.json safety-v1.json; do
  if ! cmp -s "$src/$f" "$dst/$f"; then
    echo "differs: $f (docs/protocol/test-vectors vs Messaging test resources)" >&2
    status=1
  fi
done
exit $status
