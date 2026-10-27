# On-device Noise Suppression and Voice Processing for a Native iOS/macOS WebRTC Calling App (as of Oct 2026)

Scope: options for noise suppression (NS), echo cancellation (AEC), and gain control (AGC) in a Swift app using native libwebrtc, where processing must stay on the device because calls are end-to-end encrypted. Goal: "Discord-like" (Krisp-class) suppression with low data use and good battery life. Research date: 2026-10-27. Information older than 2025 is marked **[older]**.

---

## 1. WebRTC Audio Processing Module (APM: AEC3, NS, AGC2) on iOS, and how it interacts with Apple's voice processing (VPIO / Voice Isolation)

### Takeaway
On iOS, stock libwebrtc already hands NS to Apple's Voice Processing I/O (VPIO). Since 2020 the WebRTC defaults turn its own software NS off on iOS while leaving AEC and AGC on. Apple recommends VPIO for VoIP. An app that uses VPIO also lets the user choose Standard, Voice Isolation, or Wide Spectrum for that app. Use one main noise suppressor, not two in series. Pick either Apple VPIO (plus whatever mic mode the user chooses) or a software suppressor, and turn the other one off.

### Cited Findings
- APM applies the speech-enhancement effects needed for VoIP: AEC, NS and AGC. Its API is `api/audio/audio_processing.h`, and it is built with `BuiltinAudioProcessingBuilder`. It is thread-safe, handles any input sample rate below 384 kHz and any number of mic or speaker channels (it reconfigures itself automatically), and can run inside the WebRTC pipeline or on its own. — [WebRTC APM doc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/g3doc/audio_processing_module.md)
- WebRTC commit "Move iOS noise suppression override to default settings" (Sam Zackrisson, 26 Nov 2020) **[older, but still how the defaults work]**. In `webrtc_voice_engine.cc`, `#if defined(WEBRTC_IOS)` contains the comment "On iOS, VPIO provides built-in NS" and sets `options.noise_suppression = false; options.typing_detection = false;`. `echo_cancellation = true`, `auto_gain_control = true` and `highpass_filter = true` stay on. — [webrtc.googlesource.com commit b7d89ca](https://webrtc.googlesource.com/src/+/b7d89ca0d3ef9688152c35be2726f1eb29861d19%5E%21/)
- Apple (WWDC23 "What's new in voice processing") says voice processing is available both through AUVoiceIO (AUVoiceProcessingIO) and through AVAudioEngine `inputNode.setVoiceProcessingEnabled(true)`. Apple says it "provides best-in-class audio signal processing, including echo cancellation, noise suppression, automatic gain control", that it is "tuned by acoustic engineers for each Apple product model in conjunction with each type of audio device", and that Apple "highly recommend[s] using Apple's voice processing APIs for your Voice over IP applications". **[2023]** — [WWDC23 session 10235](https://developer.apple.com/videos/play/wwdc2023/10235/)
- The same session says using Apple's voice processing APIs "grants users full control over the mic mode settings for your app, including Standard, Voice Isolation, and Wide Spectrum." It also added other-audio ducking control (`AUVoiceIOOtherAudioDuckingConfiguration` / `AVAudioVoiceProcessingOtherAudioDuckingConfiguration`) and muted-talker detection (iOS 15+, macOS 14, tvOS 17). — [WWDC23 session 10235](https://developer.apple.com/videos/play/wwdc2023/10235/)
- LiveKit's Swift SDK (built on a libwebrtc fork) allows platform voice processing by default and uses Apple's VPIO for AEC and AGC. `AudioManager.shared.setPlatformVoiceProcessingAllowed(false)` restarts the internal AVAudioEngine without VPIO, and `.automatic` modes then fall back to WebRTC software processing. `isVoiceProcessingBypassed` maps to AVAudioEngine's `isVoiceProcessingBypassed`. Per-track `AudioProcessingOptions` with `.software` modes are also available. — [LiveKit client-sdk-swift Docs/audio.md](https://github.com/livekit/client-sdk-swift/blob/main/Docs/audio.md)
- On iOS, LiveKit's Rust SDK defaults to `prefer_hardware_processing: true`, which relies on the hardware/VPIO path. — [docs.rs livekit 0.8.1 processing.rs](https://docs.rs/crate/livekit/0.8.1/source/src/platform_audio/processing.rs)
- A 2026 integration guide recommends running exactly one noise suppressor. Turn off built-in NS (`noiseSuppression: false`) when a custom model or Krisp is in the path, and leave AEC and AGC on. Place the suppressor on raw PCM before the Opus encoder, never in an encoded transform. — [Fora Soft, 2026-06-02 (vendor blog)](https://www.forasoft.com/learn/ai-for-video-engineering/articles-ai/real-time-noise-suppression-krisp-rnnoise-deepfilternet-integration)

### Inferences
- The stock native iOS path is: VPIO does AEC, NS and AGC in hardware/firmware-tuned DSP, the user-selected mic mode (Voice Isolation) runs on top, and WebRTC APM adds its own AEC3/AGC on the software side with NS off. Running AEC3 on top of VPIO's AEC is usually redundant. Apps that keep VPIO normally disable APM's AEC to save CPU and avoid double processing. Confirm this against the exact libwebrtc branch you ship.
- VPIO costs the app almost no CPU, because Apple's own audio stack does the work. That makes it the most battery-efficient baseline. Its NS, however, is a conventional voice-chat suppressor unless the user turns on Voice Isolation.
- On macOS the same AUVoiceIO/AVAudioEngine voice-processing APIs exist, so the same design carries over.

### Gaps
- I found no published CPU or battery numbers for AEC3 / NS / AGC2 on iPhone hardware, and none for VPIO.
- I did not confirm whether WebRTC's iOS defaults changed after 2020 (for example, AGC2 replacing AGC1 on iOS, or AEC3 being disabled when VPIO is active). Check `webrtc_voice_engine.cc` and the `sdk/objc` audio device module in the M13x branch you ship.

---

## 2. Apple Voice Isolation / Wide Spectrum mic modes: can the app turn them on?

### Takeaway
No. The app cannot set the mic mode. The user chooses it in Control Center, and the app can only read it or open the system picker. For Voice Isolation to be offered in VoIP apps, the app must use Apple's voice processing (VPIO). Apple does not publish quality or CPU numbers for it.

### Cited Findings
- `AVCaptureDevice.preferredMicrophoneMode` is documented as "The microphone mode that the user selects in Control Center." It is a get-only class property, and Apple's guidance is to "Use key-value observing to monitor the user's microphone mode selection." `activeMicrophoneMode` reports the device's active mode. — [Apple docs: preferredMicrophoneMode](https://developer.apple.com/documentation/avfoundation/avcapturedevice/preferredmicrophonemode)
- `AVCaptureDevice.MicrophoneMode.voiceIsolation` is processing that isolates the voice and attenuates other sounds. — [Apple docs: MicrophoneMode.voiceIsolation](https://developer.apple.com/documentation/avfoundation/avcapturedevice/microphonemode/voiceisolation)
- Apple Support (current, updated for iOS 26.1):
  - Voice Isolation and Wide Spectrum in FaceTime "or in certain third-party apps" need iOS 15+.
  - Voice Isolation in recording apps (Voice Memos, "certain third-party apps") needs iOS 26+.
  - Voice Isolation in phone calls needs iOS 16.4+.
  - Automatic Mic Mode needs iOS 18+.
  - Supported hardware: iPhone XR/XS and later.
  - The user switches modes in Control Center.
  - iOS 26.1 adds an input-gain slider for supported wired mics in Control Center.
  — [Apple Support 101993](https://support.apple.com/en-us/101993)
- Developer forum reports (iOS 15 era **[older]**): apps can observe the mode and show the system picker, but there is no public API to select Voice Isolation, and setting `AVAudioSession` mode `.voiceChat` does not change the mic mode. — [Apple Developer Forums thread 690152](https://developer.apple.com/forums/thread/690152)
- The WWDC25 session "Enhance your app's audio recording capabilities" covers an in-app input picker (AVKit), AirPods high-quality recording, and spatial-audio capture with speech/ambience isolation for recording. It does not announce an API for setting the voice-chat mic mode. — [WWDC25 session 251](https://developer.apple.com/videos/play/wwdc2025/251/)
- Using Apple voice processing gives users control of Standard, Voice Isolation and Wide Spectrum for the app. — [WWDC23 session 10235](https://developer.apple.com/videos/play/wwdc2023/10235/)

### Inferences
- A good user experience: detect `preferredMicrophoneMode != .voiceIsolation` and show a "Turn on Voice Isolation" hint that calls `AVCaptureDevice.showSystemUserInterface(.microphoneModes)`. Apple developer docs name this API, but this research did not fetch that page, so verify the signature.
- Voice Isolation runs in Apple's audio stack and is tuned per device. It is very likely the cheapest strong NS available on iPhone. Because the user controls it, though, the app cannot guarantee Krisp-like behavior for everyone.

### Gaps
- Apple publishes no PESQ/DNSMOS, latency or CPU/battery figures for Voice Isolation.
- Not verified: whether `showSystemUserInterface(.microphoneModes)` is available on macOS for VoIP apps, and how Voice Isolation behaves when VPIO is bypassed.

---

## 3. Open and commercial noise-suppression models: CPU, latency, quality, size, licenses

### Takeaway
- **RNNoise** (BSD-3) is tiny and very cheap (about 0.04 GMAC/s), but its quality is modest.
- **GTCRN** has an even smaller compute budget (0.033 GMAC/s, 48K parameters) and clearly beats RNNoise on DNSMOS/PESQ. It is a research model.
- **DeepFilterNet 2/3** (MIT/Apache-2.0) gives the best open-source full-band (48 kHz) quality but costs roughly 10x the compute. DFN2 reports RTF 0.04 and DFN3 reports RTF 0.19 on a single laptop core.
- **Krisp** is the "Discord" option. It runs on-device on iOS, but pricing is not published and it is closed-source.

I found no iPhone-measured numbers for any of the open models.

### Cited Findings

**RNNoise (Xiph)**
- License BSD-3-Clause. The repo includes PyTorch training scripts (`torch/`, `training/`) for retraining on custom noise data. — [github.com/xiph/rnnoise](https://github.com/xiph/rnnoise)
- Original design **[2017–2018, older]**: a hybrid DSP + GRU network with 8-bit weights. The model is about 85 kB (340 kB as float32). It ran about 60x faster than real time on x86 and about 7x on a Raspberry Pi 3 before SIMD optimization. — [jmvalin.ca RNNoise demo](https://jmvalin.ca/demo/rnnoise/)
- RNNoise 0.2 (Apr 2024): improved training on public datasets only, SSE4.1/AVX2 optimizations, and runtime CPU detection. No quality metrics were published for 0.2 versus 0.1. — [Phoronix](https://www.phoronix.com/news/RNNoise-0.2-Released)
- Benchmarks (from the GTCRN authors' tables):
  - VCTK-DEMAND: RNNoise 0.06M params, 0.04 GMAC/s, PESQ 2.29 (noisy input: 1.97).
  - DNS3 blind: DNSMOS P.808 3.15, OVRL 2.53.
  — [GTCRN repo](https://github.com/Xiaobin-Rong/gtcrn)
- Frame size: 480 samples = 10 ms at 48 kHz, which matches WebRTC's 10 ms frames. The Jitsi WASM build is about 110 KB. — [Fora Soft 2026](https://www.forasoft.com/learn/ai-for-video-engineering/articles-ai/real-time-noise-suppression-krisp-rnnoise-deepfilternet-integration)

**GTCRN (ICASSP 2024)**
- Size and compute: 48.2K parameters and 33.0 MMACs/s. The paper reported 23.7K / 39.6 MMACs; the repo explains the difference.
- VCTK-DEMAND: PESQ 2.87, STOI 0.940, SI-SNR 18.83. For comparison, DeepFilterNet (2022) scores PESQ 2.81 with 1.8M params / 0.35 GMAC/s, and PercepNet scores 2.73.
- DNS3 blind: DNSMOS P.808 3.44, BAK 3.90, SIG 3.00, OVRL 2.70 (RNNoise: 3.15 / 3.45 / 3.00 / 2.53).
- The streaming version reaches RTF 0.07 on an Intel i5-12400 (desktop).
- It runs at 16 kHz (wideband), per the paper.
— [GTCRN repo](https://github.com/Xiaobin-Rong/gtcrn)

**DeepFilterNet (v2/v3)**
- Low-complexity full-band (48 kHz) enhancement using deep filtering. Dual-licensed MIT or Apache-2.0. The repo ships a Rust `libDF` runtime and a LADSPA/PipeWire plugin. — [github.com/Rikorose/DeepFilterNet](https://github.com/Rikorose/DeepFilterNet)
- DFN3 paper (May 2023): "real-time-factor of 0.19 on a single threaded notebook CPU" while matching state-of-the-art benchmarks. — [arXiv 2305.08227](https://arxiv.org/abs/2305.08227)
- DFN2 (2022): RTF 0.04 on a notebook Core i5. — [arXiv 2205.05474](https://arxiv.org/abs/2205.05474)
- Community Core ML ports exist (iOS 17+/macOS 14+), with the network on the Neural Engine and DSP on the CPU. On an Apple Silicon Mac the author reports 0.264 ms of compute per hop and about 34.9x real time, and warns that results vary by device and load. These are **not iPhone numbers**. — [HF aufklarer/DeepFilterNet3-CoreML](https://huggingface.co/aufklarer/DeepFilterNet3-CoreML); [HF iky1e/DeepFilterNet3-Streaming-CoreML](https://huggingface.co/iky1e/DeepFilterNet3-Streaming-CoreML)
- DFN in the browser needs onnxruntime-web, about 14 MB of WASM before the model. This does not apply to a native app. — [Fora Soft 2026](https://www.forasoft.com/learn/ai-for-video-engineering/articles-ai/real-time-noise-suppression-krisp-rnnoise-deepfilternet-integration)

**Krisp (commercial)**
- Krisp sells an AI Voice SDK: an RTC SDK for human-to-human calls and a VIVA SDK for voice agents. Its models are "lightweight, CPU-deployed". It runs on Windows, macOS, Linux, iOS, Android and Web. Features: inbound/outbound NS, background-voice cancellation, AEC. — [krisp.ai/developers](https://krisp.ai/developers/)
- Pricing: not published. Access is through a request form and a sales call. Aggregators describe it as "usage-based". — [Fora Soft 2026 citing ai-coustics](https://www.forasoft.com/learn/ai-for-video-engineering/articles-ai/real-time-noise-suppression-krisp-rnnoise-deepfilternet-integration); [usagepricing.com](https://usagepricing.com/blueprint/krisp)
- LiveKit offers a Swift package `LiveKitKrispNoiseFilter` for outbound NC on iOS. Background-voice cancellation (BVC) is only in the JS SDK. This filter requires LiveKit Cloud. — [LiveKit docs: noise & echo cancellation](https://docs.livekit.io/home/client/tracks/noise-cancellation)

**Other**
- Picovoice Koala: a commercial on-device NS SDK for iOS 16+. — [Picovoice blog](https://picovoice.ai/blog/real-time-noise-suppression-in-ios/)
- WorkAdventure 1.32 uses a DTLN-based suppressor by default (web). — [WorkAdventure](https://workadventu.re/release/workadventure-1-32-noise-suppression/)
- ai-coustics: about 30 ms latency, self-serve pricing from $150/month (vendor claims). It is server/agent focused. — [Fora Soft 2026](https://www.forasoft.com/learn/ai-for-video-engineering/articles-ai/real-time-noise-suppression-krisp-rnnoise-deepfilternet-integration)

### Comparison table (from the figures above; "—" = no source found)

| Option | License | Params / compute | Band | Quality (sourced) | Measured speed | Latency |
|---|---|---|---|---|---|---|
| Apple VPIO + Voice Isolation | Proprietary, free with the OS | runs in Apple's audio stack | — | No public metrics | — | — |
| WebRTC NS (APM) | BSD-3 (libwebrtc) | classical DSP | — | — | — | — |
| RNNoise 0.2 | BSD-3 | 60K / 0.04 GMAC/s, ~85 kB weights | 48 kHz | PESQ 2.29 (VCTK); DNSMOS OVRL 2.53 (DNS3) | 60x real time on x86 **[2017]** | 10 ms frames |
| GTCRN | not verified (see Gaps) | 48K / 0.033 GMAC/s | 16 kHz | PESQ 2.87; DNSMOS OVRL 2.70 | RTF 0.07 on desktop i5 | — |
| DeepFilterNet2 | MIT / Apache-2.0 | ~1.8–2.3M / 0.35 GMAC/s | 48 kHz | PESQ 2.81 (VCTK) | RTF 0.04 on notebook i5 | — |
| DeepFilterNet3 | MIT / Apache-2.0 | — | 48 kHz | "matches SOTA" | RTF 0.19 on single-thread notebook CPU | — |
| Krisp RTC SDK | Commercial, unpublished pricing | — | — | No public metrics | — | — |

### Inferences
- Rough compute-to-battery ranking: RNNoise ≈ GTCRN (about 0.03–0.04 GMAC/s, likely around 1% of one efficiency core on a recent iPhone) < DFN2 (about 10x) < DFN3 or a Core ML/ANE path. This is estimated from MAC counts. It has not been measured on iPhone.
- GTCRN looks like the best compute-to-quality option, but it is 16 kHz only. In a 48 kHz Opus pipeline you would have to band-split or resample, which costs high-band quality. DFN3 is natively full-band.
- Running a Neural Engine model per 10 ms frame may cost more power through scheduling overhead than a tiny CPU model. Measure it with Instruments (Energy Log) before committing to Core ML.

### Gaps
- No iPhone-measured CPU %, battery or memory figures for RNNoise, GTCRN, DFN or Krisp.
- GTCRN's license was not verified from the repo; check its LICENSE file.
- DFN3 algorithmic latency was not captured in a source here. The DFN papers discuss lookahead; check the paper.
- Speex DSP preprocessor (spectral NS/AGC): I did not research it. It is widely regarded as superseded by RNNoise and WebRTC NS, but I have no source for that.
- No public DNSMOS figures for Krisp or Apple Voice Isolation, and no independent head-to-head comparisons.
- The Microsoft DNS Challenge results pages were not reviewed.

---

## 4. Opus 1.5 / 1.6 ML features (Deep PLC, DRED, OSCE LACE/NoLACE, BWE): what they do, cost, use in libwebrtc

### Takeaway
These run in the receiver's decoder (Deep PLC, LACE/NoLACE, BWE) or add redundancy at the sender (DRED). They improve loss resilience and low-bitrate quality, which suits a low-data app. They do not remove background noise. Each costs about 1% of a CPU core or less. Stock libwebrtc does not appear to use them, so you would need a custom Opus build and changes to libwebrtc's Opus wrapper.

### Cited Findings
- **Deep PLC (Opus 1.5)**:
  - Build with `--enable-deep-plc` (about 1 MB binary).
  - Enabled at run time when decoder complexity is 5 or higher (`OPUS_SET_COMPLEXITY` on the decoder).
  - Costs about 1% of a laptop core at high loss.
  - Decoder-only, so there are no compatibility issues.
  — [Opus 1.5 release](https://opus-codec.org/demo/opus-1.5/)
- **FARGAN vocoder** (used by Deep PLC and DRED): 600 MFLOPS (1/5 of LPCNet), "less than 1% of a CPU core on laptops or even recent phones." — [Opus 1.5 release](https://opus-codec.org/demo/opus-1.5/)
- **DRED**:
  - Packs up to 1 s of redundancy into each packet using an RDO-VAE, at "about 12-32 kb/s overhead". The release page says every 20 ms packet is effectively sent 50 times, at a cost similar to LBRR. — [Opus 1.5 release](https://opus-codec.org/demo/opus-1.5/)
  - The IETF draft describes the DRED bitrate as about 1/50 of the regular Opus bitrate. DRED is carried in Opus padding, and SFUs that drop silent packets SHOULD still forward DRED from the last active source. — [draft-ietf-mlcodec-opus-dred](https://datatracker.ietf.org/doc/html/draft-ietf-mlcodec-opus-dred)
- **LACE/NoLACE (OSCE)**: a DNN predicts postfilter coefficients for low-bitrate SILK speech, and the audio itself never passes through the DNN. LACE runs "even on older phones". NoLACE needs more compute but "runs without significant overhead on recent laptop and smartphone CPUs." It is decoder-side and compatible with RFC 6716. — [Opus 1.5 release](https://opus-codec.org/demo/opus-1.5/)
- **Opus 1.6 (current: 1.6.1)**:
  - Adds a wideband-to-fullband ML BWE (experimental). It is built with `--enable-osce` and turned on with decoder complexity ≥4 at 48 kHz output; it works only on SILK wideband. It "closes about half" of the WB-to-FB quality gap. NoLACE at 9 kb/s plus BWE is said to be similar to Opus 1.4 full-band at 18 kb/s.
  - The DRED models shrink from 1800 kB to 600 kB, with better intelligibility and noise robustness. 1.6 DRED is incompatible with 1.5 DRED data; it fails safely by ignoring the data.
  - Also adds Opus HD (96 kHz, `--enable-qext`) and a 24-bit API.
  — [Opus 1.6 release](https://opus-codec.org/demo/opus-1.6/); [opus-codec.org](https://opus-codec.org/)
- The DRED reference implementation for the IETF draft is opus-1.6.1-20-gf18e26e64 (June 2026), so standardization is still in progress. — [draft-ietf-mlcodec-opus-dred](https://datatracker.ietf.org/doc/html/draft-ietf-mlcodec-opus-dred)
- One WebRTC glossary (undated) says DRED is "still not supported by WebRTC implementations". — [BlogGeek.me Opus glossary](https://bloggeek.me/webrtcglossary/opus/)

### Inferences
- For a low-data app, NoLACE (+ BWE) lets you run Opus at about 9–16 kb/s while keeping good perceived quality. This decoder-side change gives the biggest data saving for little CPU, and it needs no protocol changes, because the receiver just builds Opus with OSCE and sets decoder complexity.
- DRED increases bitrate. It is worth enabling adaptively, only when measured packet loss is high, not by default. Both sides need DRED-aware decoders running the same major version (1.6).
- To get these features in libwebrtc, build Opus 1.6.x with `--enable-deep-plc --enable-osce` (and DRED if wanted), make sure libwebrtc's Opus decoder wrapper sets decoder complexity, and for DRED, call the `opus_dred_*` decode APIs when NetEq detects loss. That last part is a significant change to NetEq.
- None of these features suppress the far end's background noise. They complement a noise suppressor and do not replace one.

### Gaps
- Not verified: whether current Chromium/libwebrtc `third_party/opus` builds with `ENABLE_DEEP_PLC`, `ENABLE_OSCE` or `ENABLE_DRED`. The BUILD.gn fetch returned 404. Inspect `third_party/opus/BUILD.gn` in your checkout.
- No iPhone-specific CPU figures for NoLACE or BWE.

---

## 5. Putting a custom noise suppressor into libwebrtc's iOS audio pipeline: integration and pitfalls

### Takeaway
The clean place to hook in is APM's injectable capture-post-processing slot (`BuiltinAudioProcessingBuilder::SetCapturePostProcessing`). It runs after AEC on 10 ms frames, before the encoder. Wrapper SDKs such as LiveKit's Swift SDK expose it as `capturePostProcessingDelegate`. The main pitfalls are doubled NS (VPIO / Voice Isolation plus your model), mismatched sample rates and frame sizes, and loss of AEC if you bypass VPIO without enabling AEC3.

### Cited Findings
- `BuiltinAudioProcessingBuilder` setters: `SetConfig`, `SetEchoCancellerConfig`, `SetEchoControlFactory`, `SetCapturePostProcessing` ("Sets the capture post-processing sub-module to inject when APM is created"), `SetRenderPreProcessing`, `SetEchoDetector`, `SetCaptureAnalyzer`, `SetNeuralResidualEchoEstimator`. — [builtin_audio_processing_builder.h](https://webrtc.googlesource.com/src/+/refs/heads/main/api/audio/builtin_audio_processing_builder.h)
- APM accepts any sample rate below 384 kHz and any channel count, and reconfigures itself automatically. — [WebRTC APM doc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/g3doc/audio_processing_module.md)
- LiveKit Swift exposes `AudioManager.shared.capturePostProcessingDelegate` (an `AudioCustomProcessingDelegate`). It changes the local buffer before it is sent, and only one delegate can be set at a time. — [LiveKit Swift audio management guide (Mintlify)](https://www.mintlify.com/livekit/client-sdk-swift/guides/audio-management)
- In LiveKit Swift you can turn off VPIO with `setPlatformVoiceProcessingAllowed(false)` or `isVoiceProcessingBypassed`, after which `.automatic` processing falls back to WebRTC software (AEC3 etc.). — [LiveKit Docs/audio.md](https://github.com/livekit/client-sdk-swift/blob/main/Docs/audio.md)
- RNNoise's native frame is 480 samples (10 ms at 48 kHz). — [Fora Soft 2026](https://www.forasoft.com/learn/ai-for-video-engineering/articles-ai/real-time-noise-suppression-krisp-rnnoise-deepfilternet-integration)
- When using Krisp on the frontend, LiveKit tells developers not to also run Krisp in the agent; standard NC and AEC can stay on. — [LiveKit noise & echo cancellation](https://docs.livekit.io/home/client/tracks/noise-cancellation)

### Inferences (implementation checklist)
1. **Choose the AEC owner.**
   - Option A: keep VPIO, which handles AEC and AGC and offers Voice Isolation. If you add a model, set it to run only when `activeMicrophoneMode != .voiceIsolation`, so the audio is never processed by two neural suppressors.
   - Option B: bypass VPIO and enable APM AEC3 + AGC2 + your model. This gives consistent behavior across devices, but costs more CPU, and AEC3 tuning on iPhone speakerphone has to be validated.
2. **Hook point.** Implement a `webrtc::CustomProcessing` and inject it with `SetCapturePostProcessing`. In ObjC/Swift you need a fork of the iOS SDK that exposes the APM builder; LiveKit's `webrtc-sdk` fork does, via `capturePostProcessingDelegate`. Stock Google `WebRTC.framework` ObjC APIs may not expose this, so verify.
3. **Frames and rates.** APM delivers 10 ms frames at the processing rate (typically 48 kHz mono on iOS), so RNNoise (480 samples) fits directly. GTCRN (16 kHz) and DFN (480-sample hop at 48 kHz, plus lookahead) need resampling or internal buffering, and any lookahead adds one-way latency.
4. **Turn off APM NS** (`noise_suppression=false`) when your model is active, so the signal is not suppressed twice and speech is not muffled.
5. **Order.** NS should run after AEC (post-processing does) and before AGC gain, or AGC will amplify residual noise. Check where AGC2 sits relative to capture post-processing in your branch.
6. **Real-time safety.** The callback runs on the audio thread. Do not allocate memory, take locks or make blocking Core ML calls in it. Prefer a C/SIMD model (RNNoise/GTCRN via ONNX→C or Accelerate) over per-frame Core ML.
7. **E2EE.** Processing happens on raw PCM before encoding and encryption, so it is compatible with E2EE (SFrame/insertable streams).

### Gaps
- I did not find official Google documentation or examples for injecting `CustomProcessing` from the ObjC SDK, or the exact `AudioCustomProcessingDelegate` method signatures and buffer formats in LiveKit Swift.
- Not verified: where capture post-processing sits relative to AGC2 in the APM chain in current main.

---

## 6. What do Signal, WhatsApp, Telegram, Discord, Jitsi and Element/Matrix use?

### Takeaway
Discord uses Krisp on-device. Jitsi uses RNNoise (WASM) in the browser. WhatsApp is beta-testing its own on-device NS that runs before encryption, with the vendor or model not disclosed. Telegram has an NS toggle with no published details. Signal's RingRTC is a libwebrtc fork with nothing public about NS models. Element Call runs on LiveKit, where Krisp is available only with LiveKit Cloud.

### Cited Findings
- **Discord**: launched noise suppression with Krisp, which runs on the user's device. It is under Voice & Video > Advanced > Noise Suppression. **[2020, older]** — [Discord blog](https://discord.com/blog/talking-to-each-other-during-covid-19); [Pocketnow](https://pocketnow.com/discord-can-now-detect-and-suppress-background-noise). No source found saying Discord replaced Krisp; one April 2026 user report describes the NS button temporarily missing after an update. — [Arch Linux forums](https://bbs.archlinux.org/viewtopic.php?id=313222)
- **Jitsi Meet**: added RNNoise-based noise suppression compiled to WASM and run in AudioWorklets (blog post July 2022). — [Jitsi blog](https://jitsi.org/blog/enhanced-noise-suppression-in-jitsi-meet)
- **WhatsApp**: beta-testing call noise cancellation on Android. Reports say it runs locally before encryption, is on by default, affects outgoing audio only, and was still in limited rollout as of April 2026. These are beta-tracker and press reports, not an official WhatsApp statement. — [WABetaInfo](https://wabetainfo.com/whatsapp-beta-is-testing-noise-cancellation-for-voice-and-video-calling/); [Stuff.co.za 2026-04-07](https://stuff.co.za/2026/04/07/whatsapps-real-time-noise-cancellation-aims/)
- **Telegram**: announced improved noise suppression in voice chats with a settings toggle, with no technical details. **[2021, older]** — [Gizmodo](https://gizmodo.com/a-year-later-telegram-finally-launches-the-group-video-1847183093)
- **Signal**: RingRTC is Signal's calling library, built on a WebRTC fork. The README gives no NS model. — [github.com/signalapp/ringrtc](https://github.com/signalapp/ringrtc)
- **Element/Matrix (Element Call)**: runs on LiveKit. LiveKit's Krisp NC filter (including Swift) is a LiveKit Cloud feature. — [LiveKit docs](https://docs.livekit.io/home/client/tracks/noise-cancellation)

### Inferences
- Among privacy-focused, self-hosted projects (Signal, Jitsi, Element), none publicly ships Krisp-class ML NS on native iOS. They rely on platform voice processing or WebRTC defaults (iOS: VPIO), and Jitsi web uses RNNoise. A Krisp-class experience is a real differentiator, and to deliver it without a vendor you need DFN-class models.

### Gaps
- I found no public source for Signal's iOS NS/AEC configuration, Telegram's tgcalls processing chain, or WhatsApp's model or vendor. RingRTC's license (believed AGPL-3.0) was not confirmed from the fetched page.
- Element Call's native iOS NS settings were not found.

---

## 7. Recommendation for a low-data, battery-efficient, self-hosted, privacy-focused E2EE app

### Takeaway
Use a layered design:
- **(1) Default:** Apple VPIO for AEC, AGC and baseline NS, and prompt users to turn on Voice Isolation. This adds almost no CPU and is the best quality per watt on iPhone.
- **(2) Optional "Enhanced noise suppression" toggle:** a small on-device open model injected through APM capture post-processing. Use RNNoise 0.2 (BSD-3) as the safe baseline, or GTCRN / DeepFilterNet (MIT/Apache) for quality, and turn it off automatically when Voice Isolation is active.
- **(3) Receiver side:** Opus 1.6 with Deep PLC + NoLACE + BWE for low-bitrate quality. Use DRED only when measured loss is high.

Avoid Krisp unless budget and closed-source licensing are acceptable. Its SDK is on-device and E2EE-compatible, but pricing is unpublished, and it conflicts with a self-hosted/OSS stance.

### Cited Findings (the sourced basis for the recommendation)
- Apple recommends its voice processing for VoIP, and it gives users the Voice Isolation mic mode for the app. — [WWDC23 10235](https://developer.apple.com/videos/play/wwdc2023/10235/)
- The app cannot set the mic mode; only the user can. — [Apple preferredMicrophoneMode](https://developer.apple.com/documentation/avfoundation/avcapturedevice/preferredmicrophonemode)
- libwebrtc on iOS already defers NS to VPIO. — [WebRTC commit b7d89ca](https://webrtc.googlesource.com/src/+/b7d89ca0d3ef9688152c35be2726f1eb29861d19%5E%21/)
- Compute and quality: RNNoise 0.04 GMAC/s, PESQ 2.29. GTCRN 0.033 GMAC/s, PESQ 2.87. DFN 0.35 GMAC/s, PESQ 2.81. — [GTCRN repo](https://github.com/Xiaobin-Rong/gtcrn)
- DFN3: RTF 0.19 on a single laptop core, full-band, MIT/Apache. — [arXiv 2305.08227](https://arxiv.org/abs/2305.08227); [DeepFilterNet repo](https://github.com/Rikorose/DeepFilterNet)
- Opus NoLACE at 9 kb/s + BWE is about equal to Opus 1.4 full-band at 18 kb/s. Deep PLC/FARGAN costs under 1% of a core on phones. — [Opus 1.6](https://opus-codec.org/demo/opus-1.6/); [Opus 1.5](https://opus-codec.org/demo/opus-1.5/)
- Krisp pricing is unpublished and requires a sales process. — [Fora Soft 2026](https://www.forasoft.com/learn/ai-for-video-engineering/articles-ai/real-time-noise-suppression-krisp-rnnoise-deepfilternet-integration); [krisp.ai/developers](https://krisp.ai/developers/)

### Inferences
- **Effect on data use:** a good noise suppressor also lowers bitrate. Opus DTX and VBR spend fewer bits when background noise is removed. This is a general codec property, not separately sourced here.
- **Suggested order of work:**
  - (a) Ship VPIO + a Voice Isolation prompt + Opus 1.6 decoder features.
  - (b) Prototype RNNoise 0.2 and GTCRN as C/ONNX in `CustomProcessing`, and measure CPU and energy on the oldest supported iPhone with Instruments.
  - (c) If quality is still short of Discord/Krisp, evaluate DFN3, either as the Rust `libDF` on CPU or via a Core ML port. Gate it to newer devices and make it opt-in, to protect battery.
- **Licensing:** RNNoise (BSD-3), DeepFilterNet (MIT/Apache-2.0), Opus (BSD) and libwebrtc (BSD) are all permissive and App Store–compatible. Confirm GTCRN's license before shipping. Avoid AGPL code such as RingRTC (license unconfirmed) unless the app itself is AGPL.
- **macOS:** VPIO is available there too, but Voice Isolation behavior and APIs on Mac were not confirmed. A software model gives consistent behavior on both platforms.

### Gaps
- Without iPhone-measured CPU, energy and latency numbers for each model, the battery ranking above is an estimate from MAC counts. An on-device benchmark is the key next step.
- No objective comparison of Voice Isolation against Krisp, DFN3 or GTCRN was found.
