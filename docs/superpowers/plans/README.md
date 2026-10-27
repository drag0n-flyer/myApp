# Build Roadmap

All plans needed to reach the GOAL.md Phase 1 exit: an invite-only private beta. Each plan is self-contained and lists the plans it depends on. **This file fixes the names that cross plan boundaries.** A plan may add to them, but must not rename them.

## Plans

| # | Plan | Phase | Depends on | Agents | Owner review |
|---|---|---|---|---|---|
| 1 | [App UI on sample data](2026-10-27-app-ui-sample-data.md) | 0 | — | done (branch `feat/app-ui-sample-data`) | — |
| 2 | [Go server](2026-10-27-go-server.md) | 1 | — | 1 → 5 lanes → 1 | identity, delivery, calls, push |
| 3 | [WebRTC + Opus build](2026-10-27-webrtc-opus-build.md) | 0 | — | 1 | all (media build) |
| 4 | [MLS client core](2026-10-27-mls-client-core.md) | 0 | — | 1–2 | all (cryptography) |
| 5 | [Call lab and measurements](2026-10-27-call-lab.md) | 0 | 3, server lane C | 1–2 + owner on devices | — |
| 6 | [Client networking](2026-10-27-client-networking.md) | 1 | 1, 2 (spec only; server needed for its end-to-end task) | up to 3 lanes | auth, push |
| 7 | [Client messaging](2026-10-27-client-messaging.md) | 1 | 4, 6 | up to 4 lanes | MLS use, NSE, escrow |
| 8 | [Client calling](2026-10-27-client-calling.md) | 1 | 3, 4, 6 | up to 4 lanes | CallKit/PushKit, media keys |
| 9 | [Security, docs, release](2026-10-27-security-docs-release.md) | 1 (beta gate) | 2, 7, 8 for final checks | 1 + owner | all |

## Running order (at most 5 coding agents at once)

All coding agents use Sonnet 5.5. Final whole-branch reviews use Opus.

1. **Now:**
   - plan 2's foundation
   - plan 3 (mostly CI compile time)
   - plan 4
   - plan 9's documents (threat model, protocol spec)
2. **When plan 2's foundation lands:** its 5 lanes. Plans 3 and 4 keep running, so each time an agent finishes, start the next ready item rather than exceeding 5.
3. **After plan 1 merges to `main`:** plan 6, built against the server spec. Its end-to-end task waits for plan 2.
4. **After plan 3, and plan 2's lane C:** plan 5. The owner runs the device measurements.
5. **After plans 4 and 6:** plans 7 and 8 in parallel. Their lanes share the 5 slots.
6. **Last:** plan 9's release checklist, then the beta.

## Cross-plan contracts

### Repository layout

```
server/                          plan 2
apps/apple/project.yml           plan 1; plans 6–8 add targets/entitlements
apps/apple/App/                  plan 1 UI; plans 6–8 swap sample services for live ones
apps/apple/Packages/AppCore      plan 1; domain models + service protocols
apps/apple/Packages/WebRTCBinary plan 3 (product and binary target `WebRTC`)
tools/webrtc/                    plan 3 (pin, patches, build scripts, reserved-symbols.txt)
apps/apple/Packages/SecureMessaging  plan 4
apps/apple/Packages/SecureMessaging/mlsffi plan 4 (our thin `mlsffi` crate over mls-rs)
apps/apple/Packages/ServerKit    plan 6 (products ServerKit, ServerKitTesting)
apps/apple/Packages/LocalStore   plan 7
apps/apple/Packages/Messaging    plan 7 (content envelope, outbox, inbox, LiveChatStore, Escrow)
apps/apple/Packages/CallCore     plan 8 (pure call logic, Linux-testable)
apps/apple/Packages/CallMedia    plan 8 (WebRTC media session)
apps/apple/NotificationService/  plan 7 (NSE source, shared by iOS and Mac NSE targets); plan 8 fills NotificationService+Call.swift
tools/call-lab/                  plan 5 (app, CallLabKit, RNNoiseLab, rig and scoring scripts)
docs/protocol/                   plan 9 (protocol spec, test vectors)
docs/security/ docs/legal/ docs/release/ docs/ops/   plan 9
docs/measurements/               plan 5
```

### Identifiers

