# Custom WebRTC build

Stub. Task 6 of the WebRTC + Opus build plan completes this file.

- `VERSION`: the pinned WebRTC milestone and commit, and the pinned libopus tarball. Scripts read it with `. tools/webrtc/VERSION`.
- `opus-probe/`: a C probe that tells whether a libopus build has Deep PLC, OSCE and OSCE BWE. Run `sh tools/webrtc/opus-probe/run-tests.sh` on Linux.

## Android

The Android build uses the same pin (`VERSION`) and the same patched Opus 1.6 as the Apple build, and runs on Linux only.

- `build-android.sh <abi> <workdir>` (`arm64-v8a` or `x86_64`): fetches `webrtc_android`, runs `APPLY_PLATFORM=android apply.sh`, builds with gn args from `build-android.sh --print-gn-args <abi>`, and copies `libjingle_peerconnection_so.so` and `libwebrtc.jar` to `<workdir>/out/android/<abi>/`, plus `AndroidManifest.xml` and `LICENSE.md` to `<workdir>/out/android/`. arm64 has no dot-product flags (ARMv8.0 devices such as the Snapdragon 835).
- `package-android.sh <workdir>/out/android`: writes `libwebrtc.aar` (with `jni/arm64-v8a` and `jni/x86_64` only) and `libwebrtc.aar.sha256`. It fails if an ABI is missing or extra, if `LICENSE.md` names a GNU license, or if the jar lacks `org.webrtc.OpusInfo` or `org.webrtc.AudioCapturePostProcessing`. The AAR is reproducible.
- `apply.sh` applies patches 0001 and 0002 (C/C++) on both platforms, 0003 and 0004 (Objective-C) only on Apple (the default), and 0005 and 0006 (Java and JNI) only with `APPLY_PLATFORM=android`.
- `patches/0005-android-opus-info.patch`: `org.webrtc.OpusInfo` with `decoderComplexity()`, `hasDeepPlc()`, `hasOsce()` and `maxPacketMs()`. Call them after `PeerConnectionFactory.initialize` has loaded the library.
- `patches/0006-android-capture-post-processing.patch`: `org.webrtc.AudioCapturePostProcessing`, an `AudioProcessingFactory` for `PeerConnectionFactory.Builder.setAudioProcessingFactory`. It is the Android form of patch 0004 (the Android SDK has no upstream equivalent at the pin). The processor is native code behind the C struct `AppAudioCaptureProcessor` (`sdk/android/src/jni/app/audio_capture_processor.h`), so audio never crosses JNI and no symbol has to be exported from the hidden-visibility library. It also makes `PeerConnectionFactory` take over the reference `createNative` returns instead of leaking it.
- Tests: `sh tools/webrtc/build_android_test.sh` (Linux; fetches upstream files from googlesource and the Opus tarball).

Every file under `tools/webrtc/` needs the project owner's review before merge.
