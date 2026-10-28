# Custom WebRTC build

The app's own `WebRTC.xcframework` for iPhone and Mac. The Swift package `apps/apple/Packages/WebRTCBinary` serves it to the apps. Every file under `tools/webrtc/` needs the project owner's review before merge.

## What is built

- WebRTC at the milestone, branch and commit in `VERSION`, built with `sdk:framework_objc` for 5 slices: `ios-arm64`, `ios-sim-arm64`, `ios-sim-x64`, `mac-arm64` and `mac-x64`.
- libopus 1.6 (the tarball and SHA-256 in `VERSION`) in place of WebRTC's bundled Opus (patch `0001`), with these features:

  | Feature | Needs decoder complexity |
  |---|---|
  | Deep PLC (neural packet loss concealment) | ≥ 5 |
  | OSCE LACE | ≥ 6 |
  | OSCE NoLACE | ≥ 7 |
  | OSCE BWE (bandwidth extension; also needs 48 kHz output and 16 kHz SILK) | ≥ 4, plus `OPUS_SET_OSCE_BWE(1)` |

  The decoder complexity is compiled in as exactly `7`, and BWE is on (patch `0002`). They are not configurable at runtime. DRED is off.
- 60 ms and 120 ms packet lengths. `RTCOpusInfo.maxPacketMs` reports the longest one this build supports.
- `RTCOpusInfo` (patch `0003`): reports the Opus version, whether Deep PLC, OSCE and BWE are active, the decoder complexity, and the PLC field-trial name.
- `RTCAudioCapturePostProcessing` (patch `0004`): a writable capture post-processing hook, for RNNoise in plan 8.
- Patch `0005` copies all public headers into the macOS framework.

## Runtime switch for plan 8

Deep PLC only runs if the client turns on the Opus-PLC field trial. Its name is `FIELD_TRIAL_OPUS_PLC` in `VERSION` (also `RTCOpusInfo.opusPLCFieldTrial`). Set it with `RTCInitFieldTrialDictionary` before creating the `RTCPeerConnectionFactory`.

## Files

- `VERSION`: the pins. Scripts read it with `. tools/webrtc/VERSION`.
- `apply.sh`: puts libopus and the `patches/` into a WebRTC checkout.
- `build.sh`, `package.sh`, `check-symbols.sh`: build one slice, package the XCFramework, and check exported symbols.
- `check-package-pin.sh`: checks that `Package.swift` names a published release whose asset and `VERSION` match.
- `opus-probe/`: a C probe that tells whether a libopus build has Deep PLC, OSCE and OSCE BWE.
- `*_test.sh` and `opus-probe/run-tests.sh`: Linux tests; CI runs them in the `probe` job of `.github/workflows/webrtc.yml`.
- `UPDATING.md`: how to update the pins and release.

## Building one slice locally on a Mac

```sh
sh tools/webrtc/build.sh mac-arm64 ~/webrtc-work
```

It needs Xcode, a lot of free disk space, and several hours. To test the result before a release, point the Swift package at a local XCFramework with `WEBRTC_LOCAL_XCFRAMEWORK=/path/to/WebRTC.xcframework swift test` in `apps/apple/Packages/WebRTCBinary`.

## Licenses

Each release carries `LICENSE.md`, the licenses of WebRTC and every third-party library in the framework. The build fails if it names a GPL, LGPL or AGPL license. A copy of the pinned release's `LICENSE.md` lives in `apps/apple/Packages/WebRTCBinary/THIRD_PARTY_LICENSES.md`.