- Bundle ID placeholders (as in `project.yml`):
  - iOS app `org.example.app`, its NSE `org.example.app.nse`
  - Mac app `org.example.app.mac`, its NSE `org.example.app.mac.nse`
  - Every list of app IDs (push topics, passkey `webcredentials`, the server's `APP_APPLE_APP_IDS`) names both apps.
  - App group: `group.org.example.app`
  - Keychain access group: `$(AppIdentifierPrefix)org.example.app.shared`
- An MLS credential's identity is the UTF-8 bytes of `"<accountID>/<deviceID>"`, using server IDs (plan 2 §6).
- Encryption mode strings are the server's: `e2ee` and `server_readable`.
- Contact IDs in AppCore are server account IDs.
- Device-linking QR: `https://<host>/link/<linkID>#<secret>.<X25519 public key, b64url>`; the approver seals the link snapshot to that key (server design §7.1).
  - Before approving, the approver fetches `POST /v1/link/{linkID}/info {secret}` → `{deviceName, platform, createdAt}` (plan 2 lane A adds it; plan 6 shows it, then requires Face ID / Touch ID).
- Passkeys work only for hosts in the app's associated domains (the project's servers). On other servers, sign-up skips the passkey and sign-in uses an email code.

### `SecureMessaging` (plan 4 owns it; plans 7 and 8 consume it)

```swift
public struct GroupID: Hashable, Sendable, Codable { public let raw: Data }
public struct CommitBundle: Sendable { public let commit: Data; public let welcome: Data?; public let newEpoch: UInt64 }
public enum Incoming: Sendable {
    case application(group: GroupID, sender: String /* "<accountID>/<deviceID>" */, plaintext: Data, epoch: UInt64)
    case commit(group: GroupID, newEpoch: UInt64, removedMe: Bool)
    case joined(group: GroupID, epoch: UInt64)          // from a Welcome
}
public protocol MLSEngine: Sendable {
    func makeKeyPackages(count: Int) async throws -> [Data]
    func createGroup() async throws -> GroupID
    func add(keyPackages: [Data], to group: GroupID) async throws -> CommitBundle
    func remove(members: [String], from group: GroupID) async throws -> CommitBundle
    func commitAccepted(_ group: GroupID) async throws   // server said 200: merge the pending commit
    func commitRejected(_ group: GroupID) async throws   // server said stale_epoch: discard it
    func encrypt(_ plaintext: Data, in group: GroupID) async throws -> (ciphertext: Data, epoch: UInt64)
    func process(_ message: Data) async throws -> Incoming   // Welcome, commit, or application message
    func epoch(of group: GroupID) async throws -> UInt64
    func exportSecret(group: GroupID, label: String, length: Int) async throws -> Data
    func members(of group: GroupID) async throws -> [String]   // added by plan 7's request
    func memberKeys(of group: GroupID) async throws -> [MemberKey]   // requested by plan 7 (safety numbers, device lines; OPEN-6/7): sorted by identity, includes this device
}
public struct MemberKey: Sendable, Equatable { public let identity: String; public let signatureKey: Data }   // the leaf's MLS signature public key; FakeMLSEngine: Data("fake-sig:\(identity)".utf8)
public enum MLSError: Error   // plan 4 defines the cases
// The engine re-reads group state from storage on every call: the NSE changes it from another process.
// Product `SecureMessagingCore` (no binary target, builds on Linux) holds the protocol, the types,
// `FakeMLSEngine` and `PushPayload.unpad(_:)`; product `SecureMessaging` adds the real engine.
public final class FakeMLSEngine: MLSEngine   // plaintext pass-through for tests in plans 6–8
public func makeMLSEngine(identity: String, storage: URL) throws -> MLSEngine   // the real engine
```

### `ServerKit` (plan 6 owns it; plans 7 and 8 consume it)

