# Project Goal: Self-Hosted, Low-Data Communication Platform

## 1. Vision

Build a privacy-focused, self-hostable communication platform with native applications, reliable messaging, and high-quality audio/video calling that works well on weak networks while minimizing mobile data usage, battery consumption, CPU usage, and memory footprint.

The product should offer the everyday communication capabilities people expect from modern messaging apps without being a WhatsApp clone. Its distinguishing strengths are:

- Low-data audio and video calls.
- Excellent voice clarity and Discord-like noise suppression.
- Efficient battery and resource usage on phones.
- End-to-end privacy and user control.
- A self-hostable server implemented primarily in Go.
- Native applications that feel at home on each supported operating system.

## 2. Product Principles

1. **Efficiency is a core requirement.** Measure data use, battery drain, CPU, memory, call setup time, and recovery from network interruptions throughout development.
2. **Voice quality comes first.** Preserve intelligible speech under poor network conditions and noisy environments.
3. **Privacy by design.** Prefer end-to-end encryption, data minimization, secure defaults, and transparent controls.
4. **Self-hosting must be practical.** Make installation, upgrades, backups, monitoring, and recovery straightforward.
5. **Native user experience.** Use platform-native UI and lifecycle APIs instead of making a web wrapper the primary client experience.
6. **Resilience over ideal-network assumptions.** Support intermittent connectivity, high latency, packet loss, and low bandwidth.
7. **Use established standards.** Prefer mature, reviewed protocols and libraries over custom cryptography or proprietary network designs.
8. **Ship incrementally.** Validate the hardest technical risks before expanding the feature set.
9. **Open source.** The server, clients, and push gateway are published under the Apache-2.0 license. Every dependency must have a license compatible with Apache-2.0 and App Store distribution.

## 3. Target Platforms

### Initial platforms
- **iPhone:** iOS 17 and later. Swift and SwiftUI, with native calling, audio-session, push-notification, and background execution integration.
- **macOS:** Swift and SwiftUI/AppKit, with native desktop conventions; minimum macOS version to be set alongside iOS 17. Native macOS has no CallKit and no VoIP push type, so incoming calls on the Mac ring through an alert push, the Notification Service Extension, and a local notification.

### Later platforms
- Android: Kotlin and Jetpack Compose.
- Windows: native Windows UI and platform APIs.
- Linux: native GTK4/libadwaita or another deliberately selected Linux-native toolkit.

### Excluded
- **Web browser client:** not planned. A web app downloads its code from the server on every load, so a compromised or dishonest server could serve code that steals keys, which conflicts with strict E2EE.

### Shared code
No shared cross-platform core for now. Each client is written natively (Swift for iPhone and macOS; Kotlin for Android later). Reviewed libraries, including those implemented in Rust or C/C++, are consumed through their official Swift/Kotlin bindings, or through a thin project-written binding layer when the official one is inadequate (as for `mls-rs`), rather than through a custom shared core. Such a layer only passes data to the library and contains no cryptography of its own; confirm each library's license before adopting it. Keep client behavior consistent through a written protocol specification and shared test vectors. Revisit this decision when Android development starts.

## 4. Core Product Scope

### Messaging
- One-to-one and group text conversations.
- Replies, edits, deletion, reactions, forwarding, and message status.
- Image, video, document, and voice-message sharing.
- Offline message queueing and synchronization after reconnect.
- Search across messages and media.
- Blocking, reporting, spam prevention, and group administration.
- Encrypted local storage and configurable message-retention policies.

### Audio and video calling
- One-to-one audio and video calls.
- Group calling after the one-to-one call experience is stable.
- Speakerphone, Bluetooth/headset routing, microphone and camera controls.
- Call history and missed-call notifications.
- Audio-only fallback when video cannot be sustained.
- Automatic reconnect and recovery from temporary network changes.
- Live diagnostics for latency, jitter, packet loss, codec, bitrate, and estimated data consumption.
- Screen sharing as a later desktop-focused feature.

### Low-data calling
Provide automatic and user-selectable data modes. Modes are defined by measured bytes on the wire, not codec bitrate alone: at 6–12 kbps, IP/UDP/RTP/SRTP headers are 60–80% of the bytes sent with default 20 ms packets, so packet length (ptime) matters more than codec bitrate in the low modes.

