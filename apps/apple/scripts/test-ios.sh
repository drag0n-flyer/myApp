#!/bin/bash
# Generates the Xcode project and runs the iPhone UI tests on an available simulator.
# IOS_RUNTIME=17 (or 18) picks a simulator with that iOS version when one is installed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."

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
  -configuration Debug -resultBundlePath "$RESULT" CODE_SIGNING_ALLOWED=NO test

# The notification service extension must be embedded and declare the right extension point.
. "$HERE/lib-nse.sh"
check_nse AppiOS "id=$UDID" "PlugIns/*.appex/Info.plist"
check_app_ids AppiOS "id=$UDID" "Info.plist" "" "PlugIns/*.appex/Info.plist" .nse
