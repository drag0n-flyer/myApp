# Android Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan runs as seven parallel lanes and one integration lane. Read "Parallel execution" first, then work only your lane's tasks.** All coding agents run on Sonnet 5.5; final whole-branch reviews use Opus.

**Goal:** A native Android app (Android 10+, Kotlin, Jetpack Compose, Material 3) with the same Phase 1 features as the iPhone app: 1:1 chats over MLS, attachments, 1:1 audio and video calls with the low-data modes, device linking, push by FCM and UnifiedPush, server-readable mode, blocking and reporting. It talks to the same Go server and interoperates with the iPhone and Mac app.

**Architecture:** Gradle modules under `apps/android/`. Pure Kotlin/JVM modules hold the logic and test on the JVM (`:contracts`, `:core`, `:net`, `:messaging`, `:calls`); Android library modules hold platform adapters (`:auth`, `:mls`, `:store`, `:calls-android`, `:push`, `:webrtc`); `:app` is the Compose UI. MLS is the existing Rust `mlsffi` crate through UniFFI Kotlin bindings, cross-compiled with `cargo-ndk`. WebRTC is the project's own build (Opus 1.6, Deep PLC, OSCE) made on Linux as an AAR. The UI is built first on sample data, then the live services replace the sample ones, as on iPhone.

**Tech Stack:** Kotlin 2.x, Jetpack Compose + Material 3, Coroutines/Flow, kotlinx.serialization, OkHttp (HTTP + WebSocket), Room on SQLCipher, Android Keystore, Credential Manager, WorkManager, Firebase Cloud Messaging, UnifiedPush connector, WebRTC (own build), Telecom `ConnectionService`, CameraX + ZXing, JNA (UniFFI), Rust + cargo-ndk, JUnit 4, Robolectric, Gradle Managed Devices, GitHub Actions on `ubuntu-24.04`. Every new library is listed under "Dependencies needing owner approval".

**Spec:**
- [Android client design](../specs/2026-10-28-android-client-design.md): the decisions this plan builds
- [Protocol spec](../../protocol/README.md) and its test vectors: the wire contract
- [Server design](../specs/2026-10-27-server-design.md) §7: endpoints, frames, push, TURN
- [UI design](../specs/2026-10-27-app-ui-design.md): screens and behaviour
- [Roadmap](README.md): cross-plan names; plans 4, 6, 7, 8 and 3 as the Apple counterparts of lanes C, B, D, E

## Global Constraints

- **Paths:** Android code lives in `apps/android/`. Lanes that change the server or shared tools touch only the files their task names.
- **Build settings** (set in A1, never changed by a lane): `minSdk = 29`, `compileSdk = 36`, `targetSdk = 36`, JDK 17 toolchain, Kotlin package root `org.example.app`, `applicationId = "org.example.app"` (placeholder; owner to decide the final one). Version catalog `apps/android/gradle/libs.versions.toml` is the only place versions live.
- **Dependencies:** only those in "Dependencies needing owner approval", and only after the owner approves the list. A1 pins each to its newest stable version on the day and records the versions in `libs.versions.toml`; nothing else is added without approval (AGENTS.md).
- **Local commands** (Linux; `scripts/android-setup.sh` from G1 installs the JDK, SDK and NDK under `~/android`):
  - JVM tests: `cd apps/android && ./gradlew :<module>:test`
  - Robolectric/Compose tests: `./gradlew :app:testDebugUnitTest`
  - Instrumented tests (needs `/dev/kvm`): `./gradlew :<module>:pixel6Api29DebugAndroidTest`
  - Rust: `cd apps/apple/Packages/SecureMessaging/mlsffi && cargo test --locked`
  - Server: `cd server && TEST_DATABASE_URL='postgres:///postgres?host=/var/run/postgresql' go test ./...`
- **Names and values** come from the protocol spec, the server design and the design doc, word for word. Don't rename the `:contracts` types; a change there needs a `Ruling:` line in the task's commit message and a note in this plan.
- **Wire rules:** binary JSON fields are base64url without padding (decoders accept padding); the push request, `d` and the TURN credential use standard base64 with padding; times from the server are RFC 3339 with up to 9 fractional digits.
- **UI:** Material 3 Compose components only; Material Symbols; Material type scale; TalkBack `contentDescription` on every control; `start`/`end` layout; every string in `res/values/strings.xml`. No encryption labels except chat info. Never show "codec", "kbps", "relay", "MLS" or "E2EE". The app name is `[App]` (`R.string.app_name`, `AppBrand.NAME`).
- **Never log** tokens, codes, link secrets, emails, push tokens, keys, plaintext or ciphertext. Log IDs and lengths only.
- **Owner review before merge** (CODEOWNERS entries in G1; reviews are currently deferred per the roadmap log, agent reviews continue): `apps/android/auth/`, `apps/android/mls/`, `apps/android/messaging/src/main/kotlin/org/example/app/messaging/{crypto,sync}/`, `apps/android/calls-android/src/main/kotlin/org/example/app/calls/android/telecom/`, `apps/android/push/`, `tools/webrtc/` Android files, every server and gateway change in this plan.
- **Secrets:** agents never create, hold or commit an upload key, keystore, keystore password, `google-services.json`, or FCM service-account file. Release signing happens on the owner's machine.
- **CI:** Android jobs run on `ubuntu-24.04` only. The one macOS job (I2 interop) runs on `main` and `workflow_dispatch` only. Push once per batch; cancel superseded runs.
- **Commits:** plain messages, no `Co-Authored-By` or AI trailers.
- **Branches:** each lane works in its own worktree: `git worktree add ../myApp-android-<lane> -b android/lane-<lane> main`. Lanes merge into `feat/android` in the order given under "Parallel execution".

## Review Focus

1. **Wire compatibility with iPhone and Go.** base64url both ways; HPKE `enc ‖ ct` with empty AAD; strict unpadding; the content envelope and `CallSignalPayload` JSON byte-compatible with the Swift fixtures. Pinned by C4 `sealsAndOpensSharedVectors`, D2 `decodesEverySwiftContentFixture`, D2 `encodesCallsigLikeSwift`, B2 `base64UrlRoundTrip`, F4 `unpadsEverySharedVector`.
2. **Keys never leave in clear.** The DB key, session and MLS signing key exist on disk only wrapped by the Keystore key; nothing is in an auto-backup. Pinned by B5 `wrappedSecretIsNotPlaintext`, `swappedSecretFailsToOpen`, A1 `backupIsDisabled`.
3. **Push never shows what it should not.** A payload that fails to decrypt, a muted or blocked chat, a receipt, an edit or a delete never shows content; a stranger's call never rings. Pinned by D7 `notificationOutcomes`, F4 `strangerCallDoesNotRing`.
4. **Calls drop a forged fingerprint.** An offer or answer whose `a=fingerprint` differs from `dtlsFingerprint`, or a signal from a device other than the answering one, never reaches `setRemoteDescription`. Pinned by E4 `fingerprintMismatchAbortsCall`, `signalFromOtherDeviceDropped`.
5. **Server and gateway changes are safe.** The UnifiedPush sender never connects to private, loopback, link-local or metadata addresses and follows no redirects; the FCM sender never sends a payload over 4096 bytes; platform `android` is the only new platform value. Pinned by F2 `refusesPrivateEndpoints`, F1 `fcmOversizeBecomesEmpty`, B1 `acceptsAndroidPlatform`.
6. **The reference phone.** Cold start, a 10 000-message chat list and an audio call in Ultra-low stay smooth on the OnePlus 5. Pinned by the I2 device checklist; owner-run.

---

## Dependencies needing owner approval

None of these may be added before the owner approves this list. Versions are pinned in A1. "Build tool" entries are never shipped in the app.