| Mode | Opus target | Audio packet length (ptime) | In-band FEC | Measured wire bandwidth (one direction, clean network)[^m] | Behavior |
|---|---:|---|---|---:|---|
| Ultra-low data | 8–10 kbps, wideband | 60 ms (120 ms optional; needs custom build) | Off (cannot activate below ~14 kbps) | 11.9 kbps | Prioritize intelligible speech and minimum bandwidth |
| Data saver | 14–16 kbps | 40–60 ms | On, only under loss | 18.3 kbps | Reduce usage while preserving acceptable quality |
| Balanced | 24 kbps | 20–40 ms | On | 27.3 kbps | General-purpose default |
| High quality | 32–48 kbps | 20 ms | On | 33.6 kbps | Prefer audio quality when bandwidth permits |

[^m]: CI, simulated: median of 3 calls per mode on a macOS CI runner over loopback, direct path, audio only; `kbps_per_direction` (half of the bytes sent and received). The test speech is about 50% talk with silence suppression (DTX) on, as in a normal back-and-forth conversation; continuous one-way speech uses more. See [docs/measurements/2026-10-28-data-use-ci.md](docs/measurements/2026-10-28-data-use-ci.md). Relayed path (one call each): Ultra-low 15.4 kbps, Balanced 28.4 kbps. The real-phone measurements of call lab L10 may replace these before release.

The measured figures are wire bytes over IPv4 from packet captures, including packet headers, encryption and congestion-control feedback, for a normal conversation with silence suppression (DTX) on. The earlier research estimates assumed continuous speech with no silence suppression and were higher (about 17–21, 25–30, 38–51 and 59–75 kbps). A 1:1 call both sends and receives, so per-device usage is roughly double the one-direction figure. Lossy networks can add more (bandwidth probing); see the call lab results.

#### Quality selection
A selected mode is a ceiling, not a guarantee. The effective quality of a call is the lowest of:
1. The server administrator's maximum mode.
2. The user's choice: the app-settings default, overridden by any in-call change for that call.
3. What the network can currently sustain, measured continuously.

- **Server default:** the administrator sets the default mode and a maximum mode for the server.
- **User default:** users can override the server default in app settings, up to the administrator's maximum.
- **In-call change:** users can change the mode at any time during a call; the change applies to that call only.
- **Auto:** the recommended default; uses the best quality the network allows within the limits above.
- **Network adaptation:** when the network cannot sustain the selected mode, step down automatically, show a clear indicator (for example, "Weak network, using Data saver"), and step back up when conditions recover.
- **Degradation order:** reduce video quality first, then drop video, and protect audio last.
- A user's mode limits what their device sends and is signaled to the other participants so they reduce what they send to that user.

Requirements:
- Use Opus with variable bitrate and DTX (silence suppression).
- Ship a custom libopus 1.6 build inside the app's WebRTC build with Deep PLC and OSCE (NoLACE, bandwidth extension) enabled and decoder complexity of at least 7. Stock libwebrtc enables none of these. Ultra-low relies on Deep PLC instead of in-band FEC.
- Defer DRED until it is standardized and supported by libwebrtc.
- Set each mode's ptime, DTX, and FEC in the SDP at call setup; changing them mid-call needs a quick renegotiation (no ICE restart). Use `maxBitrate` for instant in-call bitrate steps.
- libwebrtc's built-in adaptive ptime has a 16 kbps floor, so Auto needs its own adaptor or fixed per-mode ptime.
- Test disabling transport-wide congestion-control feedback in audio-only Ultra-low calls; it costs ~2.7 kbps or more in the reverse direction.
- Adapt to bandwidth, jitter, and packet loss.
- Prefer audio continuity over video quality during congestion.
- Provide Wi-Fi/mobile-data preferences and per-call data estimates.
- Test with throttled bandwidth, high latency, jitter, and packet loss.
- The project owns its WebRTC build from the first patch onward and must keep it updated with upstream security fixes.

#### Video
- H.264 with Apple's hardware encoder (VideoToolbox) by default; no Apple chip encodes AV1 in hardware as of October 2026.
- Starting ladder: ~180p at 15 fps for 100 kbps, ~270p at 15 fps for 150–200 kbps, ~360p at 15–20 fps for 300 kbps.
- Video starts off in Ultra-low and Data saver; the user can turn it on.
- Reduce resolution, frame rate, and bitrate dynamically, and switch to audio-only automatically below ~60 kbps available.

