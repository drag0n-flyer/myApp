#!/bin/bash
# Runs the ServerKit package tests (including the Apple-only ones) on an iOS simulator, so the
# Keychain accessibility assertion actually runs. IOS_RUNTIME=17 (or 18) picks an iOS version.
set -euo pipefail
cd "$(dirname "$0")/../Packages/ServerKit"

UDID=$(xcrun simctl list devices available -j | IOS_RUNTIME="${IOS_RUNTIME:-}" python3 -c '
import json, os, sys
want = os.environ["IOS_RUNTIME"]
devs = json.load(sys.stdin)["devices"]
runtimes = sorted((rt for rt in devs if "iOS" in rt), reverse=True)
if want:
    runtimes = [rt for rt in runtimes if ("iOS-" + want + "-") in rt]
print(next(d["udid"] for rt in runtimes for d in devs[rt] if d["name"].startswith("iPhone")))')

xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b

RESULT="${RUNNER_TEMP:-/tmp}/serverkit-ios.xcresult"
rm -rf "$RESULT"
xcodebuild -scheme ServerKit-Package -destination "id=$UDID" \
  -resultBundlePath "$RESULT" CODE_SIGN_IDENTITY=- CODE_SIGNING_ALLOWED=YES test
