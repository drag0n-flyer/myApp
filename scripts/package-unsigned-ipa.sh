#!/bin/bash
# Package an already-built device app. No signing credentials are needed.
set -euo pipefail
APP="$1"
OUTPUT="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

xcrun lipo "$APP/App" -verify_arch arm64
xcrun lipo "$APP/PlugIns/NotificationService.appex/NotificationService" -verify_arch arm64
mkdir -p "$STAGE/Payload" "$(dirname "$OUTPUT")"
ditto "$APP" "$STAGE/Payload/App.app"
ditto -c -k --keepParent "$STAGE/Payload" "$OUTPUT"
python3 "$HERE/check_unsigned_ipa.py" "$OUTPUT"
