#!/bin/bash
# Generates the Xcode project and runs the Mac UI tests.
set -euo pipefail
cd "$(dirname "$0")/.."

xcodegen generate --spec project.yml

RESULT="${RUNNER_TEMP:-/tmp}/mac-ui.xcresult"
rm -rf "$RESULT"
xcodebuild -project App.xcodeproj -scheme AppMac -destination "platform=macOS" \
  -configuration Debug -resultBundlePath "$RESULT" CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_ENTITLEMENTS="$PWD/scripts/ci-mac.entitlements" test

# The notification service extension must be embedded and declare the right extension point.
. "$(dirname "$0")/lib-nse.sh"
check_nse AppMac "platform=macOS" "Contents/PlugIns/*.appex/Contents/Info.plist"
