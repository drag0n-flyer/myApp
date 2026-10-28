#!/bin/bash
# Generates the Xcode project and runs the Mac UI tests.
set -euo pipefail
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPTS/.."

xcodegen generate --spec project.yml

RESULT="${RUNNER_TEMP:-/tmp}/mac-ui.xcresult"
rm -rf "$RESULT"
xcodebuild -project App.xcodeproj -scheme AppMac -destination "platform=macOS" \
  -configuration Debug -resultBundlePath "$RESULT" CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_ENTITLEMENTS="$SCRIPTS/ci-mac.entitlements" test

# The notification service extension must be embedded and declare the right extension point.
. "$SCRIPTS/lib-nse.sh"
check_nse AppMac "platform=macOS" "Contents/PlugIns/*.appex/Contents/Info.plist"
