# NSE prototype: physical iPhone checklist

Each item is one line. Mark pass or fail. Needs a Mac with Xcode, `xcodegen`, an iPhone, and an Apple developer team (the owner's own).

## Setup

1. Create `apps/apple/Prototypes/NSEPrototype/Local.xcconfig` (git-ignored) with two lines: `DEVELOPMENT_TEAM = <your team ID>` and `CODE_SIGN_IDENTITY = Apple Development`. Never commit it.
2. In `apps/apple/Prototypes/NSEPrototype`, run `xcodegen`, then open `NSEPrototype.xcodeproj`.
3. Select the `NSEProtoHost` scheme and your iPhone, then Run. Xcode creates the app, the extension, the app group `group.org.example.app.nseproto` and the push entitlement for your team. If it reports the bundle ids are taken, change `bundleIdPrefix` and the group id on your own copy.
4. To prepare the push: stop the app, then edit the scheme, Run, Arguments, and add `-prepare`. Run once more. This writes `push.apns` into the app group container. Remove the argument afterwards.
5. Launch the app normally. Allow notifications. The screen shows the device token. Tap Copy.

## Sending the test push

Lock the iPhone, then send `push.apns` (take the file from the container with Xcode, Window, Devices and Simulators, the app's gear menu, Download Container, then Show Package Contents, AppGroup). Use one of:

- The project's push gateway, with the token you copied (see the server docs for its test endpoint).
- A plain `curl` to the APNs sandbox with your own key. You make the JWT from your own `.p8` key, team ID and key ID. Do not paste the key anywhere in the repo:

```
curl --http2 -X POST \
  -H "authorization: bearer $JWT" \
  -H "apns-topic: org.example.app.nseproto" \
  -H "apns-push-type: alert" \
  -d @push.apns \
  https://api.sandbox.push.apple.com/3/device/$TOKEN
```

(The `Simulator Target Bundle` key in the file is ignored by APNs.)

## Checks

- [ ] The notification shows `hello from MLS` while the phone is locked after first unlock.
- [ ] `nse-result.txt` in the app group container starts with `OK hello from MLS` and reports `mem=` in MB (memory used, below 24).
- [ ] After a reboot, without unlocking, send the push again: the fallback `New message` is shown (expected).
- [ ] Instruments, Allocations, attached to the extension: peak memory stays below 24 MB.
- [ ] On the device, `LiveMLSEngineTests.testStorageProtectionClass` passes (the simulator has no data protection, so CI skips it).
