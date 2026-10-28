#!/bin/bash
# Runs the ServerKit package tests on an iOS simulator, so the
# Keychain accessibility assertion actually runs. IOS_RUNTIME=17 (or 18) picks an iOS version.
set -euo pipefail
APPLE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$APPLE"

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

# Keychain tests need an entitlement that unhosted SwiftPM test bundles lack, so the Apple tests
# (ServerKitAppleTests) run hosted in a throwaway test-host app (serverkit-tests.yml); the rest of
# the package tests run through the package scheme.
RESULT="${RUNNER_TEMP:-/tmp}/serverkit-ios.xcresult"
HOSTED="${RUNNER_TEMP:-/tmp}/serverkit-ios-hosted.xcresult"
rm -rf "$RESULT" "$HOSTED"
(cd "$APPLE/Packages/ServerKit" && xcodebuild -scheme ServerKit-Package -destination "id=$UDID" \
  -resultBundlePath "$RESULT" -skip-testing:ServerKitAppleTests -skip-testing:ServerKitE2ETests CODE_SIGNING_ALLOWED=NO test)

xcodegen generate --spec serverkit-tests.yml
xcodebuild -project ServerKitTestHost.xcodeproj -scheme ServerKitHosted -destination "id=$UDID" \
  -configuration Debug -resultBundlePath "$HOSTED" CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- test
