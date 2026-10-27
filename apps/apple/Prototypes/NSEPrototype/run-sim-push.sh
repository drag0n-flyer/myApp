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
  CODE_SIGN_IDENTITY=- > build.log 2>&1 || { grep -E "error:" build.log | head -30; tail -n 30 build.log; exit 1; }

xcrun simctl boot "$udid" || true
xcrun simctl bootstatus "$udid" -b
xcrun simctl install "$udid" build/Build/Products/Debug-iphonesimulator/NSEProtoHost.app
xcrun simctl launch "$udid" "$BUNDLE" -prepare
sleep 3
container="$(xcrun simctl get_app_container "$udid" "$BUNDLE" "$GROUP")"
cat "$container/prepare-result.txt"; echo
xcrun simctl push "$udid" "$BUNDLE" "$container/push.apns"
sleep 5
result="$(cat "$container/nse-result.txt" 2>/dev/null || echo "no nse-result.txt")"
echo "nse-result: $result"
case "$result" in
  "OK hello from MLS"*) echo "PASS" ;;
  *)
    echo "FAIL"
    ls -la "$container"
    xcrun simctl spawn "$udid" log show --last 3m --style compact \
      --predicate 'process == "NSEProtoService" OR process == "usernotificationsd" OR process == "NSEProtoHost"' | tail -n 60 || true
    exit 1 ;;
esac
