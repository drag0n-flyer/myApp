#!/bin/bash
# Builds the prototype for the simulator, installs it, lets the host app prepare a push, sends
# the push with `simctl push`, and checks that the extension decrypted it.
set -euo pipefail
cd "$(dirname "$0")"

BUNDLE=org.example.app.nseproto
GROUP=group.org.example.app.nseproto

xcodegen
udid="$(xcrun simctl list devices available iPhone | grep -m1 -oE '[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}')"
test -n "$udid"
xcodebuild build -project NSEPrototype.xcodeproj -scheme NSEProtoHost \
  -destination "platform=iOS Simulator,id=$udid" -derivedDataPath build \
  CODE_SIGN_IDENTITY=- | tail -n 30
test "${PIPESTATUS[0]}" -eq 0

xcrun simctl boot "$udid" || true
xcrun simctl bootstatus "$udid" -b
xcrun simctl install "$udid" build/Build/Products/Debug-iphonesimulator/NSEProtoHost.app
xcrun simctl launch "$udid" "$BUNDLE" -prepare
sleep 3
container="$(xcrun simctl get_app_container "$udid" "$BUNDLE" "$GROUP")"
cat "$container/prepare-result.txt"; echo
xcrun simctl push "$udid" "$BUNDLE" "$container/push.apns"
sleep 5
result="$(cat "$container/nse-result.txt")"
echo "nse-result: $result"
case "$result" in
  "OK hello from MLS"*) echo "PASS" ;;
  *) echo "FAIL"; exit 1 ;;
esac