| Library or tool | Coordinates | License | Used by | Why |
|---|---|---|---|---|
| Android Gradle Plugin, Gradle wrapper | `com.android.application`, `com.android.library` | Apache-2.0 | all | Build (build tool) |
| Kotlin, Compose compiler plugin, kotlinx.serialization plugin | `org.jetbrains.kotlin.*` | Apache-2.0 | all | Language and plugins |
| KSP | `com.google.devtools.ksp` | Apache-2.0 | `:store` | Room code generation (build tool) |
| Kotlin coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-core`, `-android`, `-test` | Apache-2.0 | all | Concurrency |
| kotlinx.serialization JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` | Apache-2.0 | all | JSON |
| AndroidX core, activity-compose, lifecycle (viewmodel, runtime-compose) | `androidx.core:core-ktx`, `androidx.activity:activity-compose`, `androidx.lifecycle:*` | Apache-2.0 | `:app` | Android basics |
| Compose BOM, UI, Material 3, ui-tooling, ui-test-junit4 | `androidx.compose:compose-bom`, `androidx.compose.material3:material3`, `androidx.compose.ui:*` | Apache-2.0 | `:app` | UI |
| Navigation Compose | `androidx.navigation:navigation-compose` | Apache-2.0 | `:app` | Screens |
| Room (runtime, ktx, compiler) | `androidx.room:*` | Apache-2.0 | `:store` | Database |
| SQLCipher for Android | `net.zetetic:sqlcipher-android` | BSD-3-Clause style (SQLCipher Community) | `:store` | Encrypted database |
| WorkManager | `androidx.work:work-runtime-ktx` | Apache-2.0 | `:messaging` wiring, `:push` | Deferred and expedited work |
| Credential Manager | `androidx.credentials:credentials`, `androidx.credentials:credentials-play-services-auth` | Apache-2.0 (the Play services part is Google's proprietary SDK) | `:auth` | Passkeys |
| OkHttp, MockWebServer | `com.squareup.okhttp3:okhttp`, `com.squareup.okhttp3:mockwebserver` (tests) | Apache-2.0 | `:net` | HTTP and WebSocket |
| JNA | `net.java.dev.jna:jna` (`@aar` on Android, jar for JVM tests) | Apache-2.0 (dual with LGPL-2.1; we use Apache-2.0) | `:mls` | UniFFI Kotlin loads `libmlsffi.so` through it |
| Firebase Cloud Messaging | `com.google.firebase:firebase-messaging` | Google proprietary SDK (Firebase terms) | `:push` | FCM data messages |
| UnifiedPush connector | `org.unifiedpush.android:connector` | Apache-2.0 | `:push` | UnifiedPush registration and delivery |
| CameraX | `androidx.camera:camera-camera2`, `camera-lifecycle`, `camera-view` | Apache-2.0 | `:app` | QR scanning, camera for photos |
| ZXing core | `com.google.zxing:core` | Apache-2.0 | `:app` | QR decode and encode |
| WebRTC (own build) | `libwebrtc.aar` from our release | BSD-3-Clause | `:webrtc` | Calls (named in GOAL.md) |
| RNNoise | vendored C | BSD-3-Clause | `:calls-android` | Enhanced noise suppression (named in GOAL.md) |
| Material Symbols (icons as vector drawables) | copied SVG → XML | Apache-2.0 | `:app` | Icons |
| JUnit 4, kotlin-test | `junit:junit`, `org.jetbrains.kotlin:kotlin-test-junit` | EPL-1.0, Apache-2.0 | tests | Test runner |
| Robolectric | `org.robolectric:robolectric` | MIT | tests | Android APIs on the JVM |
| AndroidX Test (runner, rules, ext-junit) | `androidx.test:*` | Apache-2.0 | tests | Instrumented tests |
| cargo-ndk | crates.io `cargo-ndk` | Apache-2.0 OR MIT | C2, CI | Rust cross-compilation (build tool) |
| Android SDK, NDK r28+, cmdline-tools, emulator images | Google | Android SDK license | CI, local | Build and test (build tools) |
| `actions/setup-java`, `gradle/actions/setup-gradle` | GitHub Actions | MIT | G-lane | CI setup |

Server and gateway changes in B1, F1 and F2 use the Go standard library only.

---

## Parallel execution

```text
Lane A  UI         A1 scaffold → A2 → A3 → A4 → A5 → A6 → A7 → A8 → A9
Lane B  network    B1 server (now) ─────────── after A1: B2 → B3 → B4 → B5 → B6 → B7
Lane C  MLS        C1 mlsffi HPKE (now) → C2 cross-build (now) ── after A1: C3 → C4
Lane D  messaging  D1 vectors (now) ─────────── after A1: D2 → D3 → D4 → D5 → D6 → D7
Lane E  calls      E1 WebRTC scripts (now) → E2 CI + AAR ── after A1: E3 → E4 ── after E2: E5 → E6
Lane F  push       F1 gateway FCM (now) → F2 server UnifiedPush ── after A1: F3 → F4
Lane G  CI         G1 setup + workflow (now) ── after A1: G2 → G3 → G4
Lane I  integrate  after all lanes: I1 → I2
```

| Lane | Tasks | Starts immediately with | Waits for | Owns |
|---|---|---|---|---|
| A | 9 | A1 | — (A2+ wait for A1 only) | `apps/android/{settings.gradle.kts,build.gradle.kts,gradle*,gradle/}`, `apps/android/contracts/`, `apps/android/core/`, `apps/android/app/` (until I1) |
| B | 7 | B1 (server) | A1 for B2+ | `server/internal/identity/` (B1), Apple `DevicePlatform` mapping (B1), `apps/android/net/`, `apps/android/auth/` |
| C | 4 | C1 (Rust) | A1 for C3+ | `mlsffi/src/hpke.rs` (+ export lines in `lib.rs`), `scripts/build-mlsffi-android.sh`, `apps/android/mls/` |
| D | 7 | D1 (Go vectors) | A1 for D2+; C3 for D4's live-engine test only | `tools/vectors/` (new subcommands), `docs/protocol/test-vectors/` (new files), `apps/android/messaging/`, `apps/android/store/` |
| E | 6 | E1 (WebRTC scripts) | A1 for E3+; E2 for E5+ | `tools/webrtc/*android*`, `tools/webrtc/patches/000[56]-*`, `.github/workflows/webrtc-android.yml`, `apps/android/{webrtc,calls,calls-android}/` |
| F | 4 | F1 (gateway) | A1 for F3+ | `server/internal/pushgw/`, `server/internal/push/`, `server/internal/serverinfo/` (one field), `docs/protocol/push.md`, `apps/android/push/` |
| G | 4 | G1 | A1 for G2+; C2 for G2's Rust job | `.github/workflows/android.yml`, `scripts/android-*.sh`, `scripts/check-16kb.sh`, `.github/CODEOWNERS` (Android lines), `docs/release/android-*.md` |
| I | 2 | — | all lanes merged | `apps/android/app/.../AppGraph.kt`, `apps/android/interop/`, `apps/apple/Interop/` |

- About 7 coding agents run at once (one per lane); reviewers share the remaining slots.
- **Merge order into `feat/android`:** A1 first (everyone rebases on it), then lanes in any order as they finish tasks. `settings.gradle.kts` and `libs.versions.toml` are written once in A1; lanes never edit them. A lane that needs a catalog entry asks lane A, which adds it as a separate commit.
- Server changes (B1, F1, F2) merge to `main` on their own after review, like other server work, and need no Android code.

---

## Contracts (`:contracts`, written in A1)

```kotlin
package org.example.app.contracts

// MLS — the Kotlin form of the roadmap's SecureMessaging contract.
class GroupId(val raw: ByteArray)                       // equals/hashCode by content
class CommitBundle(val commit: ByteArray, val welcome: ByteArray?, val newEpoch: Long)
class Encrypted(val ciphertext: ByteArray, val epoch: Long)
sealed interface Incoming {
    class Application(val group: GroupId, val sender: String /* "<accountID>/<deviceID>" */, val plaintext: ByteArray, val epoch: Long) : Incoming
    class Commit(val group: GroupId, val newEpoch: Long, val removedMe: Boolean) : Incoming
    class Joined(val group: GroupId, val epoch: Long) : Incoming
}
class MemberKey(val identity: String, val signatureKey: ByteArray)
sealed class MlsException(message: String) : Exception(message) {
    object Malformed; object UnknownGroup; object NotMember; object Duplicate; object EpochGap; object PendingCommit
    class Storage(m: String); class Crypto(m: String); class Internal(m: String)   // each extends MlsException
}
interface MlsEngine {
    suspend fun makeKeyPackages(count: Int): List<ByteArray>
    suspend fun createGroup(): GroupId
    suspend fun add(keyPackages: List<ByteArray>, to: GroupId): CommitBundle
    suspend fun remove(members: List<String>, from: GroupId): CommitBundle
    suspend fun commitAccepted(group: GroupId)
    suspend fun commitRejected(group: GroupId)
    suspend fun encrypt(plaintext: ByteArray, group: GroupId): Encrypted
    suspend fun process(message: ByteArray): Incoming
    suspend fun epoch(group: GroupId): Long
    suspend fun exportSecret(group: GroupId, label: String, length: Int): ByteArray
    suspend fun members(group: GroupId): List<String>
    suspend fun memberKeys(group: GroupId): List<MemberKey>      // sorted by identity, includes this device
}
class FakeMlsEngine(identity: String) : MlsEngine                 // plaintext pass-through; signatureKey = "fake-sig:<identity>".toByteArray()

// HPKE: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305, base mode, empty AAD, output enc ‖ ct.
object HpkeInfo { const val ESCROW = "escrow-v1"; const val PUSH_HANDLE = "push-handle-v1"; const val LINK = "link-v1" }
class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
interface Hpke {
    fun seal(recipientPublicKey: ByteArray, info: String, plaintext: ByteArray): ByteArray
    fun open(keyPair: X25519KeyPair, info: String, sealed: ByteArray): ByteArray   // throws HpkeException
    fun generateKeyPair(): X25519KeyPair
}
class HpkeException : Exception()

// Secrets
interface SecretStore { fun load(name: String): ByteArray?; fun save(name: String, value: ByteArray); fun delete(name: String); fun wipeAll() }
class MemorySecretStore : SecretStore
object SecretNames { const val SESSION = "session"; const val DB_KEY = "db.key"; const val MLS_SIGNING = "mls.signing" }

// Server wire types used across lanes (server design §7)
class ServerException(val status: Int, val code: String, val retryAfter: Int?, val details: Map<String, Long>?) : Exception(code)
class InboxItem(val cursor: Long, val kind: String /* message|commit|welcome */, val conversationId: String, val seq: Long?,
                val epoch: Long, val senderAccountId: String, val senderDeviceId: String, val payload: ByteArray, val sentAt: java.time.Instant)
sealed interface ServerEvent {
    class Inbox(val item: InboxItem) : ServerEvent
    class DeviceRevoked(val deviceId: String) : ServerEvent
    class CallRing(val callId: String, val fromAccountId: String, val fromDeviceId: String, val video: Boolean, val payload: ByteArray) : ServerEvent
    class CallAnswered(val callId: String, val deviceId: String, val payload: ByteArray) : ServerEvent
    class CallSignal(val callId: String, val fromDeviceId: String, val payload: ByteArray) : ServerEvent
    class CallEnded(val callId: String, val reason: String) : ServerEvent
}
enum class ConnectionState { Connecting, Connected, Disconnected, Revoked, Replaced }
interface LiveSocket {
    val events: kotlinx.coroutines.flow.Flow<ServerEvent>
    val states: kotlinx.coroutines.flow.StateFlow<ConnectionState>
    suspend fun send(type: String, data: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject?   // ack data
}
interface DeliveryApi {                                    // implemented by :net, used by :messaging
    suspend fun uploadKeyPackages(keyPackages: List<ByteArray>): Int
    suspend fun keyPackageCount(): Int
    suspend fun claimKeyPackages(accountId: String, deviceIds: List<String>? = null): Map<String, ByteArray>   // deviceID → KeyPackage
    suspend fun createConversation(peerAccountId: String): ConversationInfo
    suspend fun conversation(id: String): ConversationInfo
    suspend fun postCommit(id: String, epoch: Long, commit: ByteArray, welcome: ByteArray?, addDevices: List<String>, removeDevices: List<String>): Long
    suspend fun postMessage(id: String, epoch: Long, ciphertext: ByteArray, escrow: ByteArray?, clientMsgId: String): Long
    suspend fun inbox(after: Long?, limit: Int = 100): InboxPage
    suspend fun ack(upTo: Long)
    suspend fun devices(): List<DeviceRecord>
    suspend fun uploadAttachment(bytes: java.io.InputStream, length: Long): AttachmentRef
    suspend fun downloadAttachment(id: String, fromByte: Long): java.io.InputStream
}
class ConversationInfo(val conversationId: String, val mode: String /* e2ee|server_readable */, val epoch: Long)
class InboxPage(val items: List<InboxItem>, val more: Boolean)
class DeviceRecord(val id: String, val name: String, val platform: String, val createdAt: java.time.Instant, val lastSeenAt: java.time.Instant?, val current: Boolean)
class AttachmentRef(val id: String, val expiresAt: java.time.Instant)

// Calls
enum class DataMode { UltraLow, DataSaver, Balanced, High }           // ordinal order = Swift rawValue order
@kotlinx.serialization.Serializable
data class IceCandidate(val sdp: String, val sdpMid: String? = null, val sdpMLineIndex: Int)
@kotlinx.serialization.Serializable
data class CallSignalPayload(val kind: Kind, val callID: String, val video: Boolean? = null, val sdp: String? = null,
    val dtlsFingerprint: String? = null, val candidate: IceCandidate? = null,
    val ceiling: DataMode? = null /* wire: Int 0..3 today, string accepted; owner to decide */, val videoOn: Boolean? = null) {
    enum class Kind { ring, offer, answer, candidate, renegotiate, renegotiateAnswer, modeChange }
}
sealed class CallSealException : Exception() { object WrongSender; object Undecryptable }
interface CallSignalSealer {
    suspend fun seal(payload: CallSignalPayload, to: String /* contact account ID */): ByteArray
    suspend fun open(data: ByteArray, from: String): CallSignalPayload
}

// Push
sealed interface NotificationOutcome {
    object Nothing : NotificationOutcome
    object Generic : NotificationOutcome                                  // "[App]", "New message"
    class Message(val chatId: String, val senderName: String, val preview: String) : NotificationOutcome
    class Request(val chatId: String) : NotificationOutcome               // "Message request"
    class NewOwnDevice(val deviceName: String?) : NotificationOutcome
    object FetchInbox : NotificationOutcome                              // empty payload
}
sealed interface RingOutcome {
    class Ring(val callId: String, val fromAccountId: String, val callerName: String, val video: Boolean) : RingOutcome
    class SilentMissed(val callId: String, val fromAccountId: String) : RingOutcome    // stranger
    object Drop : RingOutcome
}
interface PushPayloadHandler {
    suspend fun onMessagePush(padded: ByteArray): NotificationOutcome     // implemented by :messaging (D7)
    suspend fun onCallPush(padded: ByteArray): RingOutcome                // implemented by :messaging (D7)
}
object PushPadding {                                                      // implemented in A1 (pure, spec'd in push.md)
    val BUCKETS = intArrayOf(256, 512, 1024, 2048, 3072)
    fun unpad(padded: ByteArray): ByteArray?                              // null when malformed
}
```

---

## Lane A — Scaffold and UI on sample data

### Task A1: Gradle scaffold, version catalog, contracts, module skeletons

**Files:**
- Create: `apps/android/settings.gradle.kts` (all 12 modules), `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`), `.gitignore` (`build/`, `.gradle/`, `local.properties`, `**/jniLibs/`)
- Create: one `build.gradle.kts` per module with its kind from design §3 and its dependencies; `src/main/kotlin/org/example/app/<module>/Placeholder.kt` and one placeholder test, so every module builds
- Create: `apps/android/contracts/src/main/kotlin/org/example/app/contracts/*.kt` (the Contracts block above), `apps/android/contracts/src/test/kotlin/.../ContractsTest.kt`
- Create: `apps/android/app/src/main/AndroidManifest.xml` with `android:allowBackup="false"`, `android:dataExtractionRules="@xml/data_extraction_rules"`, `android:supportsRtl="true"`, `android:label="@string/app_name"`; `res/xml/data_extraction_rules.xml` excluding every domain; `res/values/strings.xml` with `app_name` = `[App]`
- Test: `apps/android/app/src/test/kotlin/org/example/app/ManifestRulesTest.kt` (Robolectric)