```swift
public struct ServerError: Error, Equatable, Sendable { public let status: Int; public let code: String; public let retryAfter: Int?; public let details: [String: Int]? }
public actor ServerClient {           // one method per server spec §7 endpoint, async throws
    public init(host: String, session: SessionStore, transport: HTTPTransport = URLSessionTransport())
}
public protocol HTTPTransport: Sendable { func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) }
public struct InboxItem: Codable, Sendable, Equatable   // server spec §7.2 fields, payload as Data
public enum ServerEvent: Sendable, Equatable {
    case inbox(InboxItem), deviceRevoked(String)
    case callRing(callID: String, fromAccountID: String, fromDeviceID: String, video: Bool, payload: Data)
    case callAnswered(callID: String, deviceID: String, payload: Data)
    case callSignal(callID: String, fromDeviceID: String, payload: Data)
    case callEnded(callID: String, reason: String)
}
public actor LiveConnection {         // the /v1/ws socket, with reconnects
    public var events: AsyncStream<ServerEvent> { get }
    public func send(_ type: String, _ data: some Encodable & Sendable) async throws -> Data?   // ack data
    public var states: AsyncStream<ConnectionState> { get }
    public func start(); public func stop()
}
public enum PushHandle { public static func seal(_ plain: Plain, gatewayKey: Data) throws -> Data }  // plan 6
// PushRegistrar gains a method that takes the VoIP token from plan 8's VoIPPushCenter (the only PKPushRegistry).
// ServerKit builds on Linux (Apple-only parts behind #if canImport). LiveAccountService lives in ServerKit.
public final class SessionStore: Sendable   // Keychain, shared access group; accountID, deviceID, token, host
```

### AppCore service protocols

These plans replace sample services with live ones behind the existing protocols. Each owning plan may make its protocol `async`/`throws` and update `AppModel` to match.

| Protocol | Sample (plan 1) | Live | Owner |
|---|---|---|---|
| `AccountService` | `SampleAccountService` | `LiveAccountService` | plan 6 |
| `ChatStore` | `SampleChatStore` | `LiveChatStore` | plan 7 |
| `CallEngine` | `SimulatedCallEngine` | `LiveCallEngine` | plan 8 |

Additions other plans rely on:
- `ChatStore.recordCall(_:contactID:outgoing:at:)`: added to AppCore by plan 8, implemented by plan 7.
- `CallEngine` gains `answer(video:)`, `decline()`, `setNetwork`, `reset`, `onChange`; `CallPhase` gains `.ringing`, `.incoming`; `CallEndReason` gains `.declined`, `.missed`, `.answeredElsewhere`, `.failed` (plan 8).
- `CallSignalPayload`, `IceCandidate`, `CallSealError` live in AppCore (fields fixed by plan 8). `CallSignalSealer` lives in CallCore; the app adds `extension LiveChatStore: CallSignalSealer {}` (plan 7 implements `seal(_:to:)`/`open(_:from:)`). In MLS messages they travel as envelope kind `callsig`.
- `Escrow.seal(_:serverKey:)` lives in Messaging (plan 7).

The sample services stay. The launch argument `-sampleData` selects them, so the UI tests from plan 1 keep running without a server.

### Dependencies (owner approval)

| Library | License | Plan | Status |
|---|---|---|---|
| pgx v5, coder/websocket, go-webauthn | MIT, ISC, BSD-3 | 2 | asked in plan 2 |
| WebRTC (Google, M-branch) + libopus 1.6 | BSD-3 | 3 | named in GOAL.md; plan 3 pins the branch |
| mls-rs, mls-rs-crypto-rustcrypto (git pin; all targets, CryptoKit crate dropped 2026-10-28), uniffi | Apache-2.0 OR MIT / MPL-2.0 (uniffi) | 4 | mls-rs named in GOAL.md; plan 4 asks for the rest |
| RNNoise | BSD-3 | 5 (lab), 8 | named in GOAL.md |
| Lab-only scoring: numpy, onnxruntime, ViSQOL/speequal, DNSMOS models; LibriSpeech + MUSAN/DNS noise corpora | various, to check | 5 | asked in plan 5; never shipped |
| CI tools: markdownlint-cli2, lychee, gitleaks, go-licenses, gremlins | various | 9 | asked in plan 9 |
| SQLite | public domain, ships with Apple OSes | 7 | system library: no new dependency |

## Cross-plan changes outside the owning plan

- **WebRTC API others may use (plan 3):** `RTCOpusInfo` (incl. `maxPacketMs`, `opusPLCFieldTrial`), `RTCAudioCapturePostProcessing`, `RTCPeerConnectionFactory.hasCapturePostProcessingHook`. Plan 8 wraps these; where plan 8's text says `MediaFactory.hasPostProcessingHook`, it maps to plan 3's name.
- **Plan 8 turns on the Opus PLC field trial** (name in `tools/webrtc/VERSION`, `FIELD_TRIAL_OPUS_PLC`) with `RTCInitFieldTrialDictionary` before creating the factory. Without it Deep PLC is built in but never used.
- **Plan 4 lists every plain C symbol its bridge exports** in `tools/webrtc/reserved-symbols.txt`; plan 3's CI fails on a collision.

