#!/bin/bash
# Generates the Xcode project and runs the Mac UI tests.
set -euo pipefail
cd "$(dirname "$0")/.."

xcodegen generate --spec project.yml

RESULT="${RUNNER_TEMP:-/tmp}/mac-ui.xcresult"
rm -rf "$RESULT"
xcodebuild -project App.xcodeproj -scheme AppMac -destination "platform=macOS" \
  -resultBundlePath "$RESULT" CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO test