**Interfaces — Produces:** everything in "Contracts"; `PushPadding.unpad` implemented; `FakeMlsEngine` and `MemorySecretStore` implemented.

- [ ] **Step 1: Failing tests.**
  - `groupIdEqualityByContent`: `GroupId(byteArrayOf(1,2)) == GroupId(byteArrayOf(1,2))`, equal hash codes.
  - `unpadsEverySharedVector`: every case of `docs/protocol/test-vectors/push-padding.json` (read by relative path from the repo root) unpads to `payloadHex`; a 3071-byte payload case unpads to empty; a non-bucket length, a too-long length field and a non-zero padding byte return `null`.
  - `callsigCeilingDecodesIntAndString`: `{"kind":"offer","callID":"c1","ceiling":2}` and `{"kind":"offer","callID":"c1","ceiling":"balanced"}` both decode to `ceiling == DataMode.Balanced`; encoding writes `"ceiling":2`.
  - `fakeEngineRoundTrip`: two `FakeMlsEngine`s, `createGroup` + `add` + `process(welcome)` gives `Joined`; `encrypt` then `process` gives `Application` with the sender identity.
  - `backupIsDisabled` (Robolectric): the merged manifest has `allowBackup == false`.
- [ ] **Step 2:** `./gradlew test` → FAIL (types missing).
- [ ] **Step 3: Implement.** `CallSignalPayload.ceiling` uses a custom `KSerializer<DataMode>` that writes the ordinal and reads an int or the camel-case name (`ultraLow`, `dataSaver`, `balanced`, `high`).
- [ ] **Step 4:** `./gradlew test assembleDebug` → PASS.
- [ ] **Step 5: Commit** `feat(android): Gradle scaffold, version catalog, contracts`.

### Task A2: `:core` data modes, formatting, policies

**Files:** `apps/android/core/src/main/kotlin/org/example/app/core/calls/{DataModeInfo,ModePreference,DataModePolicy,DataFormat,NetworkType}.kt`, `.../settings/{UserSettings,ServerPolicy,AutoDownloadPolicy,IPHiding,SettingScope}.kt`, `AppBrand.kt`; tests `DataModeTest`, `DataModePolicyTest`, `DataFormatTest`, `SettingsPolicyTest`.

**Interfaces — Produces:** the Kotlin port of AppCore plan 1 Tasks 1–3, same names in Kotlin case (`DataModePolicy.ceiling(server, settings, network, inCall)`, `DataFormat.size(bytes)`, `DataFormat.used(bytes)`, `DataFormat.estimate(mode)`, `IPHiding { Never, NonContacts, Always; fun requiresRelay(isContact: Boolean): Boolean }`, `AppBrand.NAME = "[App]"`). Titles are string resource keys, not text: `DataModeInfo.titleKey(mode)` returns `"mode_ultra_low"` etc.

- [ ] **Step 1: Failing tests** with the plan 1 values: `estimatedMBPer10Minutes == [2, 3, 5, 8]`; `bytesPerSecond(DataSaver) == 5000`; `DataFormat.size(240_000) == "240 KB"`, `size(1_200_000) == "1.2 MB"`, `size(999) == "1 KB"`, `used(1_200_000) == "≈ 1.2 MB used"`; the Review Focus 4 and 5 cases from plan 1 (`wifiToMobileKeepsInCallChoice`, `serverMaximumCapsUserChoice`); `IPHiding.NonContacts.requiresRelay(isContact = false) == true`.
- [ ] **Step 2:** `./gradlew :core:test` → FAIL.
- [ ] **Step 3: Implement** by porting `apps/apple/Packages/AppCore/Sources/AppCore/{Calls,Settings}` logic line for line.
- [ ] **Step 4:** `./gradlew :core:test` → PASS.
- [ ] **Step 5: Commit** `feat(android-core): data modes, formatting, settings policies`.

### Task A3: `:core` chats, contacts, sample services, simulated calls, onboarding

**Files:** `.../core/chats/{Models,ChatFolder,ChatStore,SampleChatStore,SampleData}.kt`, `.../core/calls/{CallEngine,SimulatedCallEngine}.kt`, `.../core/account/{OnboardingFlow,InviteLink,AccountService,SampleAccountService,DevicePlatform}.kt`; tests `ChatStoreTest`, `CallEngineTest`, `OnboardingFlowTest`, `InviteLinkTest`.

**Interfaces — Produces:** `interface ChatStore` (flows of chats, messages, contacts, requests; `send`, `markRead`, `accept`, `block`, `report`, `recordCall(summary, contactId, outgoing, at)`, `incomingCall(fromAccountId): IncomingCallDecision`); `interface CallEngine` (`state: StateFlow<CallState>`, `start(contactId, video)`, `answer(video)`, `decline()`, `hangUp()`, `setMode(ModePreference)`, `setVideo(on)`, `setNetwork(NetworkType)`); `interface AccountService` (suspend, throws `AccountError` with the plan 6 N2 cases); `enum class DevicePlatform { IPhone, Mac, Android }` with wire values `iphone`, `mac`, `android`; `InviteLink.parse(text)` accepting `XXXXX-XXXXX` from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`.

- [ ] **Step 1: Failing tests:** sample data has direct chats only, one Arabic and one Hindi name, and one very long name; a stranger's first message lands in requests, not in folders or unread counts; `SimulatedCallEngine` plays `good → weak → audioOnly → reconnecting → ended(connectionLost)` with the banner texts' resource keys; `OnboardingFlow` skips the invite step after an invite link; `InviteLink.parse("ABCDE-FGH23")` succeeds and `"ABCDE-FGHI0"` fails.
- [ ] **Step 2:** `./gradlew :core:test` → FAIL.
- [ ] **Step 3: Implement** (port of plan 1 Tasks 4–6 and plan 6 N2).
- [ ] **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-core): chats, sample services, simulated calls, onboarding`.

### Task A4: App shell, theme, navigation, strings rules

**Files:** `apps/android/app/src/main/kotlin/org/example/app/{MainActivity,AppGraph,AppRoot}.kt`, `ui/theme/{Theme,Color,Type}.kt`, `ui/nav/{Routes,MainTabs}.kt`, `res/values/strings.xml`, `res/drawable/ic_*.xml` (Material Symbols); tests `StringsRulesTest`, `MainTabsTest` (Robolectric Compose).

**Interfaces — Produces:** `AppGraph(context, sampleData: Boolean)` exposing `accountService`, `chatStore`, `callEngine`; `MainActivity` reads the intent extra `sampleData` (default `true` until I1).

- [ ] **Step 1: Failing tests:**
  - `forbiddenWordsAbsent`: no `values*/strings.xml` entry matches `\b(codec|kbps|relay|MLS|E2EE)\b` (case-insensitive).
  - `brandOnlyInAppName`: only `app_name` contains `[App]`.
  - `encryptionStringsOnlyInChatInfo`: `R.string.encryption_` appears in no `.kt` file except `ChatInfoScreen.kt`.
  - `tabsInTelegramOrder`: the bar shows Contacts, Calls, Chats, Settings, and Chats is selected at launch.
  - `everyClickableHasLabel`: on each tab, no node with a click action lacks both text and content description.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → FAIL.
- [ ] **Step 3: Implement:** `enableEdgeToEdge()`, dynamic colour on API 31+, a blue-seed baseline scheme on 29–30, `NavigationBar` with four items.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-app): shell, theme, tabs, string rules`.

### Task A5: Chat list, chat screen, chat info

**Files:** `ui/chatlist/{ChatListScreen,ChatRow,FolderChips,StatusStrip,ChatListViewModel}.kt`, `ui/chat/{ChatScreen,MessageBubble,Composer,ChatViewModel}.kt`, `ui/chatinfo/ChatInfoScreen.kt`; tests `ChatListScreenTest`, `ChatScreenTest`, `ChatInfoScreenTest`.

- [ ] **Step 1: Failing tests:** folder chips All/Unread/Personal/Work filter the list; the message-requests row sits at the top with its count; on mobile data a photo shows the blurred preview with "Photo · 240 KB" and a download button, and a voice message does not; the mic turns into send when text is typed; chat info shows "End-to-end encrypted" or "Not end-to-end encrypted: the server admin can read this chat"; the chat and list screens contain no node whose text contains "encrypt".
- [ ] **Step 2:** FAIL. **Step 3: Implement** with the design §13 component mapping. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-app): chat list, chat screen, chat info`.

### Task A6: Call screens, data sheet, Calls tab

**Files:** `ui/call/{CallScreen,CallControls,CallBanner,DataModeSheet,CallViewModel,IncomingCallScreen}.kt`, `ui/calls/CallHistoryScreen.kt`; tests `CallScreenTest`, `DataModeSheetTest`, `CallHistoryTest`.

