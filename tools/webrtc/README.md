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

## Android

The Android build uses the same pin (`VERSION`) and the same patched Opus 1.6 as the Apple build, and runs on Linux only.

- `build-android.sh <abi>` (workdir: `$WEBRTC_WORKDIR`, default `./webrtc-android`) (`arm64-v8a`, `armeabi-v7a` or `x86_64`): fetches `webrtc_android`, runs `APPLY_PLATFORM=android apply.sh`, builds with gn args from `build-android.sh --print-gn-args <abi>`, and copies `libjingle_peerconnection_so.so` and `libwebrtc.jar` to `<workdir>/out/android/<abi>/`, plus `AndroidManifest.xml` and `LICENSE.md` to `<workdir>/out/android/`. arm64 has no dot-product flags (ARMv8.0 devices such as the Snapdragon 835).
- `package-android.sh <workdir>/out/android`: writes `libwebrtc.aar` (with `jni/arm64-v8a`, `jni/armeabi-v7a` and `jni/x86_64` only) and `libwebrtc.aar.sha256`. It fails if an ABI is missing or extra, if `LICENSE.md` names a GNU license, or if the jar lacks `org.webrtc.OpusInfo` or `org.webrtc.AudioCapturePostProcessing`. The AAR is reproducible.
- `apply.sh` applies patches 0001 and 0002 (C/C++) on both platforms, 0003, 0004 and 0005 (Objective-C and the Mac headers) only on Apple (the default), and 0006, 0007 and 0008 (Java and JNI) only with `APPLY_PLATFORM=android`.
- `patches/0006-android-opus-info.patch`: `org.webrtc.OpusInfo` with `decoderComplexity()`, `hasDeepPlc()`, `hasOsce()` and `maxPacketMs()`. Call them after `PeerConnectionFactory.initialize` has loaded the library.
- `patches/0007-android-capture-post-processing.patch`: `org.webrtc.AudioCapturePostProcessing`, an `AudioProcessingFactory` for `PeerConnectionFactory.Builder.setAudioProcessingFactory`. It is the Android form of patch 0004 (the Android SDK has no upstream equivalent at the pin). The processor is native code behind the C struct `AppAudioCaptureProcessor` (`sdk/android/src/jni/app/audio_capture_processor.h`), so audio never crosses JNI and no symbol has to be exported from the hidden-visibility library. It also makes `PeerConnectionFactory` take over the reference `createNative` returns instead of leaking it.
- `patches/0008-android-opus-encoder.patch`: `org.webrtc.OpusVoiceEncoder`, the voice-note encoder for phones without a platform Opus encoder (the API 29 AOSP image has none): 16 kHz mono, 16 kbps, 20 ms frames, `OPUS_APPLICATION_VOIP`, `preSkip()` 312 (from `OPUS_GET_LOOKAHEAD`, in 48 kHz units). It uses the libopus inside the library, so no `opus_*` symbol is exported. It adds its sources to patch 0006's targets, so it needs 0006. The constructor loads `jingle_peerconnection_so` itself.
- Licenses: each ABI job writes its own `LICENSE.md` (`generate_licenses.py` over that job's build) and `package-android.sh` merges the three (paragraphs, deduplicated); a GNU license in any one fails the package. The published `webrtc-android-m156.1` `LICENSE.md` predates this and comes from the arm64-v8a build alone.
- Class files: the WebRTC build emits Java 25 class files; `package-android.sh` relabels them as version 61 (Java 17) after checking that none uses a `java/lang/runtime/*` bootstrap other than `ObjectMethods` (for example `SwitchBootstraps`), and fails otherwise.
- Tests: `sh tools/webrtc/build_android_test.sh` (Linux; fetches upstream files from googlesource and the Opus tarball).
