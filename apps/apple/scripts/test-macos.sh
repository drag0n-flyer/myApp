#!/bin/bash
# Generates the Xcode project and runs the Mac UI tests.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."

xcodegen generate --spec project.yml

RESULT="${RUNNER_TEMP:-/tmp}/mac-ui.xcresult"
rm -rf "$RESULT"
# The CI build is ad-hoc signed, and macOS refuses to launch an ad-hoc app that claims restricted entitlements
# (associated domains, push, keychain/app groups) without a provisioning profile: "Runningboard has returned
# error 5". So the test build signs without the entitlements files. The shipped build still uses them.
xcodebuild -project App.xcodeproj -scheme AppMac -destination "platform=macOS" \
  -configuration Debug -resultBundlePath "$RESULT" CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_ENTITLEMENTS= test

# The notification service extension must be embedded and declare the right extension point.
. "$HERE/lib-nse.sh"
check_nse AppMac "platform=macOS" "Contents/PlugIns/*.appex/Contents/Info.plist"
