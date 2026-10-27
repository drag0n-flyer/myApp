# Android Client Design

Date: 2026-10-28
Status: draft for owner review
Plan: [Android client plan](../plans/2026-10-28-android-client.md)
Related: [GOAL.md](../../../GOAL.md), [UI design](2026-10-27-app-ui-design.md), [server design](2026-10-27-server-design.md), [protocol spec](../../protocol/README.md), [roadmap](../plans/README.md)

## 1. Scope and decisions

The Android app is a second native client of the same Go server. It has the same features as the Phase 1 iPhone app: 1:1 chats over MLS, attachments, 1:1 audio and video calls with the low-data modes, device linking, push, the server-readable mode, blocking and reporting. No group UI, as on iPhone.

**Owner decisions (2026-10-28, roadmap log):**

- Android starts now, in parallel with Phase 1 of the iPhone and Mac app.
- Kotlin and Jetpack Compose.
- Encryption reuses the Rust layer `mlsffi` (`apps/apple/Packages/SecureMessaging/mlsffi`) through UniFFI Kotlin bindings. UI, storage, networking and calls are native Kotlin.
- Minimum Android 10 (API 29). The owner's OnePlus 5 (Android 10, Snapdragon 835, 2017) is a reference device: speed and battery must be fine on it.
- Push: FCM data messages through the project push gateway, plus UnifiedPush for direct server push.

**How to read the marks.** "(planner's choice)" marks a choice the author of this document made where no owner decision or spec exists. "Owner to decide" marks a question only the owner can settle. Section 17 collects both.

**The contract.** Android follows the [protocol spec](../../protocol/README.md) and [server design §7](2026-10-27-server-design.md) byte for byte. Compatibility with the iPhone and Mac app is proven two ways: the shared test vectors in `docs/protocol/test-vectors/`, which Android tests read directly, and an interop test where an Android peer and an Apple peer exchange messages and calls through one server (plan Task I2).

## 2. Architecture

```text
            ┌──────────────────────── :app (Compose UI, AppGraph wiring) ───────────────────────┐
            │  screens ← view models ← :core interfaces (AccountService, ChatStore, CallEngine)  │
            └───────────────┬───────────────┬──────────────────┬────────────────┬───────────────┘
                            │               │                  │                │
                 :net + :auth          :messaging + :store   :calls + :calls-android   :push
                 (HTTP, WebSocket,     (envelope, inbox,     (session machine, SDP,    (FCM, UnifiedPush,
                  session, passkeys,    outbox, escrow,       adaptor; WebRTC,          registrar, wake
                  linking)              attachments, Room)    Telecom, notifications)   handling)
                            │               │                  │                │
                            └──────── :contracts (cross-lane interfaces and wire types) ────────┘
                                            │
                                  :mls (UniFFI bindings over mlsffi, HPKE)
```

