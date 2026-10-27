# Client Calling (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan runs as a short foundation followed by four parallel lanes. Read "Parallel execution" first, then work only your lane's tasks.**

**Goal:** Replace `SimulatedCallEngine` with `LiveCallEngine`, which makes real 1:1 audio and video calls on iPhone (iOS 17+) and Mac. It covers:
- signaling over the server's WebSocket, with SDP, ICE and the DTLS fingerprint inside MLS application messages
- CallKit and PushKit on iPhone, and notification-based ringing on the Mac
- TURN and IP hiding
- data modes as SDP and bitrate controls, with automatic adaptation under the user's ceiling
- reconnection, noise suppression, diagnostics and call history

**Architecture:** All decisions live in **`CallCore`**, a new pure-Swift package that tests run on Linux:
- the mode → SDP munger
- the quality adaptor
- the reconnect policy
- the relay decision
- the call session state machine

A second new package, **`CallMedia`**, wraps the WebRTC framework from plan 3 behind the `MediaSession` protocol and runs on the macOS CI runner. The app target adds:
- `LiveCallEngine`, a thin shell that feeds events into the `CallCore` machine and carries out its effects
- the CallKit/PushKit controller for iPhone and the Mac ringer
- the ServerKit adapter

**Tech Stack:** Swift 6 with strict concurrency, Swift Testing, WebRTC (`WebRTCBinary` from plan 3, module `WebRTC`), CallKit, PushKit, AVFAudio, AVFoundation, Network (`NWPathMonitor`), UserNotifications, RNNoise (C, BSD-3, vendored), XcodeGen, and GitHub Actions `macos-26`.

**Spec:**
- [GOAL.md](../../../GOAL.md): §4 *Low-data calling*, *Quality selection*, *Video*, *Noise suppression*; §5 *1:1 call media*; §6 *Call relay privacy*, *Push notification gateway*
- [Server design](../specs/2026-10-27-server-design.md): §7.3 call frames and TURN, §7.4 push
- [UI design](../specs/2026-10-27-app-ui-design.md): call screen, data sheet, banners
- [Roadmap contracts](README.md)

This plan argues from those documents; where they are silent, its "Design" section decides.

## Global Constraints

- Platforms: iOS 17.0+ and macOS 14.0+ (same as `project.yml`). Swift 6, `SWIFT_STRICT_CONCURRENCY: complete`.
- New dependencies: only WebRTC (plan 3) and vendored RNNoise C sources, both named in GOAL.md §4. Anything else needs owner approval (AGENTS.md).
- `CallCore` imports only Foundation and AppCore, and must build and pass `swift test` on Linux (`cd apps/apple/Packages/CallCore && swift test`).
- `CallMedia` and app code are verified only by a green `macos-26` run (`gh run watch`). Never claim they pass without one.
- UI copy: plain words only.
  - Never show "codec", "kbps", "relay", "MLS", "E2EE", "SDP" or "ICE".
  - No encryption labels on call screens.
  - Every new string goes in `Localizable.xcstrings`.
  - Every control has a VoiceOver label.
- Owner review before merge: everything under `App/Calls/Platform/` (CallKit, PushKit) and every file that handles call payload sealing or the DTLS fingerprint (`CallCore/Sources/CallCore/Signal/`, `LiveCallEngine.swift`).
- Commits: plain messages, no `Co-Authored-By` or AI trailers.
- Coding agents run on Sonnet 5.5. The final whole-branch review uses Opus.
- Contact IDs equal server account IDs, as stored by plan 7's `LiveChatStore`.

## Review Focus