- [ ] **Step 1: Failing tests:** controls Speaker, Mute, Video, Data, End with labels; the sheet lists Auto, Ultra-low, Data saver, Balanced, High quality with "~2 MB per 10 min" style estimates; a mode above the server maximum is disabled with "Limited by your server"; a mode above the mobile limit on mobile data stays enabled with "Above your mobile data limit"; banner sequence texts match the UI design; the call screen always uses the dark scheme; the Missed chip filters history.
- [ ] **Step 2:** FAIL. **Step 3: Implement** on `SimulatedCallEngine`. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-app): call screens, data sheet, call history`.

### Task A7: Contacts, message requests, settings

**Files:** `ui/contacts/{ContactsScreen,MessageRequestsScreen,AddContactScreen}.kt`, `ui/settings/{SettingsScreen,DataStorageScreen,PrivacyScreen,NotificationsScreen,LinkedDevicesScreen,CallsAudioScreen,ChatFoldersScreen}.kt`; tests per screen.

- [ ] **Step 1: Failing tests:** "Hide my IP address in calls" offers Never / With people not in my contacts (Recommended) / Always; Data & Storage defaults: Call data mode Auto, Limit on mobile data Data saver, video off on mobile on, auto-download Wi-Fi All / mobile None / voice Always; Calls & Audio offers Standard / Enhanced / Off with no Voice Isolation row; Notifications shows "Notification delivery" with Google and UnifiedPush choices (sample state) and a full-screen-permission row when `canUseFullScreenIntent` is false; Linked devices lists name, type (iPhone, Mac, Android) and last active.
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-app): contacts, requests, settings`.

### Task A8: Onboarding, linking screens, QR

**Files:** `ui/onboarding/{WelcomeScreen,JoinServerScreen,InviteCodeScreen,CreateAccountScreen,VerifyEmailScreen,PasskeyScreen,PermissionsScreen,LinkDeviceScreen,LinkApprovalScreen,LostDeviceScreen,OnboardingViewModel}.kt`, `ui/qr/{QrScanner,QrImage}.kt` (CameraX + ZXing); tests per screen, `QrImageTest`.