- **Layers.** Pure Kotlin/JVM modules hold all decision logic and are tested on the JVM in seconds: `:contracts`, `:core`, `:net`, `:messaging`, `:calls`. Android library modules hold only platform adapters: `:auth`, `:mls`, `:store`, `:calls-android`, `:push`. This mirrors the Apple split (AppCore, ServerKit, Messaging and CallCore test on Linux; platform adapters test on macOS).
- **Live and sample services.** As on iPhone, `:core` defines `AccountService`, `ChatStore` and `CallEngine`, with sample implementations. The UI is built first on sample data (plan lane A). The live services replace them later without screen changes. The launch extra `sampleData=true` keeps selecting the sample services for UI tests.
- **Wiring.** Manual constructor injection in one `AppGraph` object in `:app` (planner's choice). No Hilt or Dagger: fewer dependencies, faster builds, nothing hidden.
- **Concurrency.** Kotlin coroutines and `Flow`. One `MlsGate` (a coroutine `Mutex`) serialises every call that changes MLS state and the database together, the Android form of the iPhone `ProcessLock`.
- **One process** (planner's choice). Android has no notification extension: push handlers run in the app process. No `android:process` split, so no cross-process lock is needed. The MLS engine still re-reads its state from disk on every call (that is how `mlsffi` works).

## 3. Gradle modules

All under `apps/android/` (planner's choice of path, next to `apps/apple/`). Kotlin package root and placeholder application ID: `org.example.app` (the same placeholder as the iOS bundle ID; owner to decide the final ID, which can never change after the first Play release).

| Module | Kind | Holds | Plan lane |
|---|---|---|---|
| `:contracts` | JVM | Interfaces and wire types shared across lanes: `MlsEngine`, `Hpke`, `SecretStore`, `DeliveryApi`, `ServerEvent`, `InboxItem`, `CallSignalPayload`, `DataMode`, `PushPayloadHandler` | A (written once in A1) |
| `:core` | JVM | Port of AppCore: models, `AccountService`, `ChatStore`, `CallEngine`, policies, onboarding flow, sample services | A |
| `:app` | Android app | Compose UI, view models, resources, `AppGraph` | A, then I1 |
| `:net` | JVM | `ServerClient` (OkHttp), endpoints, `LiveConnection` (WebSocket), `LiveAccountService`, linking | B |
| `:auth` | Android library | `KeystoreSecretStore`, Credential Manager passkeys, `BiometricGate` | B |
| `:mls` | Android library | Generated UniFFI Kotlin, `jniLibs`, `LiveMlsEngine`, `RustHpke` | C |
| `:messaging` | JVM | Content envelope, inbox processor, outbox, escrow, attachments cipher, device sync, safety numbers, `LiveChatStore`, `NotificationProcessor` | D |
| `:store` | Android library | Room database on SQLCipher, DAOs, the `Repository` implementation | D |
| `:calls` | JVM | Port of CallCore: audio profiles, SDP munger, video ladder, quality adaptor, reconnect policy, relay decision, call session machine | E |
| `:calls-android` | Android library | `WebRtcMediaSession`, RNNoise JNI, Telecom `ConnectionService`, call notifications, `CallService`, `LiveCallEngine` | E |
| `:webrtc` | Android library | The project's prebuilt `libwebrtc.aar`, fetched by checksum | E |
| `:push` | Android library | FCM service, UnifiedPush receiver, `PushRegistrar`, wake handling | F |

Build settings (planner's choice): Kotlin 2.x with the Compose compiler plugin, JDK 17 toolchain, `compileSdk = 36`, `targetSdk = 36` (Google Play requires a recent target for new apps and updates), `minSdk = 29`, version catalog `gradle/libs.versions.toml`, Gradle wrapper committed. Release builds use R8.

## 4. Local storage

**Choice: Room on SQLCipher** (planner's choice; the dependency needs owner approval). The database is `messages.db` in the app's credential-encrypted data directory, opened through SQLCipher's `SupportOpenHelperFactory` with a random 32-byte key.

Why SQLCipher rather than Android Keystore-backed file encryption:

1. **The database itself must be encrypted** (GOAL §5, "Encrypt local databases"). File-based encryption (FBE) is mandatory only on devices that *launched* with Android 10 or later. The OnePlus 5 launched on Android 7 and upgraded, so it may still use full-disk encryption, which protects nothing once the phone has booted. SQLCipher encrypts every page, the WAL file and the full-text index, whatever the device does.
2. **Search needs SQL.** GOAL §4 wants search across messages. Encrypting individual fields with a Keystore key (the alternative) would make the FTS index impossible or leak it in plaintext. SQLCipher keeps the iPhone design: an FTS5 `trigram` table over folded text (plan 7 Design 13).
3. **Jetpack Security (`EncryptedFile`) is deprecated**, and wrapping every row by hand is code the owner would have to review as cryptography.
4. **Cost on the OnePlus 5** is small: SQLCipher uses AES-256 in CBC with HMAC per page, and the Snapdragon 835 has ARMv8 AES instructions. Measured in plan Task D3 (`bulkInsert10k` timing on the reference device, by the owner).

Costs accepted: one native library (about 3 to 4 MB per ABI before compression) and the dependency.

**Key handling.**

- The 32-byte database key is random, made once, and stored wrapped by `SecretStore` (section 5) under the name `db.key`.
- The key is not bound to user authentication, because push handling must open the database while the phone is locked (the Android form of iOS's "complete until first unlock").
- The database lives in credential-encrypted storage, which on FBE devices is readable only after the first unlock. The app is not direct-boot aware (planner's choice): before the first unlock after a reboot, FCM holds messages until the user unlocks.

**Other files.**

| Data | Where | Protection |
|---|---|---|
| Database (`messages.db`, `-wal`, `-shm`) | `Context.getDatabasePath` | SQLCipher |
| MLS state (`mlsffi` `FileStorage`) | `noBackupFilesDir/mls/` | Credential-encrypted storage only. `mlsffi` writes its files in plain form, as on iPhone (where iOS data protection covers them). Owner to decide whether `mlsffi` should encrypt them on Android; that is a change to the reviewed Rust layer. |
| Attachments (encrypted blobs and decrypted cache) | `noBackupFilesDir/media/` | Downloaded blobs stay encrypted with their attachment key; decrypted files are written only to `cacheDir/open/` while shown and deleted after |
| Secrets (`db.key`, `session`, `mls.signing`) | `noBackupFilesDir/secrets/` | Wrapped by a Keystore key (section 5) |

Backups: `android:allowBackup="false"` and `dataExtractionRules` that exclude everything (planner's choice), so no message, key or token reaches Google's cloud backup or a device-to-device transfer. History backup is Phase 2 (GOAL §5).

## 5. Keys and the Android Keystore

- **One wrapping key** in the Android Keystore, alias `app.secrets.v1`: AES-256-GCM, `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, random nonces required, not exportable (Keystore keys never leave secure hardware).
- **StrongBox where available**: `setIsStrongBoxBacked(true)` first. On `StrongBoxUnavailableException` (the OnePlus 5 has no StrongBox), the key is made in the TEE instead. The choice is recorded and logged once as `keystore=strongbox|tee` (no other detail).
- Not auth-bound, and `setUnlockedDeviceRequired(false)`, for the push reason in section 4.
- **`SecretStore`** (`:auth`, `KeystoreSecretStore`) wraps each secret as `nonce (12) ‖ ciphertext ‖ tag (16)`, with AAD = the UTF-8 secret name, in `noBackupFilesDir/secrets/<name>`. The AAD stops one wrapped secret being swapped for another. Unwrapped values are cached in memory for the process lifetime: StrongBox operations can take hundreds of milliseconds.
- **Secrets stored:** `session` (JSON `{host, accountID, deviceID, token}`), `db.key`, `mls.signing` (the Ed25519 key pair from `mlsffi.generateSignatureKeypair()`). The MLS signing key cannot live inside the Keystore: Ed25519 Keystore keys need API 33, and `mls-rs` needs the raw key.
- **Link key pair:** a fresh X25519 pair per link attempt, made by `Hpke.generateKeyPair()`, held in memory only and dropped after the claim or expiry ([device linking](../../protocol/device-linking.md)).
- Wiping (own device revoked, account deleted): delete the database, `mls/`, `media/`, `secrets/`, and the Keystore alias.

## 6. Networking

**Choice: OkHttp** for HTTP and the WebSocket (planner's choice), with kotlinx.serialization for JSON. Ktor's Android client would sit on OkHttp anyway and adds its own dependency tree. OkHttp gives HTTP/2, connection reuse, the WebSocket with per-message compression (server design decision 3), and `MockWebServer` for tests.

- **`ServerClient`** mirrors plan 6: base URL `https://<host>`, `Authorization: Bearer <token>` on authenticated calls, the error envelope mapped to `ServerException(status, code, retryAfter, details)`, transport failures as `status = 0, code = "network"`, and one sign-out on `401` however many calls fail at once.
- **JSON** follows the protocol conventions: binary fields base64url without padding (decode accepts padding), Go's RFC 3339 times with up to 9 fractional digits, unknown fields ignored.
- **`LiveConnection`** is `/v1/ws` with the plan 6 rules: backoff 1 s × 2ⁿ, capped at 60 s, full jitter, reset after 30 s up; close code 4001 stops without reconnecting, 4003 stops and reports revoked; a client ping every 25 s, reconnect when no pong within 10 s; client frame IDs `c1`, `c2`, …; 10 s wait for `ack` or `error`.
- **When the socket is open** (planner's choice, same rule as iPhone): only while the app has a visible activity or an active call (`CallService` running). In the background, delivery uses push plus inbox sync. A permanent background socket would cost battery and break under Doze.
- **Server info.** The client fetches `GET /v1/server` when a server is added and at each start. A `protocol` above 1 stops all traffic and shows "Update [App] to use this server" ([versioning](../../protocol/versioning.md)).
- **Network type** (Wi-Fi or mobile data, for data modes and auto-download): `ConnectivityManager.NetworkCallback` and `NetworkCapabilities.TRANSPORT_WIFI` / `NET_CAPABILITY_NOT_METERED`. A metered Wi-Fi counts as mobile data (planner's choice).

## 7. MLS through UniFFI Kotlin

- **Same crate, same pin.** `:mls` uses `mlsffi` from `apps/apple/Packages/SecureMessaging/mlsffi` without moving it (planner's choice: moving it would disturb the iPhone build; owner to decide whether it moves to a neutral `crates/mlsffi` later).
- **Bindings.** `uniffi-bindgen generate --library <libmlsffi.so> --language kotlin --config apps/android/mls/uniffi.toml` writes `org/example/app/mls/ffi/mlsffi.kt`. `uniffi.toml` sets `package_name = "org.example.app.mls.ffi"` and `cdylib_name = "mlsffi"`. The generated file is committed and CI fails on drift, as on iPhone.
- **UniFFI Kotlin loads the library through JNA.** That adds JNA (`net.java.dev.jna:jna`, `@aar`), which needs owner approval.
- **Cross-compilation** with `cargo-ndk` (a build tool): Rust targets `aarch64-linux-android` and `x86_64-linux-android`, `--platform 29`, release profile, output in `apps/android/mls/src/main/jniLibs/<abi>/libmlsffi.so` (not committed; CI and `scripts/build-mlsffi-android.sh` build it).
- **ABIs** (planner's choice): `arm64-v8a` for devices and `x86_64` for emulators. `armeabi-v7a` is left out: every Android 10 phone with 64-bit hardware runs arm64 code, Play needs 64-bit anyway, and 32-bit-only Android 10 devices are low-end Go devices that would struggle with Deep PLC. Owner to decide if 32-bit phones matter.
- **16 KB pages.** Android 15 devices can use 16 KB memory pages, and Play requires 16 KB-aligned native libraries for new apps. NDK r28 and later align by default. CI checks every `.so` in the APK (`scripts/check-16kb.sh`, plan Task G4) and fails on a 4 KB segment alignment.
- **JVM tests use a host build.** `cargo build --release` on Linux makes `libmlsffi.so` for the host, and the JVM tests of `:mls` load it with `jna.library.path`. The real MLS engine is therefore tested on every push without an emulator.
- **`LiveMlsEngine`** implements `:contracts` `MlsEngine` (the Kotlin form of the roadmap's `MLSEngine`), maps `FfiException` cases one-to-one to `MlsException`, and runs each call on `Dispatchers.IO`. Identity is `"<accountID>/<deviceID>"`. The storage directory is `noBackupFilesDir/mls`.
- **`memberKeys`** (safety numbers, device lines) needs plan 4's `member_keys` export. If plan 4 M7 has not landed when Android needs it, plan Task C1 adds it to `mlsffi` (one owner: whichever lands first; the other records a `Ruling:`).
- **HPKE** (escrow `escrow-v1`, push handle `push-handle-v1`, link `link-v1`). Android 10 has no X25519 in the platform crypto (it arrived in API 33). Planner's choice: three small exports in `mlsffi` that call `mls-rs-crypto-rustcrypto`'s HPKE for cipher suite `CURVE25519_CHACHA` (`0x0003`), whose HPKE is exactly the protocol suite (DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305): `hpke_seal(recipient_public_key, info, plaintext)`, `hpke_open(private_key, public_key, info, sealed)` and `hpke_generate_keypair()`. Output is `enc ‖ ct` with empty AAD. No new dependency; the code is a call into the reviewed library, not cryptography of our own. The alternative is Google Tink on the Kotlin side (a new dependency). Owner to decide; owner review either way (cryptography).
- Other primitives come from the platform: SHA-256 (`MessageDigest`), AES-256-GCM for attachments (`Cipher "AES/GCM/NoPadding"`), `SecureRandom`.

## 8. Calls

### 8.1 WebRTC build

- **Same source, same Opus.** The Android build uses the pin in `tools/webrtc/VERSION` (today M156, `branch-heads/8078`) and the same `apply.sh`, so Opus 1.6 with Deep PLC, OSCE (LACE, NoLACE), BWE and decoder complexity 7 comes from patches `0001` and `0002`, which are C/C++ and platform-neutral.
- **Built on Linux** (`ubuntu-24.04` runners), which is the only supported host for Android WebRTC builds and costs nothing extra in macOS minutes. `tools/webrtc/build-android.sh` runs `fetch --nohooks webrtc_android`, syncs with `--no-history`, runs `apply.sh`, applies the Android patches, and builds with `tools_webrtc/android/build_aar.py`.
- **gn arguments** as on Apple: `rtc_use_h264=false` (no software H.264), `rtc_include_dav1d_in_internal_decoder_factory=false`, `rtc_opus_support_120ms_ptime=true`, `is_debug=false`, `symbol_level=1`.
- **CPU features.** arm64: NEON only, no dot-product. The Snapdragon 835 is ARMv8.0 without dot-product instructions, the same reasoning as the iPhone XS on iOS. x86_64 (emulators): Opus's runtime-dispatched SIMD.
- **Android patches**, owner review:
  - `0005-android-opus-info.patch`: Java class `org.webrtc.OpusInfo` with `static native int decoderComplexity()`, `static native boolean hasDeepPlc()`, `static native boolean hasOsce()`, `static native int maxPacketMs()`. It is the Android form of `RTCOpusInfo`, so a smoke test proves the neural features are compiled into the AAR.
  - `0006-android-capture-post-processing.patch`: a writable capture hook for RNNoise, after echo cancellation (`webrtc::CustomProcessing` passed to `AudioProcessingBuilder().SetCapturePostProcessing`), exposed as Java `org.webrtc.AudioCapturePostProcessing` with a native-pointer factory so audio frames never cross JNI into Java. If the pinned branch already has an equivalent (for example an `ExternalAudioProcessingFactory`), the task uses it and records a `Ruling:` instead.
- **Output:** one `libwebrtc.aar` with `arm64-v8a` and `x86_64`, released as a GitHub release asset tagged `webrtc-android-m<milestone>.<n>`, with a `.sha256`. The `:webrtc` module downloads it by URL and checks the hash (the Android form of `WebRTCBinary`). The license file from `generate_licenses.py` ships with it; the build fails if it names a GNU license.
- **Field trial.** `PeerConnectionFactory.initialize(...setFieldTrials("WebRTC-Audio-OpusGeneratePlc/Enabled/"))`, the name from `FIELD_TRIAL_OPUS_PLC` in `VERSION`. Without it Deep PLC is built in but never used.
- **Security cadence:** the Android AAR is rebuilt whenever the Apple build moves to a new pin (plan 3's `UPDATING.md`).

### 8.2 Media

- **Same rules as iPhone**, ported to `:calls`: the `AudioProfile` table (ptime 60/40/20/20, `maxaveragebitrate` 10000/16000/24000/40000), SDP munging of both local and remote descriptions, the video ladder (`p360`, `p270`, `p180`), the quality adaptor (bad sample, 5 good samples to step up, degradation order), and the reconnect policy (restart ICE after 2.5 s, retry every 5 s, give up at 30 s). All values come from [plan 8](../plans/2026-10-27-client-calling.md) and [calls](../../protocol/calls.md).
- **Echo cancellation and noise suppression on Android** (planner's choice). Android has no equivalent of Apple's voice-processing unit, and the hardware echo cancellers that phones expose behave differently from maker to maker. So the app turns hardware AEC and NS off (`JavaAudioDeviceModule.builder(...).setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)`) and uses WebRTC's own AEC3 and noise suppression:

| Setting | Android behaviour |
|---|---|
| Off | AEC3 on, WebRTC noise suppression off |
| Standard (default) | AEC3 and WebRTC noise suppression |
| Enhanced | AEC3, WebRTC noise suppression off, RNNoise (48 kHz, 480-sample frames) in the capture hook |

  There is no Voice Isolation hint on Android. Whether Enhanced is cheap enough on the OnePlus 5 is measured on the device (plan Task E5); if it is not, Enhanced is hidden on devices below a CPU threshold (owner to decide the threshold after the measurement).
- **Video:** H.264 through `HardwareVideoEncoderFactory` and `HardwareVideoDecoderFactory` (MediaCodec), constrained baseline first in the codec list for compatibility with iPhone's VideoToolbox. VP8 in software is the fallback when a phone has no hardware H.264 encoder (emulators, some old phones); both sides support it. Camera through `Camera2Enumerator`.
- **Data used:** `bytesUsed = bytesSent + bytesReceived + 28 × (packetsSent + packetsReceived)` from transport stats, as on iPhone.
- **IP hiding:** `iceTransportPolicy = RELAY` when the user's setting requires it for this peer ([calls, relay policy](../../protocol/calls.md)); `continualGatheringPolicy = GATHER_CONTINUALLY`.
- **Network change mid-call:** `ConnectivityManager` default-network callback; a change of network or transport restarts ICE.
- **Thermal and battery:** `PowerManager.getCurrentThermalStatus()` (API 29) at `THERMAL_STATUS_SEVERE` or above caps video at `p180`, and battery saver mode starts video off (planner's choice; GOAL §8).

### 8.3 System call UI: Telecom

- **Self-managed `ConnectionService`** (planner's choice), with a `PhoneAccount` that has `CAPABILITY_SELF_MANAGED` (permission `MANAGE_OWN_CALLS`, a normal permission).
  - It gives the system call integration Android has for VoIP apps: Bluetooth and car audio routing, headset buttons, Wear OS and Android Auto, and the right behaviour when a mobile-network call comes in (hold or end).
  - A *managed* phone account (shown in the system dialer) was rejected: the user would have to enable it in system settings, and calls would appear in the system call log, the leak that plan 8 avoids on iPhone with `includesCallsInRecents = false`. Self-managed calls are kept out of the call log.
- **Incoming call:** `TelecomManager.addNewIncomingCall` → `onCreateIncomingConnection` → `Connection.onShowIncomingCallUi` posts a `NotificationCompat.CallStyle.forIncomingCall` notification on channel `calls`, with a full-screen intent to `IncomingCallActivity` (the Android form of the CallKit screen) and Answer and Decline actions that open activities directly (notification trampolines are banned since Android 12).
- **Outgoing call:** `TelecomManager.placeCall` with the account's `PhoneAccountHandle`; `CallStyle.forOngoingCall` while the call runs.
- **Audio routes:** `Connection.requestCallEndpointChange` on API 34 and later, `Connection.setAudioRoute` on API 29 to 33. The call screen's speaker button and the Bluetooth chooser go through Telecom.
- **Fallback** when Telecom refuses the call (some makers' builds; `onCreateIncomingConnectionFailed`, or a `SecurityException`): the same notification and activity without Telecom, with audio routing done by `AudioManager` in `MODE_IN_COMMUNICATION` (planner's choice).
- **Foreground service** `CallService` while a call rings or runs: type `phoneCall` while ringing, and `phoneCall|microphone` (plus `camera` with video) after the user answers from a visible activity. This keeps the process and the WebSocket alive under Doze and the cached-app freezer.
- **Full-screen intent permission.** On Android 14 and later, `USE_FULL_SCREEN_INTENT` is granted by default only to calling and alarm apps, and the user can revoke it. The app checks `NotificationManager.canUseFullScreenIntent()`; when false, ringing is a heads-up notification, and Settings → Notifications shows a row that opens the system page.
- **Strangers' calls do not ring** (UI design §6.10): no Telecom call, a missed call in the message request.
- **Call history** stays local per device, as on iPhone.

## 9. Push

### 9.1 Two transports

| | FCM through the gateway | UnifiedPush |
|---|---|---|
| Needs | Google Play services | A distributor app (for example ntfy, or an embedded FCM distributor) |
| Path | server → project gateway → FCM → device | server → the distributor's push server → distributor app → our app |
| Default | yes, when Play services are present | chosen in Settings → Notifications, or automatically when Play services are missing and one distributor is installed (planner's choice) |
| The server sees | a sealed handle only | the endpoint URL (the user chose that distributor) |

A device uses one transport at a time; the registrar deletes the other kind on the server when it switches.

### 9.2 FCM through the gateway

- **Sealed handle.** The device seals `{"t":"<FCM registration token>","k":"fcm","e":"production","b":"<applicationId>"}` with HPKE `push-handle-v1` to the compiled-in gateway key, exactly as the iPhone app does ([push](../../protocol/push.md)). `t` is the FCM token as text (it is not hex). Upload: `PUT /v1/push/tokens/fcm {"sealed":"<b64url>"}`.
- **Protocol change.** The gateway, the server's token kinds and the push spec gain `k = "fcm"`. It is additive (`protocol` stays 1), but it touches the push gateway, so the owner reviews it. Server: `PushMessage` uses `alert` or `fcm`; `PushCall` uses `voip`, then `fcm`, then `alert`.
- **Gateway to FCM.** FCM HTTP v1, `POST https://fcm.googleapis.com/v1/projects/<PUSHGW_FCM_PROJECT_ID>/messages:send`, with an OAuth access token from the service-account key (an RS256 JWT exchanged at Google's token endpoint, standard library only). Body:

  ```json
  {"message":{"token":"<t>","data":{"k":"message","d":"<std base64 of the padded payload>"},"android":{"priority":"HIGH","ttl":"86400s"}}}
  ```

  For a call, `"k":"call"` and `"ttl":"45s"` (the ring timeout; planner's choice). FCM `UNREGISTERED` or `404` becomes `410` to the server, which deletes the handle.
- **Size.** FCM data messages are limited to 4096 bytes. The 3072-byte bucket is 4096 characters in base64, so with the keys it does not fit. **The gateway replaces a 3072-byte padded payload with the 256-byte empty one for FCM handles** (length 0 means "fetch the inbox"). The gateway can do this without reading the payload: the padded length is visible to it already. The 2048 bucket (2732 characters) fits.
- **The key** is the owner's: the FCM service-account file (`PUSHGW_FCM_KEY_FILE`) is held by the owner only, like the APNs key. The Firebase project and its `FirebaseOptions` values (project ID, application ID, API key, sender ID) are created by the owner; the app reads them from Gradle properties at build time and initialises Firebase by hand (no `google-services` plugin; planner's choice).

### 9.3 UnifiedPush

- **Registration.** The UnifiedPush connector library registers with the user's distributor and returns an endpoint URL plus Web Push keys (`p256dh`, `auth`). The app uploads `PUT /v1/push/tokens/unifiedpush {"endpoint":"https://…","p256dh":"<b64url>","auth":"<b64url>"}`. This is not sealed: the server must know where to send.
- **Server sends directly** (GOAL §6, "direct server push"), with no gateway: an HTTP `POST` to the endpoint, body encrypted with RFC 8291 (`aes128gcm`), VAPID (RFC 8292) with the server's key `APP_VAPID_KEY_FILE`, headers `TTL` (45 for calls, 86400 for messages), `Urgency: high`. The body is one kind byte (`0x01` message, `0x02` call) followed by the padded payload (planner's choice). 3073 bytes plus the RFC 8291 overhead fits the 4096-byte Web Push limit.
- **Server safety.** The server POSTs to a URL a client gave it, so: `https` only, port 443 only, no redirects, and the resolved address must not be private, loopback, link-local or a cloud-metadata address (the same block list as TURN), checked at connect time. Responses `404` and `410` delete the endpoint. Owner review (push).
- **`/v1/server`** gains `push.vapidKey` (base64url P-256 public key) so the connector can register with it. Additive.

### 9.4 How each push wakes the app

| Event | FCM high priority | UnifiedPush |
|---|---|---|
| Delivery while in Doze | Delivered at once; FCM high priority is exempt from Doze | Depends on the distributor app keeping its own connection (it must itself be exempt from battery optimisation) |
| How our code runs | `FirebaseMessagingService.onMessageReceived` | The connector's `MessagingReceiver` broadcast (`goAsync`) |
| Time budget | About 10 seconds is safe; longer work goes to an expedited `WorkManager` job | About 10 seconds (broadcast); same expedited job |
| Network | Allowed during the temporary allowlist | May be blocked while in Doze; the payload in the push is enough to decrypt and show a message |
| Can start a foreground service | Yes (high-priority FCM is an exemption on Android 12+) | Not guaranteed; ringing uses the notification and Telecom, and `CallService` starts when the user answers |

**Message push:** unpad → if empty, enqueue an expedited inbox fetch and show the generic notification ("[App]", "New message") only if the fetch cannot finish in time → otherwise `NotificationProcessor` decrypts under `MlsGate` and returns what to show: the contact's name and a preview, "Message request", or nothing (muted, blocked, receipt, edit, delete, reaction). Android needs no filtering entitlement to show nothing.

**Call push:** unpad → open the ring → `call.status` over the socket when it connects within the budget → `addNewIncomingCall`. A ring that cannot be opened is dropped silently: unlike iOS PushKit, Android has no rule that forces a call UI for every call push.

**High-priority quota.** FCM may lower the priority of an app's high-priority messages when they do not lead to a visible notification. Receipts, edits, deletes and reactions are opaque to the server, so today each one would arrive as a high-priority push that shows nothing. Owner to decide: let the sender mark such messages as quiet (`"quiet": true` on `POST /v1/conversations/{id}/messages`, an additive field), so the server sends them at normal priority (FCM) or without an alert (APNs). That reveals to the server which messages are quiet. Until decided, the Android app counts high-priority pushes that showed nothing, and the beta measures whether delivery degrades (plan Task F4).

**Without any push.** No Play services and no distributor: no background notifications. Settings → Notifications says so and links to UnifiedPush help. No permanent background socket (planner's choice; GOAL §8). Owner to decide whether an opt-in "keep connected" foreground service is wanted.

**Maker battery killers.** Some makers (OnePlus among them) force-stop apps in the background, and a force-stopped app receives no FCM until it is opened. The app does not ask for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play restricts it). When the app notices it missed pushes (inbox items older than their push would have been), it shows a one-time hint that opens the system battery page (planner's choice; owner to decide whether to request the exemption instead).

## 10. Background limits, Android 10 to 16

| Version | What changes for this app | Design response |
|---|---|---|
| 10 (API 29) | No activity starts from the background | Incoming calls use a full-screen-intent notification |
| 10 | Doze and App Standby buckets (since 6 and 9) | High-priority FCM for messages and calls; `WorkManager` for deferred work; no polling |
| 11 | Microphone and camera in a foreground service need the `microphone`/`camera` types, while in use only; package visibility | `CallService` types; `<queries>` for UnifiedPush distributors |
| 12 | No foreground-service start from the background (exemptions include high-priority FCM); no notification trampolines; `restricted` standby bucket | FGS only from FCM high priority or a visible activity; notification actions open activities |
| 13 | `POST_NOTIFICATIONS` runtime permission | Asked after sign-up with a one-line reason first (UI design §6.7 step 8) |
| 14 | Foreground-service types and their permissions required; `USE_FULL_SCREEN_INTENT` limited; cached apps frozen | `FOREGROUND_SERVICE_PHONE_CALL`, `_MICROPHONE`, `_CAMERA`; `canUseFullScreenIntent()` check |
| 15 | 16 KB pages; edge-to-edge enforced for target 35+; `BOOT_COMPLETED` cannot start `phoneCall`/`microphone` services | 16 KB-aligned `.so` files; insets handled in every screen; nothing starts at boot |
| 16 | Target 36: predictive back, large-screen orientation limits ignored | Compose back handling; layouts work in any orientation |

Recurring work, all through `WorkManager` (dependency): KeyPackage refill check every 6 hours with a network constraint ([MLS profile](../../protocol/mls-profile.md)); outbox retry with backoff; inbox fetch after a push with an empty payload (expedited). Nothing else runs in the background.

## 11. Accounts and passkeys

- **Android can create accounts** (planner's choice). The iPhone design makes the iPhone the primary device and the Mac link-only; an Android user usually has no iPhone. Owner to decide.
- **Passkeys through Credential Manager** (`androidx.credentials`, with `credentials-play-services-auth` for Android 10 to 13). `CreatePublicKeyCredentialRequest` and `GetPublicKeyCredentialOption` take the WebAuthn JSON from `POST /v1/signup/passkey/options` and `POST /v1/signin/passkey/options` unchanged, and return the JSON that `finish` expects.
- **Which servers.** A passkey works only when `https://<host>/.well-known/assetlinks.json` lists the app's package and signing-certificate SHA-256 for `delegate_permission/common.get_login_creds`. Unlike iOS, where the app must list each domain at build time, any server can publish that file. So the app supports passkeys on every server whose assetlinks file names it (checked with one GET before offering a passkey), not only the project's servers (planner's choice; owner to confirm, because it differs from the iPhone rule in the roadmap).
- **Server changes** (owner review: authentication):
  - `validDevice` accepts platform `android`.
  - `GET /.well-known/assetlinks.json` from a new setting `APP_ANDROID_APPS` (`<package>:<cert SHA-256 hex>` list), with `get_login_creds` and `handle_all_urls` (so `/invite/*` and `/link/*` open the app as verified App Links).
  - WebAuthn origins gain `android:apk-key-hash:<base64url of the cert SHA-256>` for each listed app.
- **Fallback.** Android 10 phones without Play services, without a screen lock or without a passkey provider make Credential Manager throw (`CreateCredentialNoCreateOptionException`, `CreateCredentialUnsupportedException`, `NoCredentialException`). The app then finishes sign-up without a credential and signs in by email code, exactly as iPhone does on servers without passkeys.
- **Cross-plan change for Apple:** AppCore `DevicePlatform` gains `android`, and ServerKit maps `"android"` to it. Today an unknown platform shows as a Mac on the iPhone's linked-devices screen and approval screen.

## 12. Device linking

Android follows [device linking](../../protocol/device-linking.md) on both sides.

- **As the new device:** `POST /v1/link {deviceName, platform: "android"}`, a fresh X25519 pair, the QR `https://<host>/link/<linkID>#<secret>.<public key b64url>`, claim polling every 2 s until approved, expired (5 min) or cancelled, then `Hpke.open` of `sealed` with `link-v1`. A `sealed` that is missing or does not open imports nothing.
- **As the approver:** scan (CameraX and ZXing; planner's choice of an open-source decoder over the closed ML Kit), refuse a host other than its own (`wrongServer`, nothing sent), `POST /v1/link/{linkID}/info`, the approval screen with name, type and request time and the plain warning, then `BiometricPrompt` with `BIOMETRIC_STRONG` (platform API, no AndroidX biometric library; planner's choice). No strong biometric enrolled: `biometricsUnavailable`, nothing sent.
- **Snapshot.** The JSON `LinkSnapshot` of plan 7 (`v`, `contacts`, `chats`, `folders`, `blocked`, `settings`). Its `settings` values are opaque Swift-encoded bytes today. Android imports only the setting keys whose JSON value format is written down; until a shared table exists it ignores them (cross-plan open point).
- The device's own MLS key is made on the device; membership arrives by Welcome from a device that already belongs ([MLS profile](../../protocol/mls-profile.md)).

## 13. UI on Material 3

The rule from AGENTS.md carries over: **platform system components only.** On Android that means Material 3 Compose components (`androidx.compose.material3`) and the system's look, not a copy of the iPhone's.

| iPhone (UI design) | Android (Material 3) |
|---|---|
| Glass tab bar: Contacts, Calls, Chats, Settings; Chats at launch | `NavigationBar` with the same four `NavigationBarItem`s in the same order; Chats selected at launch |
| Search button (iOS 26) / search field (iOS 17–25) | `SearchBar` at the top of Chats and Contacts (global: chats, messages, contacts) |
| Large title, ••• and ✎ buttons | `LargeTopAppBar` with an overflow `IconButton` + `DropdownMenu`; new chat is a `FloatingActionButton` (Android convention) |
| Folder chips | `FilterChip` row in a `LazyRow` |
| Status strip | One-line `Surface` under the app bar: connection dot and the data mode text |
| Chat rows | `ListItem` with avatar, preview, time and `Badge` |
| Message requests row | `ListItem` at the top of the list |
| Chat header pill and call buttons | `TopAppBar` with back `navigationIcon`, avatar and name as a clickable title (opens chat info), audio and video `IconButton`s |
| Bubbles | `Surface` with `RoundedCornerShape` (18 dp, smaller on the tail side); own messages `primaryContainer`, others `surfaceContainerHigh` |
| Composer | `TextField` with attach `IconButton` (opens a `ModalBottomSheet`: photo, camera, file), emoji, and mic that turns into send |
| Data sheet | `ModalBottomSheet` with `RadioButton` rows; disabled rows show "Limited by your server" |
| Call screen | Full-screen dark surface; `FilledIconButton`/`FilledTonalIconButton` controls (Speaker, Mute, Video, Data, End in `error` colour); status pills as `Surface` |
| Weak-network banners | `Card` at the top of the call screen, same texts |
| CallKit incoming screen | `IncomingCallActivity` from the full-screen intent, plus the `CallStyle` notification |
| Settings lists, toggles | `ListItem` rows in `LazyColumn` with section headers, `Switch`, radio choices in a dialog or sub-screen |
| Onboarding | `Scaffold` screens with `Button`/`OutlinedButton`; the 6-digit code in an `OutlinedTextField` with `KeyboardType.NumberPassword` |
| Linked devices, QR | `ListItem` rows; the QR as an `Image` generated with ZXing; the camera preview from CameraX |
| SF Symbols | Material Symbols, imported as vector drawables (Apache-2.0, listed on the licenses page) rather than the large `material-icons-extended` library (planner's choice) |
| Liquid Glass and materials | Material 3 tonal surfaces; no blur, no hand-made effects |
| System blue accent | Dynamic colour (Material You) on Android 12+, a baseline Material 3 scheme from a blue seed on 10 and 11 (planner's choice; brand colours wait for the brand) |
| Dark mode, call screens always dark | `isSystemInDarkTheme()`; call screens force the dark scheme |

Navigation: `navigation-compose` with type-safe routes; edge-to-edge with `enableEdgeToEdge()`; predictive back. Phones first; tablets and foldables get the same layouts in Phase 1 (planner's choice).

**Product rules that carry over unchanged:** no encryption labels except the chat info page; plain words, never "codec", "kbps", "relay", "MLS" or "E2EE"; strangers in Message requests; low-data media rules; the data-mode limits order; the same strings as the iPhone where the meaning is the same.

## 14. Strings, the app name, accessibility

- **Strings.** Every UI string is a resource in `apps/android/app/src/main/res/values/strings.xml` (plurals in `<plurals>`). Lint `HardcodedText` and `SetTextI18n` are errors. Kotlin code in `:core` and other JVM modules returns typed values or string resource IDs passed in from `:app`, never user text (planner's choice).
- **The app name** is the placeholder `[App]`, in exactly one resource, `<string name="app_name">[App]</string>`, and one constant `AppBrand.NAME = "[App]"` in `:core` for non-UI uses. Other strings take it as an argument (`Update %1$s to use this server`). A unit test fails if any other string contains `[App]` literally.
- **Forbidden words.** A unit test reads every `values*/strings.xml` and fails on the words `codec`, `kbps`, `relay`, `MLS`, `E2EE` (case-insensitive, whole words). A second test fails if a string resource named `encryption_*` is referenced from any Kotlin file other than `ChatInfoScreen.kt`.
- **TalkBack.** Every `IconButton`, `Icon` used as a button and custom clickable has a `contentDescription` from a string resource. Rows use `Modifier.semantics(mergeDescendants = true)`. Touch targets are at least 48 dp (Material 3 default). Live regions announce call banners (`LiveRegionMode.Polite`).
- **Text size and languages.** Material 3 type scale only, so font scale works; tested at 2.0. Layout uses `start`/`end`; `android:supportsRtl="true"`; tests in an RTL pseudo-locale and with Arabic and Hindi sample names; pseudo-locales `en-XA` and `ar-XB` turned on for debug builds.

## 15. Testing

| Level | What | Where it runs |
|---|---|---|
| JVM unit tests (JUnit 4, kotlin-test, coroutines-test) | All logic in `:core`, `:net`, `:messaging`, `:calls`, `:contracts`; `:mls` against the host-built `libmlsffi.so`; the shared protocol vectors | Locally on Linux and on `ubuntu-24.04` runners |
| `MockWebServer` | Every endpoint and the WebSocket in `:net` | JVM |
| Robolectric | Compose screens on sample data, notification building, `PushRegistrar`, string rules | JVM, no emulator |
| Instrumented tests | `:store` (SQLCipher is a native library), `:auth` Keystore, `:mls` on the device ABI, Telecom and `CallService` smoke tests, a short UI smoke test | Gradle Managed Devices on `ubuntu-24.04` with KVM: API 29 (`aosp`) and API 36 (`aosp-atd`) |
| Interop | An Android peer and an Apple peer exchange messages, receipts, edits, an attachment, an escrow copy and call signaling through one Go server | macOS runner, on `main` and by hand only (plan Task I2) |
| Owner's devices | OnePlus 5 and an iPhone: calls both ways, push while killed, Bluetooth, network switch, battery over an hour | Manual checklist (plan Task I2) |

Shared vectors read directly from `docs/protocol/test-vectors/`: `push-padding.json`, `request-signature.json` (string building only; signing is server-side), `sealed-handle.json`, `escrow.json`, `turn-credential.json`, plus the new `content-v1.json`, `safety-v1.json`, `attachment-stream.json` and `link-v1.json` (plan Task D1). A vector file with `"version"` other than 1 fails the test.

**Interop beyond vectors.** Message interop runs the Kotlin JVM modules (`:net`, `:messaging`, `:mls` with the host library) as a command-line peer on the macOS runner, next to a Swift peer and the Go server. Media interop between WebRTC on Android and iPhone cannot run automatically on hosted runners (Apple-silicon runners cannot run the Android emulator, and two runners cannot reach each other). It runs on the owner's devices, plus automated SDP fixture tests: SDPs recorded from the Apple build go through Android's munger and fingerprint checks, and the reverse (owner to decide whether a self-hosted runner should automate it later).

## 16. CI cost and release

**CI.** Every Android job runs on Linux (`ubuntu-24.04`): Gradle builds and tests, lint, Rust cross-builds, the WebRTC Android build and the emulator tests (KVM). Linux minutes are free on the public repo and do not compete with the ~5 macOS slots. Only the interop job uses a Mac, and only on `main` or by hand. `android.yml` has a `changes` job like `apple.yml`, so a change in one module tests only that module and its dependents; emulator tests run only when a module with instrumented tests changed.

**Release** (owner's steps, not agents'):

- Google Play, as an Android App Bundle. Play App Signing: Google holds the app signing key; the **upload key is made and held by the owner only**. CI builds an unsigned release bundle; agents never see a keystore, its passwords or the Firebase service-account file.
- **Data safety form** (owner fills it; plan Task G4 writes a draft answer sheet in `docs/release/android-data-safety.md`): account data (email, username, display name), device and push identifiers, and messages, calls and attachments. End-to-end encrypted content may fall under Play's exemptions, but server-readable conversations do not. Owner to decide with legal review.
- **Account deletion:** in the app (Settings), plus the web page that Play requires for deletion requests (owner to decide where it lives for self-hosted servers).
- Play Console declarations: foreground-service types (`phoneCall`, `microphone`, `camera`) and full-screen intent use.
- The open-source licenses page lists every shipped library (plan 9's license check gains the Gradle dependency report).

## 17. Choices and open questions

### Planner's choices

1. Module layout and paths in section 3; manual DI; one process.
2. Room on SQLCipher, with a Keystore-wrapped key (section 4).
3. One Keystore wrapping key, StrongBox first, not auth-bound (section 5).
4. OkHttp and kotlinx.serialization (section 6); socket only while visible or in a call.
5. HPKE through three new `mlsffi` exports instead of a Kotlin crypto library (section 7).
6. ABIs `arm64-v8a` and `x86_64`; no `armeabi-v7a`.
7. WebRTC's AEC3 and noise suppression instead of hardware effects; Enhanced = RNNoise (section 8.2).
8. Self-managed Telecom `ConnectionService` with a fallback (section 8.3).
9. FCM payload over 2048 bytes replaced by the empty payload at the gateway; call TTL 45 s; message TTL 1 day.
10. UnifiedPush body format (kind byte + padded payload); automatic choice when Play services are missing.
11. Firebase initialised by hand, no `google-services` plugin.
12. Passkeys on any server that lists the app in assetlinks; Android can create accounts.
13. CameraX + ZXing for QR codes; platform `BiometricPrompt`.
14. Material Symbols as vector drawables; dynamic colour.
15. Gradle Managed Devices for emulator tests.

### Owner to decide

1. The final application ID (permanent after the first Play release).
2. Android as a primary device (sign-up on Android), not link-only.
3. Passkeys on any server with an assetlinks file, unlike the iPhone rule.
4. HPKE in `mlsffi` (no new dependency) or Google Tink.
5. Whether `mlsffi` should encrypt its state files on Android.
6. `armeabi-v7a` (32-bit phones) support.
7. A sender-set `quiet` flag for receipts, edits, deletes and reactions (FCM quota), which tells the server which messages are quiet.
8. An opt-in "keep connected" mode for phones with no push at all.
9. Asking for the battery-optimisation exemption instead of a hint.
10. A CPU threshold for hiding Enhanced noise suppression after the OnePlus 5 measurement.
11. Moving `mlsffi` to a neutral path later.
12. A `foss` build flavor without Firebase (for F-Droid), later.
13. The `ceiling` encoding in call payloads: resolved 2026-10-28: string per [calls](../../protocol/calls.md) (`"balanced"`); the Swift app was changed to match.
14. Decided (owner, 2026-10-28): voice messages are Ogg Opus (RFC 7845) on every platform. The iPhone and Mac apps record Opus in CAF and repackage the packets as Ogg before sending; Android's recorder writes Ogg directly. Rules for receivers: [protocol README, Voice attachments](../../protocol/README.md#voice-attachments).
15. The Play data safety answers and the web page for account deletion.
16. Whether a self-hosted runner should automate Android↔iPhone media tests.

### Open points for other plans

- **Plan 6 (Apple):** `DevicePlatform.android` and its wire mapping.
- **Plan 7:** a written format for `LinkSnapshot.settings` values; `content-v1.json` and `safety-v1.json` copied to `docs/protocol/test-vectors/`.
- **Plan 2 / push spec:** `k = "fcm"`, the `unifiedpush` token kind, `push.vapidKey`, the FCM size rule.
- **Plan 4:** `member_keys` (if not landed) and the HPKE exports.
- **Plan 9:** the license check covers Gradle dependencies; the protocol README lists the new vectors.
