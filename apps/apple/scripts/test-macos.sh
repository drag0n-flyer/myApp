#!/bin/bash
# Generates the Xcode project and runs the Mac UI tests.
set -euo pipefail
cd "$(dirname "$0")/.."

xcodegen generate --spec project.yml

RESULT="${RUNNER_TEMP:-/tmp}/mac-ui.xcresult"
rm -rf "$RESULT"
xcodebuild -project App.xcodeproj -scheme AppMac -destination "platform=macOS" \
  -resultBundlePath "$RESULT" CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO test

# The notification service extension must be embedded and declare the right extension point.
check_nse() {
  local dir plist got
  dir=$(xcodebuild -project App.xcodeproj -scheme "$1" -destination "$2" -showBuildSettings 2>/dev/null \
    | awk -F' = ' '/^ *BUILT_PRODUCTS_DIR = /{print $2; exit}')
  plist=$(ls "$dir"/App.app/$3 2>/dev/null | head -1)
  [ -n "$plist" ] || { echo "NSE Info.plist not found under $dir/App.app/$3" >&2; exit 1; }
  got=$(plutil -extract NSExtension.NSExtensionPointIdentifier raw "$plist")
  [ "$got" = "com.apple.usernotifications.service" ] || { echo "NSE extension point is '$got'" >&2; exit 1; }
  echo "NSE extension point OK"
}
check_nse AppMac "platform=macOS" "Contents/PlugIns/*.appex/Contents/Info.plist"