1. **Every VoIP push on iOS < 26.4 is reported to CallKit, and so is every push on iOS 26.4+ where `mustReport` is true.** That includes pushes that can't be decrypted, calls that already ended, and strangers. Otherwise iOS stops delivering VoIP pushes to the app. Pinned by K2 `testEveryVoIPPushIsReported` (a table over payload states).
2. **A forged or swapped fingerprint ends the call.** A call payload whose MLS sender isn't the expected contact, or whose `dtlsFingerprint` doesn't match the SDP's `a=fingerprint`, never reaches `setRemoteDescription`. Pinned by S3 `remoteFromWrongSenderIsRejected` and `fingerprintMismatchEndsCall`.
3. **The ceiling is never exceeded.** With IP hiding set to `always`, or `nonContacts` with a stranger, no host or server-reflexive candidate is ever sent, and the adaptor never steps above the effective ceiling (`DataModePolicy`, plus the peer's signaled ceiling). Pinned by P3 `relayPolicyTable`, M1 `testRelayOnlyGathersOnlyRelayCandidates`, and P2 `neverExceedsCeiling` (a property test).
4. **A finished call stays finished.** `call.ended`, a hangup, a CallKit end, a timeout or a reconnect give-up all leave exactly one history entry and a frozen state; later events are ignored. Pinned by S3 `endedCallIgnoresLateEvents` and `exactlyOneHistoryEntry`.
5. **Network handover:** a Wi-Fi → cellular switch mid-call triggers an ICE restart within 1 s and recovers with no new ring. 30 s with no recovery ends the call as `connectionLost`. Pinned by P3 `reconnectTimeline` and the owner device checklist (I1).

---

## Design (decisions)

### Call setup: the callee makes the offer

The ring payload must fit inside a push: the server pads to at most 3070 bytes and otherwise sends an empty push (server spec §7.4). A video SDP offer is usually 3–6 KB. Therefore:

1. **Caller:**
   - sends `call.invite` with the sealed payload `{kind: ring, video, ceiling}`, which is small and fits in a push
   - starts gathering ICE early by creating its peer connection (`prewarm`)
2. **Every ringing callee device** creates its peer connection and calls `createOffer` + `setLocalDescription` (prewarm: TURN allocations and gathering happen while it rings). It does not send its candidates yet.
3. **The device that answers** sends `call.answer` with a sealed payload `{kind: offer, sdp, dtlsFingerprint, ceiling}`. Its SDP already holds the candidates gathered so far, and later candidates trickle via `call.signal {kind: candidate}`.
4. **Caller** gets `call.answered` with that offer. It checks the sender and fingerprint, then calls `setRemoteDescription` and `createAnswer`, and sends `call.signal {kind: answer, sdp, dtlsFingerprint}`.
5. **Devices that didn't answer** get `call.ended answered_elsewhere` and close their peer connections.

Setup after the answer costs one round trip plus ICE. That fits GOAL §9's 3 s target on a healthy network.

### Call payload (sealed by plan 7)

`CallSignalPayload` is the call content type in plan 7's message content format. This plan needs these fields; if plan 7 has not defined the type yet, foundation task F1 adds it to AppCore exactly as follows.

```swift
public struct CallSignalPayload: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable { case ring, offer, answer, candidate, renegotiate, renegotiateAnswer, modeChange }
    public var kind: Kind
    public var callID: String
    public var video: Bool?             // ring
    public var sdp: String?             // offer, answer, renegotiate, renegotiateAnswer
    public var dtlsFingerprint: String? // offer, answer: "sha-256 AB:CD:…", copied from the SDP's a=fingerprint
    public var candidate: IceCandidate? // candidate
    public var ceiling: DataMode?       // ring, offer, answer, modeChange: the most this device wants to receive
    public var videoOn: Bool?           // modeChange
}
public struct IceCandidate: Codable, Equatable, Sendable { public var sdp: String; public var sdpMid: String?; public var sdpMLineIndex: Int32 }
```

Sealing goes through plan 7. `CallCore` declares:

```swift
public protocol CallSignalSealer: Sendable {
    /// MLS-encrypts `payload` in the 1:1 conversation with `contactID`.
    func seal(_ payload: CallSignalPayload, to contactID: String) async throws -> Data
    /// Decrypts; throws `CallSealError.wrongSender` unless the MLS sender's account is `contactID`.
    func open(_ data: Data, from contactID: String) async throws -> CallSignalPayload
}
```

`LiveChatStore` (plan 7) conforms. That is a README contract addition.

The DTLS fingerprint is protected because the whole SDP travels inside MLS (GOAL §5). As a second check, the machine rejects any offer or answer whose `dtlsFingerprint` differs from the SDP's `a=fingerprint` line. That catches munging bugs or a sealer that returns the wrong content.

### Data modes → wire settings

`AudioProfile.table` is the single source; plan 5 (call lab) measurements may change its values.

| Mode | `maxaveragebitrate` | `a=ptime` | `useinbandfec` | `usedtx` | `maxplaybackrate` | Wire budget per direction (for the adaptor) |
|---|---:|---:|---:|---:|---:|---:|
| `ultraLow` | 10000 | 60 | 0 | 1 | 16000 | 21 000 bps |
| `dataSaver` | 16000 | 40 | 1 | 1 | 16000 | 30 000 bps |
| `balanced` | 24000 | 20 | 1 | 1 | 48000 | 51 000 bps |
| `high` | 40000 | 20 | 1 | 1 | 48000 | 75 000 bps |

How a mode reaches the wire:
- **SDP munging** (GOAL §4: "set each mode's ptime, DTX, and FEC in the SDP"). Opus `fmtp` parameters describe what the *receiver* wants, so the engine munges both sides:
  - the **local** description with the profile of *my receive ceiling*, which limits what the peer sends me (GOAL: "signaled to the other participants")
  - the **remote** description, before `setRemoteDescription`, with the profile of *my effective send mode*, which drives my encoder
- **Mid-call ptime, FEC or DTX changes:** the side whose mode changed sends `renegotiate` (a new offer, no ICE restart). The peer answers with `renegotiateAnswer`. If both sides renegotiate at once (glare), the caller's offer wins and the callee rolls back.
- **Instant bitrate steps:** `RTCRtpSender.parameters.encodings[0].maxBitrateBps`, with no renegotiation.
- **Optional 120 ms ptime:** not used in Phase 1. It needs plan 3's custom build flag.

### Video ladder (GOAL §4)

| Tier | Size | fps | Min bps | Max bps | Allowed when the ceiling is at least |
|---|---|---:|---:|---:|---|
| `p360` | 640×360 | 20 | 250 000 | 300 000 | `high` |
| `p270` | 480×270 | 15 | 140 000 | 200 000 | `balanced` |
| `p180` | 320×180 | 15 | 40 000 | 100 000 | `ultraLow` (only if the user turned video on) |

Rules:
- Video uses H.264 through VideoToolbox.
- Video starts **off** when the effective ceiling is `ultraLow` or `dataSaver`, or when `videoOffOnMobile` applies on mobile data (existing `CallSettings`). The user can turn it on.
- The video budget is `available − audio wire budget`.
- The adaptor picks the highest allowed tier whose `Min bps` ≤ budget, and caps the sender at `min(budget, Max bps)`.
- Below 60 000 bps available, video is off, with the banner `audioOnly`.

### Quality adaptor (Auto and fixed modes alike)

- **Input:** a `MediaStats` sample every 2 s, plus the ceiling, which is `min(DataModePolicy.ceiling(…), the peer's signaled ceiling)`.
- **Output:** a sustainable `DataMode`, a video tier or none, a `CallQuality`, and an optional `CallBanner`.
- **Degradation order** (GOAL "reduce video, then drop video, protect audio last"), each step taken immediately on one bad sample:
  1. lower the video tier
  2. turn video off
  3. lower the audio mode
- **Bad sample:** `availableOutgoingBitrate < needed × 0.85`, or loss > 10 % across 2 samples in a row.
- **Recovery:** step up one level only after 5 good samples in a row (10 s), where good means `available ≥ 1.3 × the next level's need` and loss < 2 %. Video comes back last.
- **Never** above the ceiling. When the effective mode is below the ceiling, `quality = .weak`.

### Reconnection (GOAL §6 Phase 1 calls)

- `continualGatheringPolicy = .gatherContinually`.
- ICE `disconnected` → show `CallBanner.reconnecting` and `CallPhase.reconnecting` right away.
- After 2.5 s still disconnected, or immediately on `failed`, or on a network path change mid-call (`NWPathMonitor`: interface type or primary interface changed) → **ICE restart**. The caller always starts it; the callee asks for one with `renegotiate`. Retry every 5 s while the call is still disconnected.
- ICE `connected` → clear the banner and go back to `active`.
- 30 s after the first disconnect with no recovery → end with `connectionLost`, plus `call.hangup` to the server.

### Relay privacy (GOAL §6)

`iceTransportPolicy = .relay` when `settings.ipHiding.requiresRelay(isContact:)` is true. Otherwise it is `.all`. `isContact` means the contact is in `ChatStore.contacts`, and a message-request sender doesn't count. Each side applies its own setting: hiding protects *my* address.

### Noise suppression (GOAL §4)

| Setting | Behavior |
|---|---|
| `off` | VPIO stays on, because echo cancellation is always needed. Apple's noise suppression is off where the platform allows it; nothing else is added. |
| `standard` | Apple voice processing (VPIO), which libwebrtc on iOS uses by default. |
| `enhanced` | RNNoise (48 kHz, 480-sample frames) through the capture post-processing hook, after echo cancellation. |

- Only one suppressor runs at a time. If Voice Isolation is active (`AVCaptureDevice.activeMicrophoneMode == .voiceIsolation`), RNNoise is bypassed.
- When the setting is `standard` or `enhanced`, Voice Isolation is not active, and the device supports it, the call screen shows a hint: "For the clearest voice, turn on Voice Isolation". It opens `AVCaptureDevice.showSystemUserInterface(.microphoneModes)`. The app can't switch it on itself (GOAL §4).
- **Dependency on plan 3:** upstream WebRTC's Objective-C SDK exposes no hook that *modifies* capture audio. Its audio-device data observers are read-only ([react-native-webrtc forum](https://react-native-webrtc.discourse.group/t/is-there-any-way-for-me-to-implement-audio-processing/1763), [libwebrtc `audio_device_module.h`](https://searchfox.org/firefox-main/source/third_party/libwebrtc/sdk/objc/native/api/audio_device_module.h)). Plan 3 must patch in an Objective-C capture post-processing delegate over C++ `AudioProcessingBuilder::SetCapturePostProcessing`. This plan calls it `RTCAudioCapturePostProcessing` (a README contract addition). Until it lands, `enhanced` behaves like `standard`, and the settings row shows it as unavailable.

### iPhone incoming calls (CallKit + PushKit)

- **One `PKPushRegistry`** for the app's lifetime, owned by `VoIPPushCenter`. It gives the VoIP token to plan 6's push registrar to seal and upload as kind `voip`.
- **Reporting rules:**
  - iOS < 26.4: report every VoIP push to CallKit before the completion handler, as Apple requires.
  - iOS 26.4+: implement the new delegate method that receives `PKVoIPPushMetadata`, and report whenever `mustReport` is true. Apple DTS says it can be false when the app is in the foreground, already on a call, or the push arrived too late ([Apple Developer Forums 816211](https://developer.apple.com/forums/thread/816211)). Developers report that the process is killed when `mustReport` is true and nothing is reported ([hermes-conduit #604](https://github.com/kaishi00/hermes-conduit/pull/604), [expo-callkit-telecom #47](https://github.com/mfairley/expo-callkit-telecom/issues/47)).
  - The exact selector must be copied from the iOS 26.4 SDK header, not from these reports.
- **When the push can't ring normally**, the app still reports a call and then ends it at once:
  - The payload can't be opened, or the push is empty → report the caller as "Unknown", then `reportCall(endedAt:reason: .failed)`.
  - `call.status` (sent after the WebSocket connects) says the call already ended → end with `.remoteEnded`, or `.unanswered` if it was missed.
  - Calls from strangers (`ChatStore.incomingCall` returns `.silentMissed`): when a report is required, report and immediately end with `.unanswered`, then record a missed call in the request.
- **China:** in 2018, Apple required CallKit to be off for apps in the China App Store ([Apple Developer Forums 103083](https://developer.apple.com/forums/thread/103083), [VentureBeat 2018](https://venturebeat.com/2018/05/21/apple-pulls-callkit-apps-from-chinas-app-store-again-appeasing-government)). No 2025–2026 source confirms or lifts this. Because VoIP pushes require CallKit on iOS 13+, the beta excludes the China storefront (owner decision 4).
- **Audio:** start the audio only in `provider(_:didActivate:)`, by calling `RTCAudioSession.sharedInstance().audioSessionDidActivate`, with `useManualAudio = true` and `isAudioEnabled` toggled. Stop it in `didDeactivate`.
- **`CXProviderConfiguration`:**
  - `supportsVideo = true`, `maximumCallsPerCallGroup = 1`
  - `supportedHandleTypes = [.generic]`, where the handle is the account ID and the display name comes from `ChatStore`
  - `includesCallsInRecents = false` (privacy; owner decision 3)

### Mac incoming calls (no CallKit, no VoIP push)

- **App running and connected:** `call.ring` → `AppModel.incomingCaller` (the existing banner from plan 1) plus a local notification. The notification has category `CALL`, actions Answer and Decline, and interruption level `.timeSensitive`.
- **App not running:**
  1. The gateway delivers an alert push.
  2. Plan 7's NSE opens the payload. When it is a `CallSignalPayload` with kind `ring`, the NSE rewrites the notification: title is the caller's name; body is "Incoming call" or "Incoming video call"; category `CALL`.
  3. Answer launches the app, which connects, checks `call.status`, and answers.
  4. A ring older than 45 s shows "Missed call" instead.

### Diagnostics (plain words)

- The call screen's info sheet shows:
  - "Connection": "Direct" or "Through your server"
  - "Delay": RTT in ms
  - "Sound lost": inbound loss in %
  - "Quality": the effective mode's title
  - "Data used": `DataFormat.used`, from `bytesUsed`
- Codec and bitrate go only to the unified log (category `calls`) for plan 5's measurements.
- `bytesUsed` = transport `bytesSent + bytesReceived` + 28 × (packets sent + received). The 28 bytes are the IPv4+UDP header, which WebRTC stats leave out. This matches GOAL's "measured bytes on the wire".

### Call history

When a call ends, `LiveCallEngine` calls `ChatStore.recordCall(_:contactID:outgoing:at:)` exactly once:
- `.audio(duration:)` or `.video(duration:)` if the call was ever `active`
- otherwise `.missedAudio` or `.missedVideo` for incoming calls, and `.audio(duration: 0)` for outgoing calls that weren't answered

This is a new `ChatStore` requirement. F2 adds it, and plan 7 implements it in `LiveChatStore`. History is local per device in Phase 1.

## Decisions I made — owner to confirm

1. **The callee makes the offer**, with prewarmed peer connections on every ringing device. Each ringing device uses TURN allocations until the call is answered elsewhere.
2. **`AudioProfile.table` values** (ptime 60/40/20/20, bitrates 10/16/24/40 kb/s). Plan 5's measurements may replace them.
3. **`includesCallsInRecents = false`**, so calls don't show in the Phone app's Recents. iCloud syncs Recents, which would leak call metadata.
4. **No China storefront for the beta.** CallKit there is still unresolved; see Design.
5. **The diagnostics sheet leaves out the codec name and bitrate.** The UI rule forbids those words; they go to the log only.
6. **Strangers' calls on iOS < 26.4** briefly show a CallKit call that ends at once, because the report is mandatory. On iOS 26.4+ with `mustReport == false` they don't ring.
7. **`off` noise suppression keeps echo cancellation**, because turning it off makes speakerphone unusable.
8. **Call history is not synced across your own devices** in Phase 1.

---

## Parallel execution

```
Stage 0  Foundation (1 agent)   F1 → F2                       branch feat/client-calling (from main after plans 1, 6 merged)
Stage 1  Four lanes (4 agents)  P1–P3 | S1–S3 | M1–M3 | K1–K4  worktrees, branches calling/lane-<p|s|m|k>
Stage 2  Integration (1 agent)  I1                             merge order P, S, M, K
```

- **Stage 0** fixes every type and protocol name. Lanes don't edit `CallCore/Sources/CallCore/Contracts/` or the AppCore protocol changes from F2. If a lane must, it records a `Ruling:` in its ledger (`.superpowers/sdd/2026-10-27-client-calling-lane-<x>/progress.md`).
- **Lanes:**
  - **P** (policy) and **S** (session machine): pure `CallCore`, tested on Linux.
  - **M** (media): `CallMedia`, macOS CI. Needs plan 3's `WebRTCBinary`.
  - **K** (platform): the app target, macOS CI and devices. Needs plans 6 and 7 merged; until then it codes against the F1 fakes.
- **Merging:** plans 7 and 8 both touch `project.yml` (the NSE target is plan 7's; the `CALL` category branch in the NSE is lane K's) and `AppModel.swift`. Resolve those conflicts by keeping both sides' additions.

---

## Stage 0 — Foundation

### Task F1: `CallCore` package, contracts, fakes

**Files:**
- Create: `apps/apple/Packages/CallCore/Package.swift`. Library `CallCore` depends on `../AppCore`; platforms iOS 17 and macOS 14; test target `CallCoreTests`.
- Create in `Sources/CallCore/Contracts/`:
  - `MediaSession.swift`, `CallSignaling.swift`, `CallSignalSealer.swift`, `MediaStats.swift`, `Clock.swift`
  - `CallSignalPayload.swift`, only if plan 7 hasn't defined it. Otherwise import theirs and record a `Ruling:`.
- Create: `Sources/CallCore/Fakes/FakeMediaSession.swift`, `FakeCallSignaling.swift`, `PlainCallSignalSealer.swift`, `ManualClock.swift`
- Test: `Tests/CallCoreTests/ContractsTests.swift`

**Interfaces — Produces:**

```swift
public enum IceTransportPolicy: Sendable, Equatable { case all, relay }
public struct IceServer: Sendable, Equatable { public var urls: [String]; public var username: String?; public var credential: String? }
public enum IceState: Sendable, Equatable { case new, checking, connected, completed, disconnected, failed, closed }
public enum VideoTier: Int, Sendable, Comparable, CaseIterable { case p180, p270, p360 }

public struct MediaStats: Sendable, Equatable {
    public var availableOutgoingBitrate: Double?   // bps, from the selected candidate pair
    public var roundTripTime: Double?              // seconds
    public var inboundLossFraction: Double         // 0…1, audio inbound-rtp, since the last sample
    public var jitter: Double?                     // seconds
    public var bytesSent: Int64, bytesReceived: Int64, packetsSent: Int64, packetsReceived: Int64
    public var usingRelay: Bool                    // local candidate type == relay
    public var audioCodec: String?                 // log only
}

public enum MediaEvent: Sendable, Equatable {
    case localCandidate(IceCandidate)
    case iceState(IceState)
    case remoteVideo(Bool)
}

public protocol MediaSession: AnyObject, Sendable {
    var events: AsyncStream<MediaEvent> { get }
    func configure(iceServers: [IceServer], policy: IceTransportPolicy, video: Bool) async throws
    func makeOffer(receive: AudioProfile, iceRestart: Bool) async throws -> String              // munged, set as local
    func acceptOffer(_ sdp: String, send: AudioProfile, receive: AudioProfile) async throws -> String  // returns local answer
    func acceptAnswer(_ sdp: String, send: AudioProfile) async throws
    func rollback() async throws
    func add(_ candidate: IceCandidate) async throws
    func setAudioMaxBitrate(_ bps: Int) async
    func setVideo(_ tier: VideoTier?) async                       // nil = video off
    func setMuted(_ on: Bool) async
    func setNoiseSuppression(_ mode: NoiseSuppression) async
    func stats() async -> MediaStats
    func close() async
}

public enum CallServerEvent: Sendable, Equatable {
    case ring(callID: String, fromAccountID: String, fromDeviceID: String, video: Bool, payload: Data)
    case answered(callID: String, deviceID: String, payload: Data)
    case signal(callID: String, payload: Data)
    case ended(callID: String, reason: String)       // server spec §7.3 reasons
}
public enum CallStatus: Sendable, Equatable { case ringing, answered, ended(reason: String), unknown }
public protocol CallSignaling: Sendable {
    var events: AsyncStream<CallServerEvent> { get }
    func invite(callID: String, to accountID: String, video: Bool, payload: Data) async throws
    func answer(callID: String, payload: Data) async throws
    func signal(callID: String, payload: Data) async throws
    func decline(callID: String) async throws
    func hangup(callID: String) async throws
    func status(callID: String) async throws -> CallStatus    // `not_found` → .unknown
    func iceServers() async throws -> [IceServer]              // GET /v1/turn; `calls_disabled` → CallError.disabled
}
public enum CallSealError: Error, Equatable { case wrongSender, undecryptable }
public enum CallError: Error, Equatable { case disabled, fingerprintMismatch, notFound }
public protocol Clock: Sendable { var now: Date { get } }
```

`AudioProfile` is declared here as a plain struct, `{mode: DataMode; maxAverageBitrate, ptime, maxPlaybackRate, wireBudget: Int; inbandFEC, dtx: Bool}`. Its `table` comes in P1.

Fakes:
- `FakeMediaSession` records every call into `calls: [String]`, and its `events` stream can be driven with `emit(_:)`.
- `FakeCallSignaling` records `sent: [(frame: String, callID: String, payload: Data?)]` and is driven with `emit(_:)`. `ice` is configurable; `statusResult` defaults to `.ringing`.
- `PlainCallSignalSealer` JSON-encodes the payload and checks the sender against an injected `senderOf: [Data: String]`, which is a test-only map.
- `ManualClock` provides `advance(_:)`.

- [ ] **Step 1: Failing tests.**
  - `payloadRoundTrip`: a `CallSignalPayload` with every field set survives `JSONEncoder` → `JSONDecoder`.
  - `plainSealerRejectsWrongSender`: opening data sealed for contact `bob` with `from: "carol"` throws `CallSealError.wrongSender`.
  - `fakeSignalingRecordsFrames`: `invite` followed by `hangup` → `sent.map(\.frame) == ["call.invite","call.hangup"]`.
- [ ] **Step 2:** `cd apps/apple/Packages/CallCore && swift test` → FAIL (no package).
- [ ] **Step 3:** Create the package and the files above.
- [ ] **Step 4:** `swift test` → PASS on Linux.
- [ ] **Step 5: Commit** `feat(calls): CallCore contracts and fakes`.

### Task F2: AppCore call protocol additions

**Files:**
- Modify: `AppCore/Sources/AppCore/Calls/CallEngine.swift`, `SimulatedCallEngine.swift`, `Chats/ChatStore.swift`, `Chats/SampleChatStore.swift`
- Modify: `App/Features/Calls/CallView.swift` (new phases and end reasons), `App/AppModel.swift` (answer and decline go through the engine)
- Test: `AppCore/Tests/AppCoreTests/CallEngineContractTests.swift`

**Interfaces — Produces** (owned by this plan per the README; additive):
- `CallPhase`: add `.ringing` (outgoing, waiting) and `.incoming` (a ringing call shown in-app on the Mac).
- `CallEndReason`: add `.declined`, `.missed`, `.answeredElsewhere`, `.failed`. Their user-visible end texts: "Call declined", "No answer", "Answered on another device", "Call failed".
- `CallEngine`: add these requirements:
  - `func answer(video: Bool)`
  - `func decline()`
  - `func setNetwork(_ type: NetworkType)`
  - `func reset()`
  - `var onChange: ((CallState?) -> Void)? { get set }`
- `ChatStore`: add `func recordCall(_ summary: CallSummary, contactID: String, outgoing: Bool, at date: Date)`.
- `SimulatedCallEngine`: `answer` moves `.incoming` to `.active`, and `decline` ends with `.declined`. It also gets a new `simulateIncoming(from:video:)` that sets `.incoming`.
- `SampleChatStore.recordCall` appends a `.call(summary)` message to the contact's chat. When `outgoing`, the sender is `me`.

- [ ] **Step 1: Failing tests.**
  - `simulatedIncomingAnswer`: `simulateIncoming` → phase `.incoming`; `answer(video:false)` → `.active`.
  - `simulatedDecline`: ends with `.declined`, and a later `setMuted` changes nothing.
  - `recordCallAppendsHistory`: `recordCall(.missedAudio, contactID: c, outgoing: false, at: d)` → that chat's last message is `.call(.missedAudio)`, sent by `c` at `d`.
- [ ] **Step 2:** `cd apps/apple/Packages/AppCore && swift test` → FAIL.
- [ ] **Step 3:** Implement. Update `CallView` so every new phase and reason has text, with no `default:`. Move the `MacRootView` incoming banner's answer and decline to `calls.answer` and `calls.decline`.
- [ ] **Step 4:** `swift test` → PASS. Push → the `apple.yml` iOS and macOS jobs are green.
- [ ] **Step 5: Commit** `feat(calls): incoming phases, end reasons, answer/decline, call history hook`.

---

## Lane P — Policy logic (`CallCore/Sources/CallCore/Policy/`, Linux)

Test command for every task: `cd apps/apple/Packages/CallCore && swift test`.

### Task P1: Audio profiles and the SDP munger

**Files:** Create `Policy/AudioProfile+Table.swift`, `Policy/SDPMunger.swift`. Test: `SDPMungerTests.swift`, with fixtures `Tests/CallCoreTests/Fixtures/chrome-offer.sdp` and `ios-webrtc-offer.sdp`, which are real offers with audio and video m-sections and an Opus payload type other than 111 in one of them.

**Interfaces — Produces:**
- `AudioProfile.table: [DataMode: AudioProfile]`, holding the values from Design
- `static func AudioProfile.for(_ mode: DataMode) -> AudioProfile`
- `enum SDPMunger`:
  - `static func apply(_ p: AudioProfile, to sdp: String) -> String`
  - `static func fingerprint(in sdp: String) -> String?`, which returns `"sha-256 AB:…"` from the first `a=fingerprint:` line

How `apply` works:
1. Find Opus's payload type from `a=rtpmap:<pt> opus/48000/2`.
2. On its `a=fmtp:<pt>` line, set or replace `maxaveragebitrate`, `useinbandfec`, `usedtx`, `maxplaybackrate` and `stereo=0`, keeping the other parameters in their order. If the line is missing, create one.
3. In the audio m-section, replace or insert `a=ptime:` and `a=maxptime:` (both set to `ptime`) right after the rtpmap lines.
4. Keep `\r\n` line endings, and never touch the video section.

- [ ] **Step 1: Failing tests.**
  - `tableMatchesDesign`: `AudioProfile.for(.ultraLow)` is `(10000, 60, fec false, dtx true, 16000, 21000)`. Check all four rows.
  - `mungesOpusFmtp`: after applying `.dataSaver` to the chrome fixture, the Opus `fmtp` contains `maxaveragebitrate=16000;useinbandfec=1;usedtx=1;maxplaybackrate=16000;stereo=0` and still contains `minptime=10`.
  - `insertsPtime`: the audio section contains exactly one `a=ptime:40` and one `a=maxptime:40`; the video section is byte-identical to the input.
  - `nonDefaultPayloadType`: the iOS fixture, whose Opus is payload type 109, is munged on the `a=fmtp:109` line.
  - `idempotent`: `apply(p, apply(p, s)) == apply(p, s)`.
  - `crlfPreserved`: the output contains no lone `\n`.
  - `extractsFingerprint`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement with line-based parsing, no regex over the whole SDP.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(calls): audio profiles and SDP munger`.

### Task P2: Video ladder and quality adaptor

**Files:** Create `Policy/VideoLadder.swift`, `Policy/QualityAdaptor.swift`. Test: `QualityAdaptorTests.swift`.

**Interfaces — Produces:**
- `enum VideoLadder`:
  - `static func allowed(ceiling: DataMode) -> VideoTier?`
  - `static func startsOff(ceiling: DataMode, network: NetworkType, settings: CallSettings) -> Bool`
  - `static func pick(budget: Double, maxTier: VideoTier?) -> (tier: VideoTier, maxBitrate: Int)?`
- `struct QualityAdaptor`:
  - `init(ceiling: DataMode, videoWanted: Bool)`
  - `mutating func setCeiling(_:)`
  - `mutating func setVideoWanted(_:)`
  - `mutating func ingest(_ s: MediaStats) -> AdaptorOutput`
- `struct AdaptorOutput: Equatable { mode: DataMode; video: VideoTier?; videoBitrate: Int?; quality: CallQuality; banner: CallBanner? }`. The banner is `.videoLowered` when the tier dropped but video stays on, `.audioOnly` when the adaptor turned video off, and otherwise nil.

- [ ] **Step 1: Failing tests.**
  - `startsAtCeilingWithHeadroom`: ceiling `high`, video wanted, available 600 000 → mode `.high`, tier `.p360`, quality `.good`.
  - `dropsVideoTierFirst`: then available 260 000 → `.p270`, banner `.videoLowered`, mode still `.high`.
  - `videoOffBelow60k`: available 55 000 → video nil, banner `.audioOnly`, mode chosen by audio budget = `.dataSaver` (30 000 ≤ 55 000 × 0.85).
  - `audioStepsDownLast`: available 20 000 → `.ultraLow`, quality `.weak`.
  - `lossStepsDownAfterTwoSamples`: one sample with 15 % loss changes nothing; the second steps the audio down one level.
  - `hysteresisUp`: after degrading, 4 good samples → no change; the 5th → up one level; video returns only after audio is back at the ceiling.
  - `neverExceedsCeiling`: a seeded random sequence of 2 000 samples, with the ceiling changing every 100 → every output `mode <= ceiling`, and its video tier ≤ `allowed(ceiling)`.
  - `videoNotWantedStaysOff`: `videoWanted == false` → video is always nil, with no `audioOnly` banner.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement with the thresholds from Design. Name them `static let` constants: `stepDownMargin = 0.85`, `stepUpMargin = 1.3`, `lossDown = 0.10`, `lossUpMax = 0.02`, `goodSamplesToStepUp = 5`, `audioOnlyBelow = 60_000`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(calls): video ladder and quality adaptor`.

### Task P3: Reconnect policy and relay decision

**Files:** Create `Policy/ReconnectPolicy.swift`, `Policy/RelayDecision.swift`, `Policy/PathChange.swift`. Test: `ReconnectPolicyTests.swift`.

**Interfaces — Produces:**
- `enum RelayDecision { static func policy(_ hiding: IPHiding, isContact: Bool) -> IceTransportPolicy }`, built on `IPHiding.requiresRelay`
- `struct ReconnectPolicy`:
  - `init(isCaller: Bool)`
  - `mutating func ice(_ s: IceState, at t: Date) -> [ReconnectAction]`
  - `mutating func pathChanged(at t: Date) -> [ReconnectAction]`
  - `mutating func tick(at t: Date) -> [ReconnectAction]`
- `enum ReconnectAction: Equatable { case showReconnecting, restartIce, requestRestart, recovered, giveUp }`. The callee gets `requestRestart` where the caller gets `restartIce`.
- `struct PathSnapshot: Equatable { interface: String /* "wifi", "cellular", "wired", "other" */; isSatisfied: Bool }`
- `func isMeaningfulChange(from: PathSnapshot, to: PathSnapshot) -> Bool`

- [ ] **Step 1: Failing tests.**
  - `relayPolicyTable`: for every `IPHiding` × `isContact` combination, `.relay` exactly when `requiresRelay` is true.
  - `reconnectTimeline` (caller):
    - `disconnected` at t0 → `[.showReconnecting]`
    - `tick` at t0+2.4 → `[]`
    - `tick` at t0+2.5 → `[.restartIce]`
    - `tick` at t0+7.5 → `[.restartIce]`
    - `connected` at t0+8 → `[.recovered]`
  - `giveUpAfter30s`: `disconnected` at t0 → `tick` at t0+30 → `[.giveUp]`, and later ticks → `[]`.
  - `failedRestartsImmediately`: `failed` → `[.showReconnecting, .restartIce]`.
  - `calleeRequestsRestart`: the same timeline as the caller, but with `.requestRestart`.
  - `pathChangeRestarts`: while connected, `pathChanged` → `[.restartIce]`, with no banner.
  - `meaningfulPathChange`: `wifi→cellular` true; `wifi→wifi` false; `satisfied→unsatisfied` true.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. Constants: `restartAfter = 2.5`, `retryEvery = 5`, `giveUpAfter = 30` (= `SimulatedCallEngine.reconnectTimeout`).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(calls): reconnect policy and relay decision`.

---

## Lane S — Call session machine (`CallCore/Sources/CallCore/Session/` and `Signal/`, Linux)

The machine is pure. It takes `CallInput`s and returns `[CallEffect]`, and `LiveCallEngine` (K4) carries the effects out. Payloads arrive at the machine already opened: the engine calls `CallSignalSealer.open` first and feeds `.remote(payload)` or `.remoteRejected(error)`.

Test command: `cd apps/apple/Packages/CallCore && swift test --filter Session`.

### Task S1: Outgoing calls

**Files:** Create `Session/CallSessionMachine.swift`, `Session/CallInput.swift`, `Session/CallEffect.swift`. Test: `OutgoingCallTests.swift`.

**Interfaces — Produces:**

```swift
public struct CallContext: Sendable { public var server: ServerPolicy; public var settings: UserSettings; public var network: NetworkType; public var isContact: Bool }
public enum CallInput: Sendable {
    case start(contact: Contact, video: Bool, callID: String)
    case incoming(callID: String, contact: Contact, fromDeviceID: String, ring: CallSignalPayload)
    case answer(video: Bool), decline, hangup
    case setPreference(ModePreference), setVideo(Bool), setMuted(Bool), setSpeaker(Bool)
    case server(CallServerEvent)                 // events with payloads only after `remote`
    case remote(CallSignalPayload)               // opened payload from the expected contact
    case remoteRejected(CallSealError)
    case media(MediaEvent)
    case stats(MediaStats)
    case pathChanged
    case tick
    case contextChanged(CallContext)
}
public enum CallEffect: Sendable, Equatable {
    case sendInvite(CallSignalPayload), sendAnswer(CallSignalPayload), sendSignal(CallSignalPayload)
    case sendDecline, sendHangup
    case configureMedia(policy: IceTransportPolicy, video: Bool)   // engine fetches iceServers first
    case makeOffer(receive: AudioProfile, iceRestart: Bool)        // engine replies with .media-free input `localOffer(sdp)`
    case acceptOffer(sdp: String, send: AudioProfile, receive: AudioProfile)
    case acceptAnswer(sdp: String, send: AudioProfile)
    case rollback, addCandidate(IceCandidate)
    case setAudioMaxBitrate(Int), setVideo(VideoTier?), setMuted(Bool), setNoiseSuppression(NoiseSuppression)
    case closeMedia
    case recordHistory(CallSummary, outgoing: Bool)
    case publish(CallState?)
}
public struct CallSessionMachine: Sendable {
    public init(context: CallContext, clock: Clock)
    public private(set) var state: CallState?
    public mutating func handle(_ input: CallInput) -> [CallEffect]
}
```

`CallInput` also gets `.localOffer(String)` and `.localAnswer(String)`. The engine feeds these back after `makeOffer` and `acceptOffer` return.

- [ ] **Step 1: Failing tests.** Use `ManualClock`, the context `isContact: true`, `ipHiding: .nonContacts`, the default `ServerPolicy`, and mobile network.
  - `startEmitsInviteAndPrewarm`: `.start(video:false)` → effects contain:
    - `.configureMedia(policy: .all, video: false)`
    - `.sendInvite(kind: .ring, video: false, ceiling: <DataModePolicy.ceiling(…)>)`
    - `.publish` with phase `.ringing`

    `effectiveMode` equals that ceiling.
  - `strangerForcesRelay`: with `isContact: false` → `.configureMedia(policy: .relay, …)`.
  - `answeredOfferIsAccepted`: then `.server(.answered)` followed by `.remote(offer with fingerprint F and sdp containing a=fingerprint:F)` → `.acceptOffer(send: profile(effective), receive: profile(ceiling))`. Then `.localAnswer(sdp)` → `.sendSignal(kind: .answer, dtlsFingerprint: <from sdp>)`.
  - `iceConnectedGoesActive`: `.media(.iceState(.connected))` → phase `.active`.
  - `noAnswerEnds`: `.server(.ended(reason: "missed"))` → phase `.ended(.missed)`, `.closeMedia`, `.recordHistory(.audio(duration: 0), outgoing: true)`.
  - `declinedEnds`: reason `"declined"` → `.ended(.declined)`.
  - `callerTrickles`: after the answer is sent, `.media(.localCandidate(c))` → `.sendSignal(kind: .candidate)`. Before `call.answered`, candidates are buffered and sent after the answer.
  - `elapsedCountsFromActive`: after `.tick`s at +1 s and +2 s, `state.elapsed == 2`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement the caller paths.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(calls): call session machine — outgoing`.

### Task S2: Incoming calls

**Files:** Modify `Session/CallSessionMachine.swift`. Test: `IncomingCallTests.swift`.

- [ ] **Step 1: Failing tests.**
  - `incomingPrewarmsAndOffersLocally`: `.incoming(ring video:true)` → `.configureMedia(…, video: <!startsOff>)`, then `.makeOffer(receive: profile(ceiling), iceRestart: false)`; phase `.incoming`. No signal is sent before the answer.
  - `answerSendsOffer`: `.localOffer(sdp)`, then `.answer(video:true)` → `.sendAnswer(kind: .offer, sdp, dtlsFingerprint: fingerprint(in: sdp), ceiling)`, followed by every buffered candidate as `.sendSignal(kind: .candidate)`.
  - `answerBeforeLocalOfferWaits`: `.answer` arrives before `.localOffer` → the answer is sent when `.localOffer` arrives.
  - `remoteAnswerAccepted`: `.remote(kind: .answer, matching fingerprint)` → `.acceptAnswer(send: profile(effective))`.
  - `answeredElsewhere`: `.server(.ended(reason: "answered_elsewhere"))` while `.incoming` → `.ended(.answeredElsewhere)`, `.closeMedia`, and **no** history entry.
  - `declineByUser`: `.decline` → `.sendDecline`, `.ended(.declined)`, `.recordHistory(.missedAudio, outgoing: false)` (or `.missedVideo` for a video ring).
  - `missedIncoming`: `.server(.ended(reason: "missed"))` → `.recordHistory(.missedVideo, outgoing: false)` for a video ring.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(calls): call session machine — incoming`.

### Task S3: Mid-call — modes, renegotiation, fingerprints, reconnect, end

**Files:** Modify `Session/CallSessionMachine.swift`. Create `Signal/FingerprintCheck.swift`. Test: `MidCallTests.swift`.

**Interfaces — Consumes:** `QualityAdaptor` (P2), `ReconnectPolicy` and `RelayDecision` (P3), `SDPMunger.fingerprint` (P1). Until lane P merges, lane S uses stubs with these exact signatures, which P's versions then replace.

- [ ] **Step 1: Failing tests.**
  - `remoteFromWrongSenderIsRejected`: `.remoteRejected(.wrongSender)` during setup → `.ended(.failed)`, `.sendHangup`, `.closeMedia`; no `accept*` effect.
  - `fingerprintMismatchEndsCall`: an offer whose `dtlsFingerprint` differs from its SDP's `a=fingerprint` → `.ended(.failed)`; no `acceptOffer`.
  - `statsDriveAdaptor`: once active, `.stats(available: 55_000)` → `.setVideo(nil)`, `.setAudioMaxBitrate(16000)`, and a published state with banner `.audioOnly` and `effectiveMode == .dataSaver`.
  - `modeChangeRenegotiates`: when the effective mode's `ptime` changes (balanced→dataSaver), the caller emits `.makeOffer(…, iceRestart:false)`, then `.sendSignal(kind: .renegotiate)`; the callee answers with `.sendSignal(kind: .renegotiateAnswer)`. The bitrate-only change high→balanced emits only `.setAudioMaxBitrate`.
  - `inCallPreferenceSignalsCeiling`: `.setPreference(.fixed(.ultraLow))` → `.sendSignal(kind: .modeChange, ceiling: .ultraLow)`; `state.inCallPreference == .fixed(.ultraLow)`.
  - `peerCeilingCaps`: a remote `modeChange(ceiling: .dataSaver)` → `effectiveMode <= .dataSaver` from then on.
  - `glareCallerWins`: the callee, with a renegotiate pending, gets the caller's `renegotiate` → `.rollback`, then `.acceptOffer`.
  - `reconnectFlow`: `.media(.iceState(.disconnected))` → phase `.reconnecting`, banner `.reconnecting`; 2.5 s of ticks later the caller gets `.makeOffer(iceRestart: true)`; 30 s → `.ended(.connectionLost)`, `.sendHangup`, `.recordHistory(.audio(duration: <active seconds>), …)`.
  - `pathChangeRestartsIce`: `.pathChanged` while active (caller) → `.makeOffer(iceRestart: true)`.
  - `endedCallIgnoresLateEvents`: after the end, every input gives `[]`, and `state` is unchanged.
  - `exactlyOneHistoryEntry`: in a random sequence of 500 inputs, `recordHistory` appears at most once per call, and exactly once when the call ended after `.answered`, `.missed`, `.declined` or `.connectionLost`.
  - `hangupByUser`: `.hangup` → `.sendHangup`, `.ended(.hungUp)`, `.recordHistory(.video(duration: d))` when `isVideoCall`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS. Run the full `swift test`, too.
- [ ] **Step 5: Commit** `feat(calls): mid-call adaptation, renegotiation, fingerprint check, reconnect`.

---

## Lane M — Media (`apps/apple/Packages/CallMedia`, macOS CI)

Test command: `cd apps/apple/Packages/CallMedia && swift test`. It runs on macOS only, in a new `callmedia` job in `apple.yml` on `macos-26`. **Consumes:** `WebRTCBinary` (plan 3) and the `RTCAudioCapturePostProcessing` hook (plan 3, a README contract addition).

### Task M1: `WebRTCMediaSession`

**Files:**
- Create: `CallMedia/Package.swift`, depending on `../CallCore` and `../WebRTCBinary`
- Create: `Sources/CallMedia/WebRTCMediaSession.swift`, `Sources/CallMedia/Factory.swift`
- Modify: `.github/workflows/apple.yml`, adding the `callmedia` job
- Test: `Tests/CallMediaTests/LoopbackTests.swift` (XCTest, because it needs a run loop and timeouts)

**Interfaces — Produces:**
- `final class WebRTCMediaSession: MediaSession`
- `enum MediaFactory`, with `static func shared() -> RTCPeerConnectionFactory`: one factory per process, H.264 hardware encoder and decoder factories, and the custom audio processing module when the hook exists

Peer connection configuration:
- `sdpSemantics = .unifiedPlan`
- `continualGatheringPolicy = .gatherContinually`
- `bundlePolicy = .maxBundle`, `rtcpMuxPolicy = .require`
- `iceTransportPolicy` comes from `configure`
- `iceCandidatePoolSize = 1` when prewarming

The audio transceiver is `sendrecv`. When video is requested, a video transceiver (`sendrecv`) uses `RTCCameraVideoCapturer`.

- [ ] **Step 1: Failing tests.** Two sessions in one process, wired together.
  - `testLoopbackConnects`: both `configure(iceServers: [], policy: .all, video: false)`; A `makeOffer`, B `acceptOffer`, A `acceptAnswer`; candidates are exchanged → both emit `.iceState(.connected)` within 10 s.
  - `testLocalOfferIsMunged`: `makeOffer(receive: .for(.ultraLow))` → the local description contains `a=ptime:60` and `maxaveragebitrate=10000`.
  - `testSendProfileReachesEncoder`: after connecting with `send: .for(.ultraLow)` on A, `stats()` on A after 5 s shows the outbound audio bitrate ≤ 14 000 bps. This uses a test-only audio source: a file tone, from `RTCAudioSource` with a fake ADM on macOS.
  - `testRelayOnlyGathersOnlyRelayCandidates`: `policy: .relay` with no TURN servers → no `.localCandidate` within 3 s.
  - `testIceRestartChangesUfrag`: `makeOffer(iceRestart: true)` gives a different `a=ice-ufrag`.
  - `testRollback`.
- [ ] **Step 2:** Push → the job is red (FAIL).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Push → green (`gh run watch`).
- [ ] **Step 5: Commit** `feat(calls): WebRTC media session`.

### Task M2: Stats, bitrate control, video tiers

**Files:** Create `Sources/CallMedia/StatsReader.swift`, `Sources/CallMedia/VideoController.swift`. Test: `StatsTests.swift`.

**Interfaces — Produces:**
- `stats()` fills `MediaStats` from `RTCStatisticsReport`:
  - `availableOutgoingBitrate` and `currentRoundTripTime` from the nominated `candidate-pair`
  - `bytesSent`, `bytesReceived`, `packetsSent` and `packetsReceived` from `transport`
  - inbound audio `packetsLost` and `jitter`; the loss fraction is the delta loss over the delta (received + lost)
  - `usingRelay` from the local candidate's `candidateType == "relay"`
  - `audioCodec` from the `codec` mimeType
- `setVideo(tier)` picks the camera format closest to the tier's size, sets `adaptOutputFormat` to tier width × height × fps, and sets the sender's `maxBitrateBps`. `nil` disables the track and stops the capturer.
- `setAudioMaxBitrate` sets the audio sender's `encodings[0].maxBitrateBps`.

- [ ] **Step 1: Failing tests.**
  - `testStatsAfterConnect`: in loopback, after 3 s, `bytesSent > 0`, `packetsReceived > 0`, `usingRelay == false`, `audioCodec == "audio/opus"`.
  - `testLossFractionIsDelta`: a stub report sequence (an injected `StatsSource` protocol over the raw dictionary) of lost 0→10 and received 100→190 → fraction `10/100`.
  - `testAudioMaxBitrateApplied`: after `setAudioMaxBitrate(8000)`, the sender parameters show 8000.
  - `testVideoTierFormat`: `VideoController.format(for: .p270, available: [formats])` picks 480×270, or the nearest larger.
- [ ] **Step 2:** Push → red.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Push → green.
- [ ] **Step 5: Commit** `feat(calls): media stats, bitrate and video tier control`.

### Task M3: Noise suppression

**Files:**
- Create: `CallMedia/Sources/RNNoise/` (vendored xiph/rnnoise C sources at a pinned commit, with `LICENSE` and `VENDORED.md` recording the commit and its BSD-3 notice)
- Create: `Sources/CallMedia/RNNoiseProcessor.swift`, `Sources/CallMedia/NoiseSuppressionController.swift`, `Sources/CallMedia/VoiceIsolation.swift`
- Test: `NoiseTests.swift`, with fixtures `speech-clean.wav` and `speech-fan.wav` (16-bit 48 kHz mono, 5 s, CC0 or self-recorded)

**Interfaces — Produces:**
- `final class RNNoiseProcessor`: `func process(_ frame: inout [Float])`, where a frame is 480 samples
- `NoiseSuppressionController`:
  - `apply(_ mode: NoiseSuppression)`, which installs or removes the processor on the post-processing hook
  - `isVoiceIsolationActive: Bool`
- `enum VoiceIsolation`: `static var shouldShowHint: Bool` and `static func showPicker()`, using `AVCaptureDevice.preferredMicrophoneMode` / `activeMicrophoneMode` and `showSystemUserInterface(.microphoneModes)` on iOS and macOS 12+

- [ ] **Step 1: Failing tests.**
  - `testRNNoiseReducesNoise`: processing `speech-fan.wav` lowers its energy in the 0.5 s non-speech lead-in by at least 10 dB.
  - `testRNNoiseKeepsSpeech`: the energy of the speech segment in `speech-clean.wav` changes by less than 3 dB.
  - `testOneSuppressorAtATime`: with `isVoiceIsolationActive` stubbed true, `apply(.enhanced)` leaves the processor bypassed.
  - `testHookMissingFallsBack`: with no hook (`MediaFactory.hasPostProcessingHook == false`), `apply(.enhanced)` behaves like `.standard`, and `NoiseSuppressionController.enhancedAvailable == false`.
- [ ] **Step 2:** Push → red.
- [ ] **Step 3:** Implement. `RNNoise` is a SwiftPM C target with `cSettings: [.define("RNNOISE_EXPORT=")]`.
- [ ] **Step 4:** Push → green.
- [ ] **Step 5: Commit** `feat(calls): RNNoise enhanced suppression and Voice Isolation hint`.

---

## Lane K — Platform and app (`apps/apple/App/Calls/`)

Tests: AppCore/CallCore unit tests where logic is extracted; XCTest in `AppUITests` for UI; macOS CI for builds; and devices for CallKit and PushKit (I1 checklist).

### Task K1: ServerKit signaling adapter

**Files:** Create `App/Calls/LiveCallSignaling.swift`. Test: `apps/apple/Packages/CallCore/Tests/CallCoreTests/FrameMappingTests.swift`, covering the pure mapping, which lives in `CallCore/Sources/CallCore/Signal/FrameMapping.swift`.

**Interfaces — Consumes:** `LiveConnection.events` and `LiveConnection.send(_:_:)`, `ServerEvent.callRing`/`callAnswered`/`callSignal`/`callEnded`, plan 6's `ServerClient` method for `GET /v1/turn`, and `ServerError.code`. **Produces:** `final class LiveCallSignaling: CallSignaling`, plus these pure helpers in `CallCore`:
- `enum FrameMapping`:
  - `static func event(_ e: ServerEvent) -> CallServerEvent?`
  - `static func frame(invite …) -> (type: String, data: [String: String])`, which encodes base64url without padding
  - `static func iceServers(fromTurnJSON: Data) throws -> [IceServer]`
  - `static func status(fromAck: Data) -> CallStatus`

- [ ] **Step 1: Failing tests** (Linux, in `CallCoreTests`, since `ServerEvent` is mirrored there as a test fixture).
  - `mapsRing`: every field is carried over.
  - `inviteFrame`: `type == "call.invite"` and the data keys are exactly `callID`, `to`, `video` and `payload` (server spec §7.3).
  - `turnJSON`: the server's `{iceServers:[{urls,username,credential}],ttl}` maps to an `IceServer` list.
  - `statusAck`: `{"state":"ended","reason":"declined"}` → `.ended(reason:"declined")`, and an error code of `not_found` → `.unknown`.
- [ ] **Step 2:** `swift test` → FAIL.
- [ ] **Step 3:** Implement the helpers, then `LiveCallSignaling`, which caches TURN servers until 10 minutes before their `ttl` runs out.
- [ ] **Step 4:** `swift test` → PASS. macOS CI builds the app → green.
- [ ] **Step 5: Commit** `feat(calls): server signaling adapter and TURN fetch`.

### Task K2: iPhone CallKit, PushKit, audio session — owner review

**Files:**
- Create: `App/Calls/Platform/CallKitController.swift`, `App/Calls/Platform/VoIPPushCenter.swift`, `App/Calls/Platform/AudioSessionBridge.swift` (all `#if os(iOS)`)
- Create: `CallCore/Sources/CallCore/Session/VoIPPushDecision.swift` (pure)
- Modify: `apps/apple/project.yml`:
  - `UIBackgroundModes: [voip, audio, remote-notification]`
  - entitlement `aps-environment`
- Test: `CallCoreTests/VoIPPushDecisionTests.swift`

**Interfaces — Produces:**
- `enum VoIPPushDecision { static func decide(mustReport: Bool?, payload: PushPayloadState, isStranger: Bool, status: CallStatus?) -> VoIPPushAction }`
  - `mustReport == nil` means the device is below iOS 26.4.
  - `enum PushPayloadState { case ring(CallSignalPayload, contact: Contact), undecryptable, empty }`
  - `enum VoIPPushAction: Equatable { case reportRing(name: String, video: Bool), reportThenEnd(name: String, reason: EndReason), ignore }`, where `EndReason` is `failed`, `remoteEnded` or `unanswered`.
- `CallKitController` (`CXProviderDelegate`):
  - `reportIncoming(callID: UUID, name: String, handle: String, video: Bool) async throws`
  - `reportEnded(callID: UUID, reason: CXCallEndedReason)`
  - `requestStart(contact: Contact, video: Bool)`, through `CXCallController` / `CXStartCallAction`
  - its delegate methods feed `LiveCallEngine` `answer`, `decline` or `hangup`, `setMuted` (from `CXSetMutedCallAction`), and audio activation
- Server call IDs map to `UUID`s: the server `callID` is a client-made UUIDv4 string, used directly.
- `VoIPPushCenter`:
  - one `PKPushRegistry(queue: .main)` with `desiredPushTypes = [.voIP]`
  - hands the token to plan 6's registrar as kind `voip`
  - `didReceiveIncomingPush` decodes `d`, unpads it (2-byte length, as in server spec §7.4), opens it through the `CallSignalSealer`, applies `VoIPPushDecision`, reports **synchronously before** calling the completion, then hands the call to the engine
  - on iOS 26.4+ it also implements the metadata delegate (`mustReport`)
- `AudioSessionBridge`:
  - `RTCAudioSession.sharedInstance().useManualAudio = true`
  - `didActivate` → `audioSessionDidActivate` + `isAudioEnabled = true`; `didDeactivate` → the reverse
  - category `.playAndRecord`, mode `.voiceChat`, options `[.allowBluetooth, .allowBluetoothA2DP]`
  - speaker via `overrideOutputAudioPort`

- [ ] **Step 1: Failing tests.** `testEveryVoIPPushIsReported` is a table:

  | `mustReport` | payload | stranger | status | expected |
  |---|---|---|---|---|
  | nil | ring | no | ringing | `.reportRing` |
  | nil | undecryptable | — | — | `.reportThenEnd(.failed)` |
  | nil | empty | — | — | `.reportThenEnd(.failed)` |
  | nil | ring | yes | ringing | `.reportThenEnd(.unanswered)` |
  | nil | ring | no | ended | `.reportThenEnd(.remoteEnded)` |
  | true | ring | yes | ringing | `.reportThenEnd(.unanswered)` |
  | false | ring | yes | ringing | `.ignore` |
  | false | ring | no | ringing | `.reportRing` |

  The rule: when `mustReport` is nil or true, the result is never `.ignore`.
- [ ] **Step 2:** `swift test` → FAIL.
- [ ] **Step 3:** Implement the pure decision, then the platform files. Copy the iOS 26.4 delegate selector from the SDK header, behind `if #available(iOS 26.4, *)`.
- [ ] **Step 4:** `swift test` → PASS; macOS CI iOS build → green.
- [ ] **Step 5: Commit** `feat(calls): CallKit and PushKit on iPhone`.

### Task K3: Mac ringing

**Files:**
- Create: `App/Calls/Platform/MacRinger.swift` (`#if os(macOS)`)
- Modify: `apps/apple/NotificationService/NotificationService.swift` (plan 7's NSE) to add the call branch
- Modify: `App/Root/MacRootView.swift`
- Test: `CallCoreTests/CallNotificationTextTests.swift`, covering the pure text builder `CallCore/Sources/CallCore/Session/CallNotificationText.swift`

**Interfaces — Produces:**
- `enum CallNotificationText { static func content(callerName: String, video: Bool, ringAge: TimeInterval) -> (title: String, body: String, isCall: Bool) }`
  - body `"Incoming call"` or `"Incoming video call"`
  - when `ringAge >= 45`: body `"Missed call"`, and `isCall == false`
- `MacRinger`:
  - registers the category `CALL`, with actions `ANSWER` (foreground) and `DECLINE` (destructive)
  - posts a time-sensitive local notification on `call.ring`, sets `AppModel.incomingCaller`, and plays the system ring sound in a loop until answered, declined or ended
  - `userNotificationCenter(_:didReceive:)` maps the actions to `LiveCallEngine.answer` / `decline` after a `call.status` check
- The NSE branch: when the opened content is `CallSignalPayload.kind == .ring`, set title, body, `categoryIdentifier = "CALL"` and `interruptionLevel = .timeSensitive`. It stays below 24 MB and makes no network calls.

- [ ] **Step 1: Failing tests.** `incomingTexts` (audio and video) and `staleRingIsMissed` (ringAge 45 → `"Missed call"`).
- [ ] **Step 2:** `swift test` → FAIL.
- [ ] **Step 3:** Implement, and add the strings to `Localizable.xcstrings`. Add the Time Sensitive Notifications capability (`com.apple.developer.usernotifications.time-sensitive`) for `AppMac` in `project.yml`.
- [ ] **Step 4:** `swift test` → PASS; macOS CI → green, including `MacIncomingCallUITests.testBannerAnswerStartsCall` with `-sampleData`.
- [ ] **Step 5: Commit** `feat(calls): Mac incoming call ringing`.

### Task K4: `LiveCallEngine`, app wiring, diagnostics

**Files:**
- Create: `App/Calls/LiveCallEngine.swift`, `App/Calls/PathMonitor.swift`, `App/Features/Calls/CallInfoSheet.swift`
- Modify: `App/AppModel.swift`, which picks `LiveCallEngine` unless the launch argument `-sampleData` is set
- Modify: `App/Features/Calls/CallView.swift`, which adds the info button and the Voice Isolation hint row
- Modify: `App/Features/Settings/…` (noise suppression row: "Enhanced" is disabled with the footnote "Not available on this device yet" when `enhancedAvailable == false`)
- Test: `AppUITests/iOS/CallInfoSheetUITests.swift` (sample data), and `CallCoreTests/CallInfoTextTests.swift` for the pure formatter `CallCore/Sources/CallCore/Session/CallInfoText.swift`

**Interfaces — Consumes:** everything above. **Produces:** `@MainActor final class LiveCallEngine: CallEngine`, which:
- owns one `CallSessionMachine` per call
- runs effects in order on a serial `Task` chain
- feeds media events, server events (after `CallSignalSealer.open`), stats every 2 s, ticks every 0.5 s, and path changes from `PathMonitor` into the machine
- publishes `state` through `onChange`
- calls `ChatStore.recordCall` for `.recordHistory`
- on iOS, routes `start` through `CallKitController.requestStart` so the system knows about outgoing calls

`CallInfoText`:
- `rows(stats: MediaStats, state: CallState) -> [(label: String, value: String)]`, giving the labels "Connection", "Delay", "Sound lost", "Quality", "Data used"
- "Connection" is `"Direct"`, or `"Through your server"` when `usingRelay`
- the label set never includes "codec", "kbps" or "relay"

- [ ] **Step 1: Failing tests.**
  - `infoRows`: `usingRelay: true`, RTT 0.123, loss 0.031, effective `.dataSaver`, bytes 1 500 000 → `[("Connection","Through your server"),("Delay","123 ms"),("Sound lost","3%"),("Quality","Data saver"),("Data used","1.5 MB")]`. "Data used" uses `DataFormat.used`.
  - `infoRowsPlainWords`: no label or value contains `codec`, `kbps` or `relay` (case-insensitive).
  - UI test `testInfoSheetOpens`: `-skipOnboarding -uiTesting -sampleData`, start a call, tap "Call info" → five rows are visible, each with a VoiceOver label.
- [ ] **Step 2:** `swift test` → FAIL; UI test → red.
- [ ] **Step 3:** Implement. `bytesUsed` follows the Design formula. Log codec and bitrate with `Logger(subsystem: "org.example.app", category: "calls")` at `.debug`.
- [ ] **Step 4:** `swift test` → PASS; macOS CI (iOS and macOS UI tests) → green.
- [ ] **Step 5: Commit** `feat(calls): LiveCallEngine, path monitor, call info sheet`.

---

## Stage 2 — Integration

### Task I1: Merge, two-engine end-to-end test, device checklist

**Files:**
- Create: `apps/apple/Packages/CallMedia/Tests/CallMediaTests/TwoEngineTests.swift`. It runs two `CallSessionMachine` + `WebRTCMediaSession` pairs joined by an in-memory `CallSignaling` pair and `PlainCallSignalSealer`.
- Create: `docs/testing/calls-device-checklist.md`

- [ ] **Step 1:** Merge the lanes in the order P, S, M, K; after each merge, run `swift test` (CallCore) and push. Expected: green after every merge.
- [ ] **Step 2: Failing tests** in `TwoEngineTests`:
  - `testAudioCallConnects`: A starts, and B gets the ring, answers, and offers. A answers → both reach `.active` within 10 s; the history after hangup has one `.audio` entry on each side.
  - `testFingerprintSwapRejected`: the in-memory pipe swaps the `dtlsFingerprint` field → both sides end `.failed`, and neither calls `setRemoteDescription` with the tampered SDP.
  - `testModeChangeRenegotiates`: B sets `.fixed(.ultraLow)` → within 5 s, A's local SDP shows `a=ptime:60` in the remote description it applied, and A's outbound bitrate is ≤ 14 000 bps.
- [ ] **Step 3:** Fix the wiring only, never lane behavior, until they pass (macOS CI green).
- [ ] **Step 4:** Write `calls-device-checklist.md` for the owner. Each line gives the step, the expected result, and a pass/fail box:
  1. iPhone locked, app killed: a call from the Mac rings through CallKit within 3 s; Answer → voice both ways.
  2. iPhone locked: the caller hangs up while it rings → CallKit stops ringing (via `call.ended`, or `call.status` after the push).
  3. iOS 26.4+ device in the foreground on a call: a second call's push → no app termination (`mustReport` handling).
  4. Stranger calls: iOS 17 shows a call that ends at once and a missed call in the request; iOS 26.4+ shows no ring.
  5. Mac with the app quit: a call from the iPhone → time-sensitive notification → Answer → connected.
  6. Wi-Fi → cellular mid-call: "Reconnecting…" for at most 3 s, then the call continues; an airplane-mode toggle for 35 s → "Call ended: connection lost".
  7. IP hiding `always`: the Call info sheet shows "Through your server"; a packet capture on the iPhone's network shows no peer IP.
  8. Mode sheet → Ultra-low: plan 5's capture shows 60 ms packets and ≤ 21 kbps per direction.
  9. The weak network profile in Network Link Conditioner (Edge) → video drops first, then "Switched to audio only", and voice continues.
  10. Bluetooth headset: route, mute from the headset button, and CallKit mute all stay in sync.
  11. Noise: Enhanced with a fan → the other side hears less fan; with Voice Isolation on → the hint is hidden, and RNNoise is bypassed (debug log).
  12. VoiceOver: every call control is announced; Dynamic Type at the largest size leaves no clipped controls.
- [ ] **Step 5: Commit** `test(calls): two-engine end-to-end and device checklist`.
- [ ] **Step 6:** Whole-branch review on Opus (superpowers:requesting-code-review) with this plan's Review Focus and every lane's `Ruling:` lines. The owner reviews the paths listed in Global Constraints and runs the device checklist before merging to `main`.

## Not in this plan

- group calls and the SFU (Phase 2)
- SFrame
- screen sharing
- syncing call history across devices
- CallKit-less calling for the China storefront
- DRED and 120 ms ptime (plan 3 build flags)