### Noise suppression and voice processing
- Echo cancellation.
- Noise suppression for fans, traffic, keyboards, wind, and background noise.
- Automatic microphone gain control.
- Voice activity detection.
- Configurable processing: Off, Standard, and Enhanced/AI, if the device can handle it efficiently.
- Audio routing that behaves correctly with Bluetooth devices and wired headsets.

- **Standard:** Apple's voice processing (VPIO) for echo cancellation, noise suppression, and gain control; on iOS, libwebrtc already hands noise suppression to it. The app cannot turn on Apple's Voice Isolation mic mode itself; detect when it is off and show a hint that opens the system mic-mode picker.
- **Enhanced:** an on-device open-source model inserted after echo cancellation through WebRTC's capture post-processing hook. Start with RNNoise (BSD-3); consider GTCRN (license to be confirmed) or DeepFilterNet 3 (MIT/Apache-2.0) only after iPhone benchmarks show a meaningful gain at acceptable CPU, latency, memory, and battery cost.
- Run only one noise suppressor at a time: switch the Enhanced model off while Voice Isolation is active.
- Do not use Krisp or other closed, per-use SDKs.
- All processing runs on-device, before encryption.

## 5. Privacy, Encryption Modes, and Security

The product must support clearly differentiated encryption policies. Encryption mode is a security property of a conversation and its participating devices, not merely a server-wide toggle.

### Mode A — Strict end-to-end encryption (E2EE)
- Only authorized recipient devices hold the keys needed to decrypt message content.
- The server must not possess a decryption key, hidden master key, escrow key, or administrator override.
- No server-side content decryption or administrator recovery path is permitted in this mode.
- The server must not silently downgrade a strict E2EE conversation.
- If a device, participant, or server cannot satisfy the policy, fail closed or require users to explicitly create/configure a different conversation.
- Clearly explain that metadata may still be visible even when content is end-to-end encrypted.

### Mode B — Server-readable / server-managed encryption
- Content may be encrypted in transit and at rest, but the server or an authorized server-side service can decrypt it.
- This mode may support organizational retention, moderation, e-discovery, or lawful access where applicable.
- Label this mode as **not end-to-end encrypted** on the conversation's info page (see the UI design: no encryption labels elsewhere in the app).
- Access must be governed by documented policies, least privilege, audit logs, retention rules, and applicable law.
- Do not market server-readable encryption as equivalent to strict E2EE.

### Mode C — Mixed deployment
- A server may allow different encryption policies for different conversations or account types.
- A strict E2EE conversation remains strict even if another conversation on the same server is server-readable.
- The client shows each conversation's encryption type on its info page and provides a way to verify participants/devices there. Following Telegram's approach, the chat list, chat screen, and call screen carry no encryption labels.
- A mixed-mode server must not silently change an existing conversation's security mode. Policy changes require an explicit, authenticated transition with clear user notification; strict E2EE conversations must never be downgraded by server policy.
- Define behavior for group membership changes, unsupported clients, linked devices, message search, backups, attachments, notifications, and call media under each mode.

### Encryption protocol
- **Messaging:** MLS (RFC 9420) for every conversation, using the `mls-rs` library (Apache-2.0 OR MIT) through a thin project-written Rust binding layer (`mlsffi`), because the official `mls-rs-uniffi` bindings require OpenSSL, keep KeyPackage keys only in memory, and lack epoch and pending-commit operations. The owner reviews this layer. No complete Swift-native MLS library exists as of October 2026. A 1:1 chat is a two-member group, and each device is its own MLS member, which supports device linking.
- **Server role:** the Go server is an MLS Delivery Service. It stores KeyPackages and accepts commits in strict order (first commit wins per epoch). It needs no cryptography library.
- **Not libsignal:** it is AGPL-3.0 and its maintainers state that use outside Signal is unsupported.
- **1:1 call media:** WebRTC DTLS-SRTP is end-to-end for direct and TURN-relayed calls, provided each side's DTLS fingerprint is exchanged inside an MLS-encrypted message so no server can substitute it.
- **Group call media (Phase 2):** SFrame (RFC 9605) with per-sender keys derived from the MLS group, following the Discord DAVE design. Server-generated media keys are allowed only in server-readable mode.
- **Post-quantum:** standard MLS cipher suites are not yet post-quantum; keep the ability to change cipher suites (MLS ReInit).
- **Verification:** build contact and device verification (safety numbers or similar codes).
- No public audit of `mls-rs` was found; re-check before release.