- [ ] **Step 1: Failing tests:** Welcome shows the three points and both buttons; the [App] server card is first and marked "Recommended"; email help text matches the UI design; the code field accepts 6 digits only; the passkey screen says "Use your screen lock to sign in" (planner's wording for Android) and offers "Use an email code instead"; the approval screen shows device name, type and time and the warning "This device will be able to read all your future messages. Only approve it if it's yours and you started linking it just now."; `QrImage` encodes and ZXing decodes back the same URL; permissions screens show the one-line reason before the system prompt.
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-app): onboarding, linking, QR`.

### Task A9: Accessibility and languages pass

**Files:** `app/src/test/kotlin/org/example/app/AccessibilityTest.kt`, `app/src/androidTest/kotlin/org/example/app/SmokeTest.kt`, fixes across `ui/`.

- [ ] **Step 1: Failing tests:** at font scale 2.0 the chat row keeps time and badge visible for the long name; in RTL the bubbles and composer mirror; call banners are polite live regions; every screen passes `everyClickableHasLabel`; `SmokeTest` (instrumented) launches, opens a chat, sends a sample message and starts a simulated call.
- [ ] **Step 2:** FAIL. **Step 3: Fix.** **Step 4:** `./gradlew :app:testDebugUnitTest :app:pixel6Api29DebugAndroidTest` → PASS.
- [ ] **Step 5: Commit** `test(android-app): accessibility, RTL, font scale, smoke test`.

---

## Lane B — Networking and accounts

### Task B1: Server and Apple changes for Android devices (starts now; owner review: authentication)

**Files:**
- Modify: `server/internal/identity/sessions.go` (`validDevice` accepts `android`), `server/internal/identity/passkey.go` (extra `RPOrigins`), `server/internal/config/*.go` (`APP_ANDROID_APPS`), `server/internal/serverinfo/` or the route file that serves `/.well-known/apple-app-site-association` (add `/.well-known/assetlinks.json`)
- Modify: `apps/apple/Packages/AppCore/Sources/AppCore/Account/OnboardingFlow.swift` (`DevicePlatform.android`), `apps/apple/Packages/ServerKit/Sources/ServerKit/Account/LiveAccountService.swift` (`wire`/`platform` mapping), and the Swift UI string for "Android" in `Localizable.xcstrings`
- Test: `server/internal/identity/android_test.go`, `ServerKitTests/PlatformMappingTests.swift`

**Interfaces — Produces:** `APP_ANDROID_APPS="org.example.app:<64 hex>[,…]"`; `GET /.well-known/assetlinks.json` → `[{"relation":["delegate_permission/common.get_login_creds","delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"org.example.app","sha256_cert_fingerprints":["AA:BB:…"]}}]`; WebAuthn origin `android:apk-key-hash:<base64url, no padding, of the 32 fingerprint bytes>`.

- [ ] **Step 1: Failing tests:** `acceptsAndroidPlatform` (sign-in with `platform:"android"` → 200; `"windows"` → `bad_request` "platform must be iphone, mac or android"); `assetlinksListsConfiguredApps` (colon hex upper case); `assetlinksEmptyWhenUnset` (`[]`); `passkeyAcceptsAndroidOrigin` (softauth with origin `android:apk-key-hash:…` passes, another hash fails); Swift `androidPlatformRoundTrips` (`"android"` ↔ `.android`, unknown → `.mac` unchanged).
- [ ] **Step 2:** `go test ./internal/identity/...` and `swift test` in ServerKit → FAIL.
- [ ] **Step 3: Implement.** Update server design §7.1 and §7.5 tables in the same commit.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(server): Android platform, assetlinks, Android passkey origins`.

### Task B2: `:net` coding, HTTP pipeline, errors

**Files:** `apps/android/net/src/main/kotlin/org/example/app/net/{Base64Url,ServerJson,ServerClient,Auth,Session}.kt`; tests `CodingTest`, `ServerClientTest` (MockWebServer).

**Interfaces — Produces:** `ServerClient(host: String, session: SessionStore, http: OkHttpClient)` (+ `internal` constructor with `baseUrl: HttpUrl` for tests); `suspend fun <T> request(method, path, body: JsonElement?, auth: Auth, deserializer): T`; `setOnUnauthorized(suspend () -> Unit)`; `data class Session(host, accountId, deviceId, token)`; `class SessionStore(store: SecretStore)` with `current`, `save`, `clear`.

- [ ] **Step 1: Failing tests** (the plan 6 N1 cases): `base64UrlRoundTrip` (`[0xfb,0xff]` ↔ `"-_8"`, `"-_8="` decodes), `decodesGoTimestamps`, `errorEnvelopeMapsToServerException` (`stale_epoch` with `details.currentEpoch == 3`), `retryAfterParsed`, `transportFailureIsNetwork`, `bearerOnlyWhenAuthed`, `clearsSessionOnceOn401` (5 concurrent 401s → one callback), `baseUrlIsHttps`.
- [ ] **Step 2:** `./gradlew :net:test` → FAIL. **Step 3: Implement** (OkHttp timeouts 30 s; no logging interceptor). **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-net): JSON coding, HTTP pipeline, errors, session`.

### Task B3: `:net` endpoints and server info

**Files:** `.../net/api/{IdentityApi,DeliveryApiImpl,SocialApi,AttachmentsApi,TurnApi,PushTokenApi,ServerInfoApi}.kt`, `.../net/models/*.kt`; tests per file.

**Interfaces — Produces:** one suspend function per server design §7 endpoint (names as plan 6 L1, Kotlin case); `DeliveryApiImpl : DeliveryApi`; `ServerInfo` with `protocol`, `host`, `requiresInvite`, `policy`, `encryption.escrowKey: ByteArray?`, `push.gateway`, `push.vapidKey: String?`, `features`, `limits`; `ProtocolCheck.check(info): ProtocolResult { Supported, UpdateRequired }` with `MAX_PROTOCOL = 1`; `putPushToken(kind: String, body: JsonObject)` for kinds `alert`, `fcm`, `unifiedpush`.

- [ ] **Step 1: Failing tests:** each endpoint's method, path and body against MockWebServer, using the field names from server design §7; `inboxItemDecodes` (base64url payload, nanosecond `sentAt`); `claimMapsByDevice`; `protocol2RequiresUpdate`; `unknownFieldsIgnored`; `attachmentDownloadSendsRange` (`Range: bytes=65552-`).
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-net): server endpoints and server info`.

### Task B4: `:net` live connection

**Files:** `.../net/live/{LiveConnection,Frames,Backoff}.kt`; tests `LiveConnectionTest` (MockWebServer WebSocket), `BackoffTest`.

**Interfaces — Produces:** `class LiveConnection(client: ServerClient, http: OkHttpClient, clock, random) : LiveSocket` with `start()`, `stop()`.

- [ ] **Step 1: Failing tests:** `decodesEveryServerFrame` (`inbox`, `device.revoked`, `call.ring`, `call.answered`, `call.signal` with `fromDeviceID`, `call.ended`); `ackMatchesId` (`c1`, `c2`); `sendTimesOutAfter10s`; `backoffGrowsAndCaps` (1, 2, 4 … 60 s with full jitter bounds); `resetsAfterStableConnection` (30 s); `replaced4001StopsWithoutReconnect`; `revoked4003ReportsRevoked`; `missingPongReconnects` (ping every 25 s, 10 s pong wait); `requestsCompression` (`Sec-WebSocket-Extensions` contains `permessage-deflate`; if OkHttp at the pinned version does not send it, record a `Ruling:` and drop the assertion).
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-net): live WebSocket connection`.

### Task B5: `:auth` Keystore secret store (owner review: keys)

**Files:** `apps/android/auth/src/main/kotlin/org/example/app/auth/{KeystoreSecretStore,KeystoreKey}.kt`; instrumented test `KeystoreSecretStoreTest`; JVM test `SecretFormatTest`.

**Interfaces — Produces:** `class KeystoreSecretStore(context: Context, dir: File = File(context.noBackupFilesDir, "secrets")) : SecretStore`; alias `app.secrets.v1`; file format `nonce(12) ‖ ct ‖ tag(16)`, AAD = UTF-8 name; `KeystoreKey.backing(): String` = `"strongbox"` or `"tee"`.

- [ ] **Step 1: Failing tests:** `roundTrip`; `wrappedSecretIsNotPlaintext` (the file does not contain the value bytes); `swappedSecretFailsToOpen` (copying `session`'s file over `db.key` makes `load("db.key")` throw); `wipeAllDeletesAliasAndFiles`; `strongBoxFallsBackToTee` (on the emulator, which has no StrongBox, `backing() == "tee"`).
- [ ] **Step 2:** `./gradlew :auth:pixel6Api29DebugAndroidTest` → FAIL.
- [ ] **Step 3: Implement** (`KeyGenParameterSpec`: AES-256, GCM, no padding, `setRandomizedEncryptionRequired(true)`, `setUnlockedDeviceRequired(false)`, StrongBox first).
- [ ] **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-auth): Keystore-wrapped secret store`.

### Task B6: `:auth` passkeys and biometric gate (owner review: authentication)

**Files:** `.../auth/{CredentialManagerPasskeys,PasskeySupport,BiometricGate}.kt`; JVM tests `PasskeySupportTest`, Robolectric `BiometricGateTest`.

**Interfaces — Produces:** `interface Passkeys { suspend fun create(optionsJson: String): String; suspend fun get(optionsJson: String): String }` throwing `AccountError.PasskeyUnavailable` or `AccountError.Cancelled`; `PasskeySupport.supported(host: String, assetLinksJson: String?, packageName: String, certSha256: ByteArray): Boolean`; `BiometricGate.authenticate(activity, title): Result` requiring `BIOMETRIC_STRONG`, `Unavailable` when none enrolled.

- [ ] **Step 1: Failing tests:** `supportedWhenAssetLinksListsUs`; `unsupportedForOtherCert`; `unsupportedWhenFileMissing`; `noCreateOptionMapsToUnavailable` (`CreateCredentialNoCreateOptionException` → `PasskeyUnavailable`); `userCancelMapsToCancelled`; `noBiometricsIsUnavailable` (Robolectric `BiometricManager` stub returns `BIOMETRIC_ERROR_NONE_ENROLLED`).
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-auth): passkeys via Credential Manager, biometric gate`.

### Task B7: `:net` live account service and linking

**Files:** `.../net/account/{LiveAccountService,LinkFlow,LinkQr}.kt`; tests `LiveAccountServiceTest`, `LinkFlowTest`.

**Interfaces — Produces:** `class LiveAccountService(client, sessionStore, passkeys: Passkeys, hpke: Hpke, snapshots: SnapshotSource, deviceName: String) : AccountService`; `LinkQr.build(host, linkId, secret, publicKey): String`, `LinkQr.parse(text, ownHost): LinkQr.Parsed` (throws `AccountError.WrongServer`); `interface SnapshotSource { suspend fun export(): ByteArray; suspend fun import(bytes: ByteArray) }` (D5 implements).

- [ ] **Step 1: Failing tests:** `signUpWithoutPasskeyWhenUnsupported` (no `credential` in `signup/finish`); `signUpWithPasskey`; `emailSignIn`; `qrHasExactlyOneDot`; `approveLinkRejectsOtherHost` (no request reaches the other host); `linkApprovalRequiresBiometrics` (gate `Unavailable` → no `approve` request); `linkRequestShowsDeviceDetails`; `claimPollsEvery2sUntilApproved`; `sealedThatFailsImportsNothing`; `platformIsAndroid` (every device-creating request sends `"platform":"android"`); `unsupportedProtocolStopsSignUp`.
- [ ] **Step 2:** FAIL. **Step 3: Implement** with `FakeHpke` and `MemorySecretStore`. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-net): live account service and device linking`.

---

## Lane C — MLS bindings and Android cross-build

### Task C1: HPKE exports in `mlsffi` (starts now; owner review: cryptography)

**Files:** Create `apps/apple/Packages/SecureMessaging/mlsffi/src/hpke.rs`; modify `src/lib.rs` (`pub mod hpke;`); test `mlsffi/tests/hpke_vectors.rs`; regenerate `Sources/MLSFFIBindings/mlsffi.swift` (CI drift check) — the Swift side does not use the new functions.

**Interfaces — Produces** (UniFFI exports, suite `CURVE25519_CHACHA` of `mls-rs-crypto-rustcrypto`):

```rust
#[derive(uniffi::Record)] pub struct HpkeKeyPair { pub private_key: Vec<u8>, pub public_key: Vec<u8> }
#[uniffi::export] pub fn hpke_generate_keypair() -> Result<HpkeKeyPair, FfiError>;
#[uniffi::export] pub fn hpke_seal(recipient_public_key: Vec<u8>, info: Vec<u8>, plaintext: Vec<u8>) -> Result<Vec<u8>, FfiError>;   // enc ‖ ct, empty AAD
#[uniffi::export] pub fn hpke_open(private_key: Vec<u8>, public_key: Vec<u8>, info: Vec<u8>, sealed: Vec<u8>) -> Result<Vec<u8>, FfiError>;
```

If plan 4's `member_keys` export is still missing, add `Engine::member_keys(group_id) -> Vec<MemberKeyRecord { identity: String, signature_key: Vec<u8> }>` here, sorted by identity; whichever of plan 4 M7 and this task lands second records a `Ruling:`.

- [ ] **Step 1: Failing tests:** `opens_escrow_vector` and `opens_sealed_handle_vector` (every case in `docs/protocol/test-vectors/{escrow,sealed-handle}.json` opens to `plaintextJSON`); `opens_push_handle_v1` (`docs/protocol/vectors/push-handle-v1.json`); `seal_open_round_trip`; `wrong_info_fails` (`escrow-v1` ciphertext with `link-v1` → `FfiError::Crypto`); `sealed_length_is_32_plus_pt_plus_16`; `short_input_is_malformed`.
- [ ] **Step 2:** `cargo test --locked` → FAIL.
- [ ] **Step 3: Implement:** a thin call into `CipherSuiteProvider::hpke_seal`/`hpke_open`/`kem_generate`, AAD `None`, output `kem_output ‖ ciphertext`. No logic beyond splitting and joining the two parts.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(mlsffi): HPKE seal, open and key generation exports`.

### Task C2: Android cross-build and Kotlin bindings (starts now)

**Files:** Create `scripts/build-mlsffi-android.sh`, `scripts/build-mlsffi-android_test.sh`, `apps/android/mls/uniffi.toml`; generated (committed) `apps/android/mls/src/main/kotlin/org/example/app/mls/ffi/mlsffi.kt`.

**Interfaces — Produces:** `build-mlsffi-android.sh [--host-only]`:
1. `rustup target add aarch64-linux-android x86_64-linux-android`
2. `cargo ndk -t arm64-v8a -t x86_64 --platform 29 -o apps/android/mls/src/main/jniLibs build --release --lib --locked` (skipped with `--host-only`)
3. `cargo build --release --lib --locked` (host `libmlsffi.so` for JVM tests, path printed as `HOST_LIB=…`)
4. `cargo run --release --bin uniffi-bindgen -- generate --library target/release/libmlsffi.so --language kotlin --no-format --config ../../../../android/mls/uniffi.toml --out-dir build/kotlin`, then copy `org/example/app/mls/ffi/mlsffi.kt`
5. `scripts/check-16kb.sh` on each built `.so`

`uniffi.toml`: `[bindings.kotlin] package_name = "org.example.app.mls.ffi"`, `cdylib_name = "mlsffi"`.

- [ ] **Step 1: Failing test** `build-mlsffi-android_test.sh`: with `--host-only`, the generated Kotlin file exists, declares `package org.example.app.mls.ffi`, contains `fun hpkeSeal(`, and `git diff --exit-code` on it is clean after a second run (drift check).
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.** `ANDROID_NDK_HOME` comes from `scripts/android-setup.sh` (G1); until G1 lands, the test uses `--host-only`.
- [ ] **Step 4:** PASS (and, with the NDK, both ABIs build).
- [ ] **Step 5: Commit** `build(mls): Android cross-build and Kotlin bindings for mlsffi`.

### Task C3: `:mls` live engine (owner review: cryptography)

**Files:** `apps/android/mls/src/main/kotlin/org/example/app/mls/{LiveMlsEngine,MlsGate,SigningKeys,ErrorMapping}.kt`; JVM tests `LiveMlsEngineTest` (host library via `jna.library.path`, set in `mls/build.gradle.kts` from `HOST_LIB`).

**Interfaces — Produces:** `class LiveMlsEngine(identity: String, storageDir: File, secrets: SecretStore) : MlsEngine`; `object MlsGate { suspend fun <T> withLock(block: suspend () -> T): T }`; `SigningKeys.loadOrCreate(secrets): SignatureKeypair` (name `mls.signing`, JSON `{"pub":b64,"sec":b64}`).

- [ ] **Step 1: Failing tests** (two engines in temp dirs, as plan 4's scenarios): `createAddWelcomeJoin`; `encryptProcessRoundTrip` with sender `"a1/d1"`; `staleEpochCommitRejectedThenRetry`; `pendingCommitBlocksSecond` (`MlsException.PendingCommit`); `duplicateIsTyped` (`MlsException.Duplicate`); `epochGapIsTyped`; `restartKeepsGroups` (new engine on the same dir); `signingKeyPersistsAcrossRestart`; `memberKeysSortedIncludesSelf`.
- [ ] **Step 2:** `./gradlew :mls:test` → FAIL.
- [ ] **Step 3: Implement:** every call on `Dispatchers.IO`; `FfiException` → `MlsException` one-to-one.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-mls): live MLS engine over UniFFI`.

### Task C4: `:mls` HPKE wrapper, vectors, device smoke test

**Files:** `.../mls/RustHpke.kt`; JVM test `RustHpkeTest`; instrumented `MlsDeviceSmokeTest`.

**Interfaces — Produces:** `class RustHpke : Hpke`.

- [ ] **Step 1: Failing tests:** `sealsAndOpensSharedVectors` (escrow, sealed-handle, push-handle-v1 open; a fresh seal opens with the vector key); `infoMismatchThrows`; instrumented `loadsOnDeviceAbi` (engine round trip and HPKE round trip on the x86_64 emulator, API 29 and 36).
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** `./gradlew :mls:test :mls:pixel6Api29DebugAndroidTest` → PASS.
- [ ] **Step 5: Commit** `feat(android-mls): HPKE wrapper and device smoke test`.

---

## Lane D — Local storage and messaging

### Task D1: Shared vectors for content, safety, link sealing and attachments (starts now; owner review: cryptography)

**Files:** Create `tools/vectors/link/main.go`, `tools/vectors/attachment/main.go`; create `docs/protocol/test-vectors/{link-v1.json,attachment-stream.json,content-v1.json,safety-v1.json}`; create `scripts/check-vector-copies.sh` (fails when `content-v1.json` or `safety-v1.json` differ from the copies in `apps/apple/Packages/Messaging/Tests/MessagingTests/Resources/`); modify `docs/protocol/README.md` (list the new files) and `server/internal/vectors/vectors_test.go` (Go verifies the two new files).

**Interfaces — Produces:**
- `link-v1.json`: `{"version":1,"note":"TEST ONLY — published keys","info":"link-v1","recipientPrivateKeyHex","recipientPublicKeyB64","cases":[{"plaintextJSON","sealedB64"}]}` with a `LinkSnapshot` plaintext.
- `attachment-stream.json`: `{"version":1,"chunkSize":65536,"cases":[{"name","keyHex","plaintextLength","plaintextSeed","ciphertextSha256Hex","digestB64"}]}` for lengths 0, 1, 65 536 and 65 537 (plaintext = byte `i mod 251`), using plan 7 Design 7 (nonce `0x00000000 ‖ u64be(index)`, AAD `0x01` on the last chunk, else `0x00`).

- [ ] **Step 1: Failing tests:** Go `TestLinkVector` and `TestAttachmentVector` open and re-derive the files; `check-vector-copies.sh` passes.
- [ ] **Step 2:** FAIL. **Step 3: Implement** (standard library only). **Step 4:** PASS.
- [ ] **Step 5: Commit** `test(vectors): link sealing, attachment stream, shared content and safety vectors`.

### Task D2: `:messaging` content envelope and safety numbers

**Files:** `apps/android/messaging/src/main/kotlin/org/example/app/messaging/content/{ContentEnvelope,Att,LinkSnapshot}.kt`, `.../sync/SafetyNumber.kt`; tests `ContentEnvelopeTest`, `SafetyNumberTest`.

**Interfaces — Produces:** `sealed interface ContentEnvelope` with `Text(t, r?)`, `Photo(a, w, h, th?, r?)`, `Voice(a, dur, r?)`, `File(a, name, mime, r?)`, `Edit(target, t)`, `Delete(target)`, `React(target, e)`, `Receipt(s, ids)`, `CallSig(p: CallSignalPayload)`, `Mode(m)`, `Unknown(raw)`; common `v`, `id`, `at`; `ContentCodec.encode/decode`; `Att(id, key, dg, sz, csz)` with standard base64; `LinkSnapshot` with plan 7's fields (`settings` kept as opaque bytes); `SafetyNumber.compute(memberKeys, accountA, accountB): List<String>` (12 groups).

- [ ] **Step 1: Failing tests:** `decodesEverySwiftContentFixture` (every case of `docs/protocol/test-vectors/content-v1.json` decodes and re-encodes to the same JSON object); `encodesCallsigLikeSwift` (the `callsig` case's `ceiling` stays `2`); `unknownKindIsUnknown` and `v2IsUnknown`; `textOver4096Rejected`; `safetyMatchesVector` (`safety-v1.json`); `linkSnapshotDecodesSwiftShape`.
- [ ] **Step 2:** `./gradlew :messaging:test` → FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-messaging): content envelope and safety numbers`.

### Task D3: `:store` Room on SQLCipher

**Files:** `apps/android/store/src/main/kotlin/org/example/app/store/{AppDatabase,Entities,Daos,RoomRepository,DbKey,TextFold}.kt`; instrumented `RoomRepositoryTest`, `SqlCipherTest`; JVM `TextFoldTest`.

**Interfaces — Produces:** `interface Repository` (in `:messaging`, ports package; D3 implements it) covering the plan 7 F1 tables: `conversations` (with `inner_mode`, `mode_mismatch`), `members`, `messages`, `inbox_raw`, `processed`, `outbox`, `attachments`, `contacts`, `folders`, `blocked`, `kv`, `calls`, `safety`, and an FTS5 table `messages_fts` with `tokenize='trigram'`; `DbKey.loadOrCreate(secrets): ByteArray` (name `db.key`, 32 random bytes); `TextFold.fold(s)` (NFKC, case fold, strip combining marks).

- [ ] **Step 1: Failing tests:** `fileIsNotPlainSqlite` (the first 16 bytes of `messages.db` are not `SQLite format 3\0`); `wrongKeyFailsToOpen`; `inboxRawThenProcessIsAtomic`; `processedHashDedups`; `searchFindsLatinIndicCjkRtl` (trigram, 3+ characters) and `shortQueryUsesLike`; `bulkInsert10k` (logs the time; the owner records it on the OnePlus 5); `TextFold` folds `"Ｃafé"` to `"cafe"`.
- [ ] **Step 2:** `./gradlew :store:pixel6Api29DebugAndroidTest` → FAIL.
- [ ] **Step 3: Implement** (WAL mode; Room `openHelperFactory(SupportOpenHelperFactory(key))`; if SQLCipher at the pinned version lacks the `trigram` tokenizer, record a `Ruling:` and fall back to `LIKE` on the folded column).
- [ ] **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-store): Room on SQLCipher, schema, search`.

### Task D4: `:messaging` conversation manager and inbox processor (owner review: MLS use)

**Files:** `.../messaging/sync/{ConversationManager,InboxProcessor,ModePin}.kt`, `.../messaging/ports/{Repository,Clock}.kt`; tests with `FakeMlsEngine`, a fake `DeliveryApi` and an in-memory `Repository`.

**Interfaces — Produces:** `ConversationManager.preferredConversation(contactId)` (smallest active conversation ID), `create(contactId)` (claim, create, add, commit, then `mode` envelope first); `InboxProcessor.sync()` (store raw → ack → process each under `MlsGate` in one transaction).

- [ ] **Step 1: Failing tests** (plan 7 A1/A2 cases): `preferredIsSmallestId`; `createSendsModeFirst`; `rawStoredBeforeAck`; `processedTwiceIsSkippedButAcked`; `echoedOwnCommitSkipped` (Design 17); `staleEpochRetriesAtMostThree`; `unknownOutcomeFetchesInboxFirst` (never resends the same commit); `modePinnedFromFirstEnvelope`; `laterDifferentModeSetsMismatch`; `senderFromCredentialNotServer` (a forged `senderAccountId` is ignored).
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS; plus one test against `LiveMlsEngine` with the host library once C3 is merged.
- [ ] **Step 5: Commit** `feat(android-messaging): conversations and inbox processing`.

### Task D5: Outbox, escrow, receipts, device sync

**Files:** `.../messaging/send/{Outbox,Receipts}.kt`, `.../messaging/crypto/Escrow.kt`, `.../messaging/sync/{DeviceSync,Membership,KeyPackagePool}.kt`; tests per file.

**Interfaces — Produces:** `Escrow.seal(body: ContentEnvelope, conversationId, senderAccountId, sentAt, serverKey: ByteArray, hpke: Hpke): ByteArray`; `KeyPackagePool.refillIfDue()` (check at most every 6 h; upload 50 when fewer than 10); `DeviceSync` implementing `SnapshotSource`, `reconcileOwnDevices()`, `handleRevoked(deviceId)`; `Membership.diff()` producing the device lines and the own-device notice.

- [ ] **Step 1: Failing tests:** `escrowOpensWithVectorKey` (sealed with the `escrow.json` public key, opened with its private key, plaintext keys `conversationID`, `senderAccountID`, `sentAt` RFC 3339, `body`); `noEscrowWhenPinnedE2ee` (server says `server_readable`, pin says `e2ee` → no escrow, sending paused, `SystemEvent.messagesPaused`); `escrowRequiredRefreshesThenRetriesOnce`; `deliveredReceiptPerBatch`; `refillOnlyBelow10`; `refillAtMost6Hourly`; `revokedDeviceRemovedWithin24h`; `newOwnDeviceNotifies`; `snapshotRoundTrip`.
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-messaging): outbox, escrow, receipts, device sync`.

### Task D6: Attachments (owner review: cryptography)

**Files:** `.../messaging/crypto/AttachmentCipher.kt`, `.../messaging/media/{Uploader,Downloader,AutoDownload,PhotoPrep,VoiceRecorder}.kt`; tests `AttachmentCipherTest`, `AutoDownloadTest`, Robolectric `PhotoPrepTest`.

**Interfaces — Produces:** `AttachmentCipher.encrypt(input, key): EncryptedBlob` and `decrypt(input, key, digest)` (chunk 65 536, nonce `0^4 ‖ u64be(i)`, AAD last-flag); `PhotoPrep.prepare(uri): Prepared` (JPEG, max side 1600, quality 70; thumbnail max side 32, quality 40, at most 1536 bytes); `VoiceRecorder` (`MediaRecorder`, `AudioEncoder.OPUS`, `OutputFormat.OGG`, 16 kHz mono, 16 kbps; see the voice-format question in design §17).

- [ ] **Step 1: Failing tests:** `matchesAttachmentVector` (every `attachment-stream.json` case); `truncatedFileFails`; `resumesFromRange` (decrypt from chunk 1 after a range fetch); `autoDownloadRules` (Wi-Fi all, mobile none, voice always); `thumbnailUnder1536Bytes`.
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-messaging): attachment cipher, transfer, photos, voice`.

### Task D7: `LiveChatStore`, notification processor, call signal sealer

**Files:** `.../messaging/{LiveChatStore,NotificationProcessor,ChatCallSealer}.kt`; tests per file.

**Interfaces — Produces:** `class LiveChatStore(...) : ChatStore, CallSignalSealer`; `class NotificationProcessor(...) : PushPayloadHandler`.

- [ ] **Step 1: Failing tests:** `notificationOutcomes` (contact → `Message` with name and preview; stranger → `Request`; muted, blocked, receipt, edit, delete, react → `Nothing`; undecryptable → `Generic`; empty → `FetchInbox`; malformed padding → `Generic`); `ringFromContactRings`; `strangerRingIsSilentMissed`; `wrongSenderThrows` (`CallSealException.WrongSender`); `sealUsesPreferredConversation`; `recordCallWritesOnce`; `searchUsesFold`.
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-messaging): live chat store, notification processor, call sealer`.

---

## Lane E — WebRTC Android build and calls

### Task E1: Android build scripts and patches (starts now; owner review: WebRTC/Opus build)

**Files:** Create `tools/webrtc/build-android.sh`, `tools/webrtc/package-android.sh`, `tools/webrtc/patches/0005-android-opus-info.patch`, `tools/webrtc/patches/0006-android-capture-post-processing.patch` (or a `Ruling:` instead, see design §8.1), `tools/webrtc/build_android_test.sh`; modify `tools/webrtc/README.md` (Android section).

**Interfaces — Produces:** `build-android.sh <abi>` (`arm64-v8a` or `x86_64`) → `out/android/<abi>/libjingle_peerconnection_so.so` plus the Java classes jar; `package-android.sh` → `out/android/libwebrtc.aar` + `.sha256` + `LICENSE.md`; gn args `rtc_use_h264=false rtc_include_dav1d_in_internal_decoder_factory=false rtc_opus_support_120ms_ptime=true is_debug=false symbol_level=1 target_os="android"`; Java `org.webrtc.OpusInfo` (`decoderComplexity()`, `hasDeepPlc()`, `hasOsce()`, `maxPacketMs()`).

- [ ] **Step 1: Failing test** `build_android_test.sh` (Linux, no WebRTC checkout): reads `VERSION`; rejects an unknown ABI; prints the exact gn args for each ABI (arm64 without dotprod flags); `0005`/`0006` apply cleanly with `git apply --check --3way` to a fixture tree; `package-android.sh` on a fake two-ABI layout produces an AAR whose `jni/` has exactly `arm64-v8a` and `x86_64`.
- [ ] **Step 2:** run → FAIL. **Step 3: Implement** (reuses `apply.sh` for patches 0001–0002; 0003–0004 are Objective-C and not applied). **Step 4:** PASS.
- [ ] **Step 5: Commit** `build(webrtc): Android build and packaging scripts, Android patches`.

### Task E2: Android WebRTC CI, release, `:webrtc` module

**Files:** Create `.github/workflows/webrtc-android.yml`, `apps/android/webrtc/build.gradle.kts` (download by URL, verify SHA-256, expose the AAR), `apps/android/webrtc/webrtc.properties` (`url`, `sha256`, `version`), instrumented `apps/android/webrtc/src/androidTest/kotlin/.../OpusInfoTest.kt`.

- [ ] **Step 1: Failing test** `OpusInfoTest`: `decoderComplexity() == 7`, `hasDeepPlc()`, `hasOsce()`, `maxPacketMs()` is 120 (or 60 with a recorded `Ruling:`); `PeerConnectionFactory` lists H264 in `HardwareVideoDecoderFactory` on API 36; `RtpSender.parameters.encodings[0].maxBitrateBps` can be set.
- [ ] **Step 2:** Workflow jobs: `build` (matrix `arm64-v8a`, `x86_64`; `ubuntu-24.04`; frees disk first; `timeout-minutes: 350`), `package` (AAR, license check fails on `GNU General Public License`, `GNU Lesser General Public License`, `GNU Affero`; `scripts/check-16kb.sh`), `smoke` (emulator API 29 x86_64 runs `OpusInfoTest`), `release` (tag `webrtc-android-m<milestone>.<n>`, on `workflow_dispatch` only). Triggers: `workflow_dispatch` and pushes to `feat/webrtc-android` only.
- [ ] **Step 3:** Run the workflow by hand; read failures with `gh run view --log-failed`; fix until green.
- [ ] **Step 4:** Publish the first release; write its URL and hash into `webrtc.properties`; `./gradlew :webrtc:pixel6Api29DebugAndroidTest` → PASS.
- [ ] **Step 5: Commit** `build(webrtc): Android AAR CI, release and Gradle module`.

### Task E3: `:calls` policy logic

**Files:** `apps/android/calls/src/main/kotlin/org/example/app/calls/policy/{AudioProfile,SdpMunger,VideoLadder,QualityAdaptor,ReconnectPolicy,RelayDecision}.kt`; tests per file.

**Interfaces — Produces:** the plan 8 lane P types in Kotlin: `AudioProfile.table` (UltraLow 10000/60/0/1/16000/21000; DataSaver 16000/40/1/1/16000/30000; Balanced 24000/20/1/1/48000/51000; High 40000/20/1/1/48000/75000); `SdpMunger.local(sdp, ceiling)`, `remote(sdp, sendMode)`; `VideoLadder` tiers p360/p270/p180 with plan 8's bps; `QualityAdaptor.step(sample: MediaStats): AdaptorOutput`; `ReconnectPolicy` (2.5 s, 5 s, 30 s); `RelayDecision.policy(setting, isContact): IceTransportPolicy`.

- [ ] **Step 1: Failing tests:** the plan 8 P1–P3 cases with the same SDP fixtures (copied from `apps/apple/Packages/CallCore/Tests`), including `mungesOpusFmtpPerMode`, `keepsOtherCodecsUntouched`, `degradesVideoThenAudio`, `recoversAfterFiveGoodSamples`, `restartAfter2500ms`, `givesUpAt30s`, `nonContactsRelaysStrangersOnly`; plus `acceptsIosSdpFixtures` (SDPs recorded from the Apple build, from `apps/apple/Packages/CallCore/Tests` resources if present, else two fixtures recorded in I2).
- [ ] **Step 2:** `./gradlew :calls:test` → FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-calls): audio profiles, SDP munger, adaptor, reconnect, relay`.

### Task E4: `:calls` session machine (owner review: protocol state machine)

**Files:** `.../calls/session/{CallMachine,CallEvent,CallEffect,Fingerprint}.kt`; tests `OutgoingCallTest`, `IncomingCallTest`, `MidCallTest`.

**Interfaces — Produces:** `class CallMachine(sealer: CallSignalSealer, clock)`; `fun handle(event: CallEvent): List<CallEffect>`; effects include `SendFrame(type, data)`, `SetRemote(sdp)`, `CreateOffer`, `RestartIce`, `End(reason)`.

- [ ] **Step 1: Failing tests:** `calleeMakesOffer`; `callerBuffersSignalsPerDevice` (keeps only the answering device's buffer); `signalFromOtherDeviceDropped`; `fingerprintMismatchAbortsCall` (no `SetRemote`, ends `failed`, sends `call.hangup`); `fingerprintCompareNormalisesCase`; `renegotiateKeepsFingerprint`; `glareCallerWins`; `calleeAsksRestartWithEmptyRenegotiate`; `answeredElsewhereCloses`; `callIdMismatchDropped`; `statusEndedAfterPushEnds`.
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-calls): call session machine`.

### Task E5: WebRTC media session and RNNoise (after E2)

**Files:** `apps/android/calls-android/src/main/kotlin/org/example/app/calls/android/media/{WebRtcMediaSession,StatsMapper,AudioSetup}.kt`, `src/main/cpp/{rnnoise/*,rnnoise_jni.c,CMakeLists.txt}`; JVM test `StatsMapperTest`; instrumented `MediaSessionTest`.

**Interfaces — Produces:** `class WebRtcMediaSession(context, settings) : MediaSession` (the plan 8 `MediaSession` protocol in Kotlin, declared in `:calls`); field trial `WebRTC-Audio-OpusGeneratePlc/Enabled/`; hardware AEC/NS off; noise setting mapping from design §8.2; `StatsMapper.bytesUsed(stats) = bytesSent + bytesReceived + 28 × (packetsSent + packetsReceived)`.

- [ ] **Step 1: Failing tests:** `bytesUsedAddsHeaders`; `codecOrderPrefersH264Baseline`; instrumented `loopbackAudioConnects` (two sessions in one process connect over host candidates within 5 s on the emulator); `relayPolicyGathersOnlyRelay` (no `host` or `srflx` candidate emitted with `RELAY`); `enhancedRunsRnnoise` (the hook is called with 480-sample frames).
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS. The owner records CPU use of Standard and Enhanced in a 5-minute call on the OnePlus 5 (`docs/measurements/android-noise-cpu.md`).
- [ ] **Step 5: Commit** `feat(android-calls): WebRTC media session, stats, RNNoise`.

### Task E6: Telecom, call notifications, `LiveCallEngine` (owner review)

**Files:** `.../calls/android/telecom/{AppConnectionService,AppConnection,PhoneAccounts,TelecomCallController,PlainCallController}.kt`, `.../calls/android/{CallService,CallNotifications,IncomingCallActivity,LiveCallEngine}.kt`, manifest entries in `calls-android/src/main/AndroidManifest.xml` (`MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE_PHONE_CALL`, `_MICROPHONE`, `_CAMERA`, `USE_FULL_SCREEN_INTENT`, the service with `android.telecom.ConnectionService` and `BIND_TELECOM_CONNECTION_SERVICE`); Robolectric `CallNotificationsTest`, instrumented `TelecomSmokeTest`.

**Interfaces — Produces:** `interface IncomingCallSink { fun ring(outcome: RingOutcome.Ring) }` (F4 calls it); `LiveCallEngine : CallEngine`; `PhoneAccount` with `CAPABILITY_SELF_MANAGED | CAPABILITY_VIDEO_CALLING`.

- [ ] **Step 1: Failing tests:** `incomingUsesCallStyleWithFullScreen` (channel `calls`, category `CATEGORY_CALL`, Answer and Decline open activities, no broadcast trampoline); `fallsBackWithoutFullScreenPermission` (heads-up only); `serviceTypesBeforeAndAfterAnswer` (`phoneCall` while ringing; adds `microphone` after answer, `camera` with video); `telecomFailureUsesPlainController`; `recordsHistoryOnce`; instrumented `selfManagedIncomingCallShows` (API 29 and 36).
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-calls): Telecom, call notifications, live call engine`.

---

## Lane F — Push

### Task F1: Gateway FCM sender (starts now; owner review: push gateway)

**Files:** Modify `server/internal/pushgw/` (handle kind `fcm`, `PUSHGW_FCM_PROJECT_ID`, `PUSHGW_FCM_KEY_FILE`, `PUSHGW_FCM_URL` for tests, `PUSHGW_ANDROID_APP_IDS`), `server/internal/pushproto/` if the handle type lives there; create `server/internal/pushgw/fcm.go`, `fcm_test.go`; modify `docs/protocol/push.md` and server design §7.4 (the `fcm` kind, the size rule).

**Interfaces — Produces:** handle `{"t":"<FCM token>","k":"fcm","e":"production","b":"<applicationId>"}`; kind rules: `message` → `alert` or `fcm`; `call` → `voip`, `alert` or `fcm`; FCM v1 body from design §9.2 with `android.priority = "HIGH"`, `ttl = "45s"` for calls and `"86400s"` for messages; OAuth token: RS256 JWT (`iss` = service account email, `scope` = `https://www.googleapis.com/auth/firebase.messaging`, `aud` = token URI), cached until 5 minutes before expiry; `e` must be `production`; FCM `UNREGISTERED` (or a `404` with status `NOT_FOUND`) becomes `410`, a bare or unknown `404` stays an error.

- [ ] **Step 1: Failing tests** (fake FCM and fake token endpoint): `fcmMessageBody` (exact JSON, `d` standard base64 of the padded bytes); `fcmOversizeBecomesEmpty` (a 3072-byte padded payload goes out as the 256-byte empty one; no request body over 4096 bytes); `fcmCallTtl45s`; `unregisteredBecomes410`; `fcmBundleMustBeListed`; `tokenCachedAndRefreshed`; `fcmKindRulesEnforced`.
- [ ] **Step 2:** `go test ./internal/pushgw/...` → FAIL. **Step 3: Implement** (standard library only; the key file is read at start and never logged). **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(pushgw): FCM data messages for Android handles`.

### Task F2: Server push kinds and UnifiedPush sender (owner review: push)

**Files:** Modify `server/internal/push/module.go` (kinds `fcm`, `unifiedpush`; send order), create `server/internal/push/webpush.go` (RFC 8291 `aes128gcm`, RFC 8292 VAPID), `server/internal/push/ssrf.go`, tests; modify `server/internal/serverinfo/` (`push.vapidKey`), `server/internal/config/` (`APP_VAPID_KEY_FILE`), `appserver keygen vapid`; update server design §7.4/§7.5 and `docs/protocol/push.md`.

**Interfaces — Produces:** `PUT /v1/push/tokens/fcm {sealed}`; `PUT /v1/push/tokens/unifiedpush {endpoint, p256dh, auth}` (endpoint at most 1024 characters, `https://` only); `DELETE /v1/push/tokens/{kind}`; UnifiedPush body `kind byte (0x01 message, 0x02 call) ‖ padded payload`, headers `TTL` (45 or 86400), `Urgency: high`, `Content-Encoding: aes128gcm`, VAPID `Authorization: vapid t=<jwt>, k=<key>`.

- [ ] **Step 1: Failing tests:** `acceptsNewKinds`; `rejectsHttpEndpoint`; `refusesPrivateEndpoints` (10/8, 172.16/12, 192.168/16, 127/8, ::1, fc00::/7, 169.254/16 including 169.254.169.254, fe80::/10; checked on the resolved address at dial time); `noRedirects`; `webpushRoundTrip` (a Go test receiver decrypts with the RFC 8291 keys; RFC 8291 §5 example vector passes); `endpointGoneDeletes` (404/410); `callUsesTtl45`; `oneTransportPerDevice` (uploading `unifiedpush` deletes `fcm` and the reverse).
- [ ] **Step 2:** FAIL. **Step 3: Implement** (standard library: `crypto/ecdh`, `crypto/hkdf`, `crypto/aes`, `crypto/cipher`, `crypto/ecdsa`). **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(server): FCM and UnifiedPush token kinds, Web Push sender`.

### Task F3: `:push` registration

**Files:** `apps/android/push/src/main/kotlin/org/example/app/push/{PushRegistrar,FcmHandle,UnifiedPushSetup,PushTransport,FirebaseInit}.kt`, `push/src/main/AndroidManifest.xml` (`<queries>` for UnifiedPush distributors); Robolectric tests.

**Interfaces — Produces:** `enum class PushTransport { Fcm, UnifiedPush, None }`; `PushRegistrar(api, hpke, gatewayKey: ByteArray, gatewayUrl: String, appId: String).register(transport)`; `FcmHandle.plaintext(token, appId)` = `{"t":…,"k":"fcm","e":"production","b":…}`; `FirebaseInit.init(context, options from BuildConfig: FCM_PROJECT_ID, FCM_APP_ID, FCM_API_KEY, FCM_SENDER_ID)`; gateway key and URL from `BuildConfig.PUSH_GATEWAY_URL`, `PUSH_GATEWAY_KEY`.

- [ ] **Step 1: Failing tests:** `fcmHandleSealedWithGatewayKey` (opens with a test key to the exact JSON); `noTokensWhenServerNamesOtherGateway`; `switchingDeletesOtherKind`; `unifiedPushUploadsEndpointAndKeys`; `chooseFcmWhenPlayServices`; `chooseUnifiedPushWhenNoPlayAndOneDistributor`; `noneWhenNeither`.
- [ ] **Step 2:** FAIL. **Step 3: Implement.** **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-push): FCM and UnifiedPush registration`.

### Task F4: Wake handling and notifications

**Files:** `.../push/{AppFirebaseService,AppUnifiedPushReceiver,PushDispatcher,InboxFetchWorker,NotificationChannels,MessageNotifier,MissedPushMonitor}.kt`; Robolectric tests.

**Interfaces — Produces:** `PushDispatcher(handler: PushPayloadHandler, ring: IncomingCallSink)`; FCM `data["k"]` and UnifiedPush kind byte both map to `onMessage` / `onCall`; channels `messages`, `requests`, `calls`, `devices`, `service`; `InboxFetchWorker` as an expedited `CoroutineWorker`; `MissedPushMonitor` counts high-priority pushes that showed nothing and detects missed pushes for the battery hint.

- [ ] **Step 1: Failing tests:** `unpadsEverySharedVector` (via `PushPadding`); `messageOutcomeShowsNotification` (title = sender name, body = preview, channel `messages`); `requestUsesRequestsChannel`; `nothingShowsNothing`; `emptyEnqueuesExpeditedFetch`; `strangerCallDoesNotRing` (`SilentMissed` → no ring, a missed call recorded); `contactCallRings`; `unifiedPushKindByte`; `quietPushCounted`; `notificationsPermissionAskedAfterSignUp` (API 33+).
- [ ] **Step 2:** FAIL. **Step 3: Implement** (decryption runs under `MlsGate`; nothing longer than 8 s runs in the receiver; the rest goes to `InboxFetchWorker`). **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(android-push): wake handling, channels, notifications`.

---

## Lane G — CI

### Task G1: Toolchain setup and workflow skeleton (starts now)

**Files:** Create `scripts/android-setup.sh` (JDK 17, Android cmdline-tools, `platforms;android-36`, `build-tools`, `ndk;<r28+>`, `emulator`, system images `android-29;default;x86_64` and `android-36;aosp_atd;x86_64`, `cargo-ndk`; installs under `~/android`, prints `export` lines), `scripts/check-16kb.sh`, `scripts/android-setup_test.sh`, `.github/workflows/android.yml` (`changes` job only for now); modify `.github/CODEOWNERS` (Android owner-review paths from Global Constraints, owner handle in the same form as the existing lines).

- [ ] **Step 1: Failing test** `android-setup_test.sh`: `--dry-run` prints every package above; `check-16kb.sh` passes a fixture `.so` with 16 KB `LOAD` alignment and fails one with 4 KB (fixtures built with `-Wl,-z,max-page-size=…` by the test itself).
- [ ] **Step 2:** run → FAIL. **Step 3: Implement;** `changes` outputs one flag per module from path filters, with dependents (`contracts` → everything; `core` → `app`; `net` → `app`, `push`; `mls` → `messaging`, `app`; …). **Step 4:** PASS; `actionlint` if installed.
- [ ] **Step 5: Commit** `ci(android): toolchain setup, 16 KB check, change detection`.

### Task G2: JVM tests, lint, build, Rust cross-build jobs

**Files:** Modify `.github/workflows/android.yml`.

- [ ] **Step 1:** Add jobs on `ubuntu-24.04`, each gated by `changes`: `jvm` (`./gradlew test` for the changed JVM modules), `robolectric` (`:app:testDebugUnitTest` and the Android modules' unit tests), `lint` (`./gradlew lint`, `HardcodedText` and `SetTextI18n` as errors), `assemble` (`assembleDebug`), `mlsffi-android` (`scripts/build-mlsffi-android.sh` with the NDK; binding drift check; uploads `jniLibs` as an artifact consumed by `assemble` and emulator jobs).
- [ ] **Step 2:** Push the lane branch; `gh run watch`; fix until green.
- [ ] **Step 3: Commit** `ci(android): JVM tests, lint, build, Rust cross-build`.

### Task G3: Emulator tests on KVM

**Files:** Modify `apps/android/build.gradle.kts` only through lane A (ask for the `managedDevices` block: `pixel6Api29` = `aosp` API 29 x86_64, `pixel6Api36` = `aosp-atd` API 36 x86_64, or `aosp` if no ATD image exists for 36, with a `Ruling:`), `.github/workflows/android.yml`.

- [ ] **Step 1:** Add the `emulator` job: enable KVM (`/etc/udev/rules.d/99-kvm4all.rules` with `KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"`, reload udev), cache the system images, run `./gradlew <module>:pixel6Api29DebugAndroidTest <module>:pixel6Api36DebugAndroidTest` for changed modules with instrumented tests, `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`, `timeout-minutes: 60`.
- [ ] **Step 2:** Push; `gh run watch`; fix until green.
- [ ] **Step 3: Commit** `ci(android): instrumented tests on KVM emulators`.

### Task G4: Release bundle, licenses, Play documents

**Files:** Modify `.github/workflows/android.yml` (`release-bundle` job on `main` and dispatch); create `docs/release/android-play.md` (owner's release steps: Play Console, Play App Signing, upload key made and kept by the owner, FGS and full-screen-intent declarations, the account-deletion web page), `docs/release/android-data-safety.md` (draft answers for the data safety form, marked for owner and legal review).

- [ ] **Step 1:** `release-bundle`: `./gradlew bundleRelease` unsigned; `scripts/check-16kb.sh` on every `.so` in the bundle; the Gradle dependency license report checked against the approved list (fails on an unlisted library or a GPL/LGPL/AGPL license, JNA counted as Apache-2.0); `gitleaks` over `apps/android`.
- [ ] **Step 2:** Write the two documents; `bash scripts/check-docs.sh docs/release` passes.
- [ ] **Step 3:** Push; green run.
- [ ] **Step 4: Commit** `ci(android): release bundle checks, Play release and data safety drafts`.

---

## Lane I — Integration

### Task I1: Live wiring

**Files:** Modify `apps/android/app/src/main/kotlin/org/example/app/AppGraph.kt`, `MainActivity.kt`, `app/build.gradle.kts` (lane A hands over `:app` here); test `AppGraphTest`, instrumented `LiveSmokeTest`.

- [ ] **Step 1: Failing tests:** `sampleDataSelectsSampleServices`; `liveGraphBuildsWithoutNetwork`; `revokedWipesEverything` (database, `mls/`, `media/`, `secrets/`, Keystore alias); `socketOpenOnlyWhileVisibleOrInCall`; `LiveSmokeTest` against a Go server on the host (`10.0.2.2`, plain HTTP debug-only constructor): sign up by email code, add a contact, send a text, receive it on a second emulator user profile.
- [ ] **Step 2:** FAIL. **Step 3: Implement;** `sampleData` defaults to `false` from here. **Step 4:** PASS (the `LiveSmokeTest` job starts the server from `server/` on the runner).
- [ ] **Step 5: Commit** `feat(android-app): live services wired`.

### Task I2: Interop with iPhone and Mac, device checklist

**Files:** Create `apps/android/interop/` (JVM command-line peer using `:net`, `:messaging`, `:mls` with the host library), `apps/apple/Interop/` (Swift command-line peer using ServerKit and Messaging), `.github/workflows/interop.yml` (`macos-26`, `main` and `workflow_dispatch` only), `docs/release/android-device-checklist.md`.

- [ ] **Step 1: Failing interop script** `scripts/interop.sh`: starts Postgres and the Go server on the runner, then: Kotlin signs up A, Swift signs up B; each adds the other; Kotlin → Swift text, Swift → Kotlin reply with a reaction and an edit; delivered and read receipts both ways; a photo attachment both ways; server-readable mode: the escrow copy from each side opens with `appserver escrow export`; safety numbers equal on both sides; call signaling: each side seals a `ring` and an `offer` with a recorded SDP and the other opens it and passes the fingerprint check; Android approves a Swift link request and the reverse (snapshot imported). Every step prints `PASS <step>`.
- [ ] **Step 2:** run → FAIL. **Step 3: Implement** both peers. **Step 4:** green on `macos-26`.
- [ ] **Step 5:** Write the device checklist for the owner (OnePlus 5 and an iPhone): audio call both directions in each data mode; video call; mode change mid-call; Wi-Fi ↔ mobile switch; Bluetooth headset; ring when the app is killed (FCM) and with UnifiedPush; a stranger's call does not ring; push while locked; one hour idle battery (target ≤ 2 % per hour, GOAL §9); cold start ≤ 2 s; 10 000-message chat scrolls smoothly. Record SDP fixtures from both builds for E3.
- [ ] **Step 6: Commit** `test(interop): Android and Apple peers through one server, device checklist`.

---

## Not in this plan

- Group chats and group calls (Phase 2), encrypted backups and the recovery key (Phase 2).
- Tablets, foldables, Wear OS and Android Auto layouts beyond what Telecom gives for free.
- A `foss` flavor without Firebase (owner to decide).
- Phone-number discovery (after the beta).