- Plan 5 adds `appserver lab session` and `APP_LAB_MODE` to the server's identity package after plan 2 merges (owner review: auth).
- Plan 8 needs plan 3's WebRTC build to add a capture post-processing hook for RNNoise and H.264 hardware factories.
- Symbol collision between plan 4 and WebRTC: no longer a risk. Plan 4 uses RustCrypto on all targets (owner decision 2026-10-28), so there is no CryptoKit bridge.
- Plan 9's test vectors (push padding, signatures, sealed handles, TURN credentials, escrow, link sealing, safety numbers) are checked by both the Go and Swift tests.
- **Plan 2 lane A (A5) adds `POST /v1/link/{linkID}/info`** (authed; `{secret}` → `{deviceName, platform, createdAt}`; `403 forbidden`, `410 expired`) for plan 6's link approval screen (OPEN-24). Owner review: auth.
- **Plan 4 adds `MLSEngine.memberKeys(of:)` and `MemberKey`** (contract above) for plan 7 A4. Owner review: cryptography.
- **Plan 7's content envelope gains kind `mode`** (`{"k":"mode","m":"e2ee"|"server_readable"}`), sent first by a conversation's creator and after every add; clients pin it and never add escrow to a chat pinned `e2ee` (OPEN-18). Plan 9's protocol spec documents it and the safety-number derivation (plan 7 Design 20).

## Owner decisions log

| Date | Decision |
|---|---|
| 2026-10-27 | Passkeys only for hosts in the app's associated domains; other servers sign in by email code. **Approved.** |
| 2026-10-27 | Plan 3 carries our own WebRTC patch for the capture post-processing hook (RNNoise). **Approved.** |
| 2026-10-27 | Keep server-readable mode in Phase 1 (escrow copy, off by default, admin export is audit-logged). **Approved.** |
| 2026-10-27 | Use mls-rs through our own thin Rust binding layer (`mlsffi`, plan 4) instead of `mls-rs-uniffi`; GOAL.md §3 and §5 updated. **Approved.** |
| 2026-10-27 | Threat-model fixes (OPEN-18, OPEN-7, OPEN-24, OPEN-6): (1) chat mode bound inside the encrypted chat by its creator, clients never switch E2EE → server-readable; (2) adding a device to a chat shows "X added a new device" to the other members and notifies own devices; (3) link approval shows device name/type, requires Face ID/Touch ID, warns plainly; (4) real safety numbers derived from MLS keys. **Approved.** Added to plans 6 and 7. |
| 2026-10-27 | Owner review of CODEOWNERS paths (identity, delivery, calls, push, pushgw, auth middleware, crypto) is deferred until the end of Phase 1; completed, reviewed work may merge to `main` before that. **Owner decision.** |
| 2026-10-28 | MLS crypto provider is `mls-rs-crypto-rustcrypto` on all targets (iPhone, Mac, Linux); `mls-rs-crypto-cryptokit` is dropped. Its Swift bridge needs macOS 26 (the Mac app supports macOS 14) and its build script fails on Xcode 26.1+; one provider also means one crypto path to review and no symbol clash with WebRTC. Supersedes plan 4 decision 2. **Owner decision.** |
| 2026-10-28 | No branch protection or code-owner enforcement for now (docs Task 13 deferred). Voice notes may be up to 30 minutes (minimum 0.5 s stays). The GitHub token in the local remote URL is a throwaway account's and stays. **Owner decisions.** |
| 2026-10-28 | Device measurements (call lab L10) are replaced for now by automated test calls on GitHub Mac runners: the server runs on the runner; the Mac app and an iOS Simulator call each other with recorded speech as the microphone, under emulated bad networks; data use, connect time and network-switch survival are measured. Battery, real cellular, Bluetooth and real microphones are left for a later, optional device run. **Owner decision.** |
| 2026-10-28 | Approved: call-lab scoring dependencies `numpy`, `onnxruntime`, `speequal` (plan 5 decision 4); noise set MUSAN "noise" subset if its license allows, else the DNS Challenge noise set (decision 5, controller call). Approved: docs tools `markdownlint-cli2`, `lychee` (docs Task 1) and `gitleaks`, `go-licenses` plus the Swift license check (docs Task 6). **Owner decision.** |
