#!/bin/bash
# Generates the Xcode project and runs the iPhone UI tests on an available simulator.
# IOS_RUNTIME=17 (or 18) picks a simulator with that iOS version when one is installed.
set -euo pipefail
cd "$(dirname "$0")/.."

xcodegen generate --spec project.yml

UDID=$(xcrun simctl list devices available -j | IOS_RUNTIME="${IOS_RUNTIME:-}" python3 -c '
import json, os, sys
want = os.environ["IOS_RUNTIME"]
devs = json.load(sys.stdin)["devices"]
runtimes = sorted((rt for rt in devs if "iOS" in rt), reverse=True)
if want:
    runtimes = [rt for rt in runtimes if ("iOS-" + want + "-") in rt]
print(next(d["udid"] for rt in runtimes for d in devs[rt] if d["name"].startswith("iPhone")))')

# Boot and wait first: XCTest against a still-booting simulator fails to initialize UI testing.
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b

RESULT="${RUNNER_TEMP:-/tmp}/ios-ui.xcresult"
rm -rf "$RESULT"
xcodebuild -project App.xcodeproj -scheme AppiOS -destination "id=$UDID" \
  -resultBundlePath "$RESULT" CODE_SIGNING_ALLOWED=NO test

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
check_nse AppiOS "id=$UDID" "PlugIns/*.appex/Info.plist"