### General security requirements
- End-to-end encryption for private messages and calls should be the default product goal.
- Define a device identity and key-management model before implementing multi-device support.
- Support secure device linking, device revocation, key rotation, group membership changes, and verification of contacts/devices.
- Encrypt local databases and sensitive local files.
- Use secure platform key stores.
- Protect authentication and session tokens.
- Apply rate limits, abuse prevention, secure authorization checks, and safe file-upload handling.
- Minimize server-side metadata and document which metadata remains visible to server operators.
- Encrypted backups use a random recovery key generated on the device and never sent to the server (Signal's 2025 model). No PIN-based recovery, because that requires tamper-resistant server hardware a self-hosted project cannot run.
- The Notification Service Extension decrypts while the phone is locked, so the message database needs the "complete until first unlock" data protection class; media files can use the stricter "complete" class.
- Establish a threat model and obtain independent security review before claiming the system is secure. Apply to the Open Technology Fund Red Team Lab for a free audit as an open-source project; otherwise budget roughly $40k–80k.

End-to-end encryption does not hide all metadata. Document the limits clearly, including what self-host administrators, network operators, and push-notification services may observe.

## 6. Self-Hosted and Multi-Server Architecture

The primary control-plane and messaging server implementation should be in Go. The platform must not depend on one mandatory global service: users can choose the project's default servers or configure an independently operated server.

**Deliberate exception — push gateway:** Apple only accepts pushes signed with the app publisher's key, so every self-hosted server must send iOS/macOS pushes through the project-run push gateway (see *Push notification gateway*). Escape routes: the gateway is open source, organizations can build their own branded app with their own Apple key, and Android can use UnifiedPush later.

### Client server selection
- Let users enter a server URL or join through a QR code or invitation link.
- Offer project-operated default servers for users who want a managed experience. During the beta these are invite-only; public sign-up waits until a legal entity, terms, and privacy policies are in place (see Section 17).
- Make the default server directory optional; custom servers must remain usable without it.
- Define a versioned server discovery/metadata document describing protocol compatibility, supported features, encryption policies, federation policy, and media relay endpoints.
- Verify server identity and use authenticated, encrypted connections.
- Explain that switching home servers does not automatically migrate identity, contacts, or message history.
- Support federation between independently operated servers as an explicit capability, controlled by each server's administrator.
- Do not imply that isolated servers can communicate with each other unless federation or another explicit bridging mechanism is enabled.

### Multi-server and multi-region calling
Design the media plane to support multiple geographically distributed relay/SFU nodes. The system should select routes using measured latency, packet loss, jitter, reachability, and relay capacity, not geography alone.

- Prefer a direct WebRTC peer-to-peer route when it is viable, efficient, and permitted by the users' privacy settings.
- Use TURN relays when direct connectivity is blocked or a relay offers a better supported route.
- Use an SFU for group calls and selective forwarding of media tracks.
- Keep call coordination and media forwarding as separate responsibilities.
- Allow the control plane to discover relay health, capacity, region, and supported capabilities.
- Recover from relay failures when an alternate viable route exists.
- Avoid forcing media through multiple hops: extra hops can increase latency and cost.
- Consider server-to-server media forwarding only when the topology requires it and measured results justify it.
- Document whether relay operators can observe connection metadata. WebRTC media is encrypted in transit, but a normal SFU can access media packets and metadata; end-to-end media encryption requires an explicit compatible design.

### Call relay privacy
A direct peer-to-peer call reveals each participant's IP address, and therefore approximate location, to the other participant. Relaying through TURN hides it; in one-to-one calls the relay forwards encrypted media and cannot decrypt it.

- **User setting**, with three values: *Direct when possible*, *Always relay calls from unknown contacts*, and *Always relay all calls*.
- **Server default:** the administrator sets the default value for new users; users can change it in app settings. Recommended default: *Always relay calls from unknown contacts* (Signal's model).
- The call-setup server is still required for signaling even when media flows directly between devices.
- About 15–25% of direct calls need a relay even without this setting; with it, plan relay capacity as if most calls are relayed. A relayed audio call uses ~22–36 MB of relay traffic per hour.

### Push notification gateway
One open-source Go push gateway, run by the project, holds the only Apple push key.
- Each self-hosted server registers with the gateway, has its own key pair, signs every request, and is rate-limited per server and per device.
- The device seals its push token to the gateway, so self-hosted servers never see raw tokens (Zulip's 2026 model). The gateway sees only an opaque handle, ciphertext, and priority.
- Messages arrive as alert pushes with `mutable-content` and generic text; the Notification Service Extension (NSE) decrypts the padded MLS payload and rewrites the notification. Small messages travel inside the push (4 KB limit) to avoid a second fetch.
- Request Apple's notification-filtering entitlement so the NSE can drop duplicate or dummy pushes silently.
- Do not rely on background (silent) pushes; Apple throttles them to a few per hour.
- iPhone calls use PushKit VoIP pushes only for real rings, with `apns-expiration: 0`, and every VoIP push is reported to CallKit immediately. Hang-ups go over the live connection.
- Use Mattermost's Go push proxy (Apache-2.0) as reference code. Do not copy the Matrix push gateway API, which is unauthenticated.
- Android later: FCM data messages through the same gateway, plus UnifiedPush for direct server push.
- Before finalizing the call-push design, check iOS 26.4's `mustReport` flag and whether China still requires CallKit to be disabled.

### Required capabilities
- Authentication and account/device management.
- Message submission, storage, delivery, acknowledgements, and synchronization.
- Group and membership management.
- Call signaling and ICE candidate exchange.
- Push-notification integration.
- Media attachment metadata and object-storage integration.
- Abuse controls, rate limits, server configuration, and audit-friendly operational logs.
- Health checks, metrics, structured logs, and diagnostics.
- Database migrations, backups, restore procedures, and documented upgrades.

### Suggested infrastructure
- **Go:** API, messaging, signaling, administration, coordination, and background workers, built as a modular monolith: one deployable server binary with clear internal module boundaries, not separate microservices.
- **WebSocket:** client signaling, live events, presence where enabled, and call setup messages.
- **gRPC + Protocol Buffers:** only across real network boundaries, such as the control plane communicating with separately deployed relay/SFU nodes. Do not split the core server into gRPC services until measured scale or operational needs justify it.
- **WebRTC:** real-time audio/video media, ICE connectivity checks, congestion control, and secure media transport.
- **PostgreSQL:** durable relational state and message metadata, with schema design informed by retention and scale requirements.
- **Object storage:** S3-compatible storage for attachments, with encryption and retention controls.
- **STUN/TURN:** coturn 4.18 or later, with short-lived credentials issued by the Go server, listening on UDP 3478, TCP 3478, and TLS 443, dual-stack (IPv4 and IPv6), and blocking relay to private, loopback, and cloud-metadata addresses. Cloudflare TURN may be offered as an optional administrator-enabled fallback, disclosed in the privacy policy because it sees user IP addresses.
- **Phase 1 calls:** no media server. 1:1 calls use the native WebRTC library with the project's own Go WebSocket signaling and coturn; reconnection (continual gathering, ICE restart after 2–3 s disconnected or on network change, give up after ~30 s, "Reconnecting…" UI) is built in-house.
- **SFU (Phase 2, group calls):** preferred is a Pion-based (Go, MIT) SFU built into the project; fallback is LiveKit (Go, Apache-2.0) if group video proves too costly to build. Decide after Phase 1.
- **Redis/Valkey or NATS:** optional components only where caching, ephemeral state, event fan-out, or coordination justify the additional operational dependency.

Do not use gRPC or WebSocket as the ordinary transport for the actual audio/video stream. Use WebRTC for media. Treat messaging and real-time media as distinct workloads, and do not route media through the ordinary messaging API. Select the SFU and deployment model after prototyping expected group-call sizes and operational requirements.

### Deployment goals
- Provide a simple Docker Compose deployment.
- Aim for a single-node setup with clearly documented dependencies.
- Consider a single-binary experience where practical, without pretending databases, object storage, and media relays are always unnecessary.
- Include environment/configuration examples, health checks, persistent-volume guidance, backup/restore documentation, upgrades, and rollback instructions.
- Support reverse proxies and TLS termination.
- Provide an administrator guide for security updates, monitoring, and troubleshooting.
- Design for horizontal scaling later, but avoid premature distributed-system complexity.

## 7. Federation, Identity, and Server Policy Decisions

### Decided
- **Username:** the primary account identity; discoverable by other users.
- **Email:** required for account recovery only; discoverable only if the user enables "Find me by email" (off by default). Email recovery restores the account, not message history; only the backup recovery key restores history, and users must be told this plainly.
- **Phone number:** optional; discoverable only if the user enables "Find me by phone" (off by default). Phone discovery is postponed until after the beta, because without SMS verification anyone could claim another person's number.
- Phone and email lookup must be protected against enumeration with strict rate limits and abuse detection. Hashing alone does not protect phone numbers, because the number space is small enough to search exhaustively.
- **Call relay privacy:** see *Call relay privacy* in Section 6.
- **Federation:** not in v1. Each server is isolated, and users can only communicate with users on the same server. Keep the protocol, identifiers, and server discovery design federation-ready so it can be added in a future phase.

- **Sign-in:** passkeys (Face ID / Touch ID), with an email code as the backup; no passwords.
- **Encryption mode choice:** the server administrator decides which conversations are server-readable through server policy; users do not choose a mode when starting a chat.

### Still to decide before production protocol design
- How users verify contacts and devices.
- How identity and message history migrate between servers.
- How cross-server spam, abuse, blocking, and trust work.
- How server encryption policies are advertised to clients.
- How clients authenticate server metadata and detect policy changes.
- How call coordination selects relays across regions and how relay credentials are issued and expire.
- Whether users can restrict relayed calls to approved relays or a chosen server's relay infrastructure.

Federation is a potential differentiator but significantly increases protocol, moderation, security, and operational complexity. Keep the protocol and server discovery design federation-ready, but do not let federation block validation of the core messaging and calling experience unless cross-server communication is required for the first release.

## 8. Battery and Resource Efficiency

The applications must avoid wasteful background polling and unnecessary work.

- Use APNs through the project push gateway and appropriate iOS calling/notification APIs; use FCM and UnifiedPush on Android when that platform is added.
- Respect operating-system background execution limits.
- Batch synchronization and avoid repeated wakeups.
- Use local encrypted storage and incremental synchronization.
- Load media lazily and generate thumbnails efficiently.
- Prefer hardware codecs.
- Adapt video and optional AI processing to thermal state and low-power mode.
- Suspend nonessential work in the background.
- Avoid permanent sockets or aggressive polling when platform policies make them unreliable or wasteful.
- Profile memory, CPU, energy, and network usage on physical devices.

## 9. Proposed Initial Performance Targets

These are provisional targets for testing and iteration, not guarantees.

- Warm launch: target at or below 2 seconds on selected reference devices.
- Call setup: target at or below 3 seconds on a healthy network after the callee answers.
- Idle/background battery drain: establish a repeatable test protocol and aim for no more than 2% per hour under a defined test setup.
- Idle memory: aim for no more than 100 MB on selected modern mobile reference devices, subject to OS and call-state differences.
- Audio continuity: prioritize intelligible speech over video under congestion.
- Data use: publish measured total bytes per minute for each call mode and network condition.
- Reliability: define measurable call setup success, reconnect success, crash-free sessions, and message-delivery objectives before beta.

All performance claims must be validated with repeatable tests, multiple devices, and documented network conditions. Do not advertise a target as achieved until it is measured.

## 10. Testing and Quality

Create a repeatable test matrix covering:
- Weak and fluctuating mobile networks.
- Bandwidth limits, packet loss, jitter, and high latency.
- Wi-Fi to cellular transitions.
- App backgrounding, suspension, and termination.
- Incoming calls while the device is locked or another call is active.
- Bluetooth and wired audio devices.
- Low-power mode and thermal pressure.
- Long calls and long-running messaging sessions.
- Multi-device synchronization and duplicate delivery.
- Notification Service Extension decryption: shared MLS state with the main app, ~24 MB memory limit, ~30 s time limit.
- Server restart, database outage, relay failure, and recovery.
- Security, authentication, authorization, abuse, and attachment-upload tests.
- Accessibility, localization, right-to-left layouts, and Unicode search.

Measure actual energy consumption and data transfer on physical devices; simulator-only testing is insufficient for those goals.

## 11. Delivery Roadmap

### Phase 0 — Technical validation
- Build the custom WebRTC framework with libopus 1.6 (Deep PLC, OSCE, decoder complexity 7) and validate Opus call quality at low bitrates and 60 ms ptime.
- Prototype WebRTC signaling and connectivity with Go WebSocket signaling and coturn.
- Benchmark Apple voice processing and RNNoise (then GTCRN/DeepFilterNet) on the oldest supported iOS 17 iPhone.
- Measure actual network bytes per mode from packet captures, and compare WhatsApp, Signal, and Telegram on the same emulated link.
- Measure battery usage on physical devices.
- Test peer-to-peer and TURN-relayed calls through a single relay.
- Prototype the NSE with MLS decryption and MLS state shared with the main app.
- Prototype the push gateway with sealed device handles and signed server requests.
- Document encryption modes, identity, server discovery, and federation decisions.
- In parallel: build the iPhone and Mac UI on sample data, following [the UI design](docs/superpowers/specs/2026-10-27-app-ui-design.md), so it can be connected to the real app in Phase 1.

**Exit criteria:** A repeatable one-to-one audio-call prototype with measured data use, voice quality, connection reliability, CPU, and energy consumption, plus a working NSE + MLS push prototype.

### Phase 1 — Initial MVP
- Native iPhone (iOS 17+) and macOS clients.
- Go server and documented self-hosted deployment.
- Project push gateway.
- Account/device identity and secure authentication.
- Multi-device linking and revocation (one account on iPhone and Mac).
- Strict E2EE one-to-one messaging over MLS with no server decryption path.
- Server-readable mode, with per-conversation encryption policy, the encryption type shown on each chat's info page, and no silent downgrade of strict E2EE conversations.
- Encrypted attachment transfer.
- One-to-one audio and video calls, with no media server.
- User-configurable home server; invite-only project-operated beta server.
- Relay discovery and basic health-aware selection across at least two TURN relay nodes.
- Low-data modes, noise suppression, and call diagnostics.
- Offline synchronization, push notifications, and call history.
- Basic blocking, reporting, and abuse controls, and in-app account deletion.

**Exit criteria:** A usable invite-only private beta with documented installation, reliable messaging, and tested low-data calls.

### Phase 2 — Reliability and groups
- Group messaging and administration.
- Group audio/video calls with an SFU (Pion-based preferred, LiveKit fallback).
- Multi-region relay selection, failover, and operational telemetry.
- Encrypted backup and restore.
- Phone-number discovery with SMS verification, fraud limits, and spending caps.
- Better network handover and reconnect behavior.
- Monitoring, metrics, migration tools, and production hardening.

### Phase 3 — Platform expansion
- Android native app.
- Windows native app.
- Linux native app.
- Localization and accessibility improvements.
- Screen sharing and other desktop-oriented features.

### Phase 4 — Ecosystem
- Federation if selected.
- Server migration and discovery improvements.
- Expanded multi-region media routing and cross-server call coordination where supported.
- Administrative and enterprise features.
- Extensible integrations and documented APIs.

## 12. Out of Scope Until the Core Is Proven

Avoid making these blockers for the initial release:
- A full public social network or community platform.
- Payments, stories, status feeds, or algorithmic content feeds.
- Complex bots and plugin ecosystems.
- Federation before the identity and trust model is settled.
- Heavy AI features without measured user benefit and acceptable energy cost.
- A custom cryptographic protocol.
- Supporting every platform before the core call and messaging experience is reliable.
- A web browser client (see Section 3, *Excluded*).

## 13. Definition of Done

The project is ready for a wider public beta when:
- The iPhone and macOS apps can securely exchange messages and attachments.
- Users can select a project-operated default server or configure an independent server.
- Server discovery and identity verification are documented and tested.
- One-to-one audio/video calls work across realistic network conditions.
- Relay selection has been tested with multiple nodes, and the system avoids unnecessary media hops.
- Low-data modes have published, repeatable measurements.
- Noise suppression and echo cancellation have been evaluated against representative noise samples.
- Battery and resource usage have been measured on physical devices.
- Strict E2EE and server-readable modes are distinguished on each chat's info page and in the documentation.
- Strict E2EE has no server-side decryption or administrator override path, and downgrade behavior has been tested.
- Encryption and key lifecycle behavior have undergone security review.
- A third party can deploy, upgrade, back up, and restore the server using the documentation.
- Failure modes, privacy limitations, and administrator-visible metadata are documented.
- Accessibility, localization, crash reporting, and abuse controls are in place.
- The App Store and legal items in Section 17 are complete.
- No major security or reliability issue remains unresolved.

## 14. Guiding Product Statement

Build a native, private, self-hostable communication platform that makes clear voice calls and reliable messaging possible on weak networks, while using as little mobile data, battery, and device resources as practical.

The product should earn adoption through measurable efficiency, excellent voice quality, trustworthy privacy, and operational control—not by copying every feature of an existing messenger.

## 15. Protocol and Architecture Decision Summary

The initial preferred direction is:

- **License:** Apache-2.0 for all project code.
- **Native clients:** Swift/SwiftUI for iPhone (iOS 17+) and macOS first; Kotlin/Jetpack Compose for Android later; native Windows and Linux clients in subsequent phases. No web client.
- **Shared client core:** none for now; native clients consume reviewed libraries through their platform bindings, kept consistent by a protocol specification and shared test vectors. Revisit when Android starts.
- **Identity:** username as primary identity; email for recovery only; optional phone number; discovery by email or phone is opt-in.
- **Call quality:** server default and maximum set by the administrator, user default in settings, in-call changes on demand, and automatic network adaptation beneath the selected ceiling.
- **Call relay privacy:** user-selectable direct, relay-unknown-contacts, or always-relay, with an administrator-set server default.
- **Backend:** Go modular monolith (a single server binary) for the API, messaging, signaling, call coordination, administration, and workers.
- **Client signaling:** WebSocket.
- **Node-to-node communication:** gRPC with Protocol Buffers between the control plane and separately deployed relay/SFU nodes; no internal microservice split until justified by measurement.
- **Media:** native WebRTC with a custom libopus 1.6 build; direct peer-to-peer connections when appropriate and coturn for relay. No media server in Phase 1; an SFU for group calls in Phase 2 (Pion-based preferred, LiveKit fallback).
- **Noise suppression:** Apple voice processing as Standard; RNNoise-first open-source model as Enhanced; no Krisp.
- **Push:** one open-source, project-run Go push gateway with sealed device handles, signed server requests, and encrypted padded payloads.
- **Multi-server media:** multiple regional relay/SFU nodes, health and capacity telemetry, measured route selection, and failover where a viable alternate route exists.
- **Storage:** PostgreSQL plus optional S3-compatible object storage.
- **Optional infrastructure:** NATS and Redis/Valkey only where actual scale and workload require them.
- **Encryption:** MLS via `mls-rs` for messaging; the Go server is an MLS Delivery Service. Strict E2EE without server override, plus a clearly labeled server-readable mode only for conversations explicitly configured for it. Mixed policies must never silently downgrade strict E2EE.
- **Server choice:** project-operated defaults are convenient but optional; users can configure an independent home server. Federation is an explicit server capability, not an assumed property of every installation.

These are starting decisions to validate with prototypes, performance tests, threat modeling, library licensing checks, and security review—not a substitute for those activities.

## 16. AI-Assisted Development Rules

The code is written mostly by AI coding agents (Claude and Codex). Studies in 2025–2026 found AI-generated code fails security tests in roughly 44% of cases, so:

- **Human review:** the project owner reviews and approves all code touching cryptography, authentication, keys, protocol state machines, the push gateway, and the WebRTC/Opus build (enforced through CODEOWNERS).
- **Specs first:** agents work from written specs (threat model, protocol spec, data-mode table). `CLAUDE.md` and `AGENTS.md` point to the same spec files so both agents follow one design.
- **Thin wrappers:** agents call vetted libraries only through thin wrappers that a human reviews.
- **CI gates:** Go fuzzing on every parser, property tests for state machines, RFC test vectors, mutation testing, `govulncheck`, license scanning, and secret scanning.
- **Dependencies:** no new dependency without human approval; agents can invent package names.
- **Secrets:** agents never hold the APNs key, signing keys, or production credentials.
- **Accessibility and localization:** specs must require VoiceOver labels, Dynamic Type, and right-to-left and Indic script support, because agents rarely add them unprompted.
- **Device testing:** CallKit, VoIP pushes, Bluetooth routing, network handover, and battery must be tested on physical iPhones (at least one old and one new) and a Mac.

## 17. App Store and Legal Compliance

- **User-generated content (Guideline 1.2):** on-device blocking, reporting by forwarding reported messages with user consent, message requests from strangers, and reports routed to the server operator's abuse contact.
- **Account deletion (Guideline 5.1.1(v)):** in-app account deletion through a server API that self-hosters cannot disable.
- **Legal entity:** enroll in the Apple Developer Program as an organization before App Store submission (Guideline 5.1.1(ix)).
- **Export compliance:** prepare an encryption export memo for App Store Connect, US BIS requirements, and France; confirm with a lawyer whether the BIS annual self-classification report applies.
- **Security contact:** publish `security.txt` and a vulnerability disclosure policy.
- **Operating servers:** running project servers for the public makes the project a GDPR controller and brings EU DSA, UK Online Safety Act, and India IT Rules duties. Keep the beta invite-only and add public sign-up only after legal review. Store as little metadata as possible.
- **Costs:** the project owner funds servers and the Apple Developer Program. AI agent subscriptions are the largest recurring cost.
