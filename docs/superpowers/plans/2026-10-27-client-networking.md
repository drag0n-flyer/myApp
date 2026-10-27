# Client Networking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan runs as a foundation, then three parallel lanes, then one integration agent. Read "Parallel execution" first.** All coding agents run on Sonnet 5.5.

**Goal:** Connect the iPhone and Mac apps to the Go server. This plan builds the `ServerKit` Swift package (every server endpoint, the live WebSocket, session storage, passkeys and push registration) and a real `LiveAccountService` that drives onboarding, device linking, device removal and account deletion.

**Architecture:** `ServerKit` is a Swift package that depends on `AppCore`. It has a platform-neutral core: JSON coding, the HTTP pipeline, typed endpoints, the WebSocket frame codec and reconnect logic, and `LiveAccountService` over injected protocols. That core is tested with `swift test` on Linux. Apple-only adapters sit behind `#if canImport(...)` and are tested on the macOS runner: the Keychain, `ASAuthorization` passkeys, CryptoKit HPKE sealing, APNs registration, and `URLSessionWebSocketTask`. The app swaps `SampleAccountService` for `LiveAccountService`, unless it is launched with `-sampleData`.

**Tech Stack:** Swift 6 (strict concurrency), Foundation/FoundationNetworking, Security (Keychain), AuthenticationServices, CryptoKit (`HPKE`, iOS 17 / macOS 14), UserNotifications, VisionKit (`DataScannerViewController`, iPhone), and Swift Testing. No new dependencies.

**Spec:**
- [Server design](../specs/2026-10-27-server-design.md): the wire contract (§7) and push sealing (§7.4)
- [Roadmap contracts](README.md): `ServerKit` names
- [UI design](../specs/2026-10-27-app-ui-design.md) §6.8, onboarding and linking
- [GOAL.md](../../../GOAL.md) §5–7

## Design (decisions)

- **One client per signed-in server.** `ServerClient` is an actor holding the base URL `https://<host>`, a `SessionStore` and an `HTTPTransport`.
  - Authenticated calls add `Authorization: Bearer <token>`.
  - A `401` on an authenticated call runs `onUnauthorized`, which clears the session. The app then returns to the welcome screen.
- **Errors.** The server's `{"error":{"code",…}}` becomes `ServerError(status:code:retryAfter:)`. `Retry-After` is parsed as seconds. A transport failure (no connection, timeout) becomes `ServerError(status: 0, code: "network")`.
- **JSON coding.**
  - Binary fields are base64url without padding, through the `Base64URLData` wrapper type. JSON keys are written explicitly in `CodingKeys`, with no key-conversion strategy.
  - Dates are RFC 3339 with optional fractional seconds; Go writes nanoseconds.
- **The live connection** is `/v1/ws` with the bearer header.
  - Reconnect backoff is 1 s × 2ⁿ, capped at 60 s, with full jitter. The attempt count resets once a connection stays up for 30 s.
  - Close code 4001 (`replaced`) stops the connection without reconnecting. Close code 4003 (`revoked`) stops it and reports `.revoked`.
  - The client sends a ping every 25 s, and a missing pong within 10 s forces a reconnect.
  - Client frames get the ids `c1`, `c2`, …. `send` waits up to 10 s for the matching `ack` or `error`.
- **When the socket is open:**
  - iPhone: only while the app is active; offline delivery uses push and inbox sync (plan 7).
  - Mac: whenever the app is running.
- **Passkeys work only for hosts listed in the app's associated domains** (decision 1).
  - The setting `PASSKEY_DOMAINS` (default `home.example.org`) feeds both the `webcredentials:` entitlement and `PasskeyPolicy.supported(host:)`.
  - On other hosts, sign-up finishes without a passkey, and sign-in uses an email code.
- **Linking QR code:** `https://<host>/link/<linkID>#<secret>`. The scanning iPhone refuses a code whose host differs from its own server and shows `wrongServer`. The new device keeps `claimToken` in memory only and polls `claim` every 2 s until approved, expired (5 min) or cancelled.
- **Push** (decision 3):
  - Sealing:
    - The device seals `{"t","k","e","b"}` (server spec §7.4) with `HPKE.Sender(recipientKey:ciphersuite: .Curve25519_SHA256_ChachaPoly, info: "push-handle-v1")`.
    - The sealed bytes are `encapsulatedKey ‖ ciphertext`, which is the framing Go's `hpke.Seal` produces.
  - Where values come from:
    - The gateway public key and URL are compiled into the app (`PUSH_GATEWAY_URL`, `PUSH_GATEWAY_KEY`).
    - The bundle is `Bundle.main.bundleIdentifier`.
    - `e` comes from the `APNS_ENVIRONMENT` Info.plist key: `sandbox` in Debug, `production` in Release.
  - Registration:
    - The app registers alert tokens after sign-in.
    - VoIP (PushKit) registration is exposed but stays off until plan 8 supplies a CallKit-reporting handler (decision 4).
- **Session storage:** a Keychain generic password with service `org.example.app.session` in the shared access group from the README. It uses `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and `kSecAttrSynchronizable = false`, so the NSE (plan 7) can read it while the phone is locked and it never syncs to iCloud.
- **Test-only passkey:** `ServerKitTesting.SoftwarePasskey`, built with CryptoKit P-256 and a minimal CBOR encoder (the same layout as plan 2's `softauth`). It lives in the `ServerKitTesting` library, which is never linked into an app target, and a CI check enforces that. The server gets no test hook.
- **Plain-HTTP test servers** use `ServerClient(baseURL:)`. That initializer exists only under `#if DEBUG` and is marked `@_spi(Testing)`.

## Decisions I made — owner to confirm

1. **Passkeys only on the project's own servers** (hosts in the app's associated domains). Apple lets an app use passkeys only for domains in its `webcredentials` entitlement, so a self-hosted server on any other domain cannot use passkeys from the App Store app. On those servers, accounts sign up without a passkey and sign in by email code. **This needs a plan 2 change:** `POST /v1/signup/finish` must accept a missing `credential`. I rejected the alternative of one shared passkey domain for every server, because a malicious server could relay another server's sign-in challenge and take over the account.
2. **The Mac link screen shows which server it links to:** `Server: home.example.org · Change`. "Change" accepts a server address or an invite link. The Mac must know the server before it can create a link code. This is a small addition to the approved UI design.
3. **The push gateway URL and key are fixed in the app.** If a server's `/v1/server` names a different gateway, the app registers no push tokens there and the chat still works. This stops a dishonest server from collecting raw push tokens through a fake gateway.
4. **No VoIP push registration until plan 8 lands.** iOS kills an app that receives a VoIP push and doesn't report it to CallKit.
5. **`LiveAccountService` lives in `ServerKit`**, not the app target, so its logic is unit-tested on Linux against stubs.
6. **Typed throws.** `AccountService` becomes `async throws(AccountError)`, and every user-visible error text lives in the app's String Catalog (decision for AGENTS.md's "all strings in the String Catalog").
7. **`UsernameCheck` gains `.unknown`**, used when the server can't be reached; the field shows no verdict.

---

## Global Constraints

- iOS 17+, macOS 14+, Swift 6 with `SWIFT_STRICT_CONCURRENCY: complete`. No new package dependencies (AGENTS.md).
- `ServerKit` lives at `apps/apple/Packages/ServerKit`, with `swift-tools-version: 6.0` and platforms `.iOS(.v17), .macOS(.v14)`. It depends on `../AppCore`.
- Products:
  - `ServerKit` (the app links this)
  - `ServerKitTesting` (test targets only)
- Linux test command: `cd apps/apple/Packages/ServerKit && swift test --no-parallel`. Every file using Darwin-only frameworks starts with `#if canImport(<Framework>)`.
- Names, paths, endpoint fields and error codes come from the server spec §7 and README.md, word for word. Don't rename the contract types: `ServerClient`, `HTTPTransport`, `ServerError`, `InboxItem`, `ServerEvent`, `LiveConnection` and `SessionStore`.
- Never log tokens, codes, link secrets, emails or push tokens. `ServerClient` has no request logging.
- UI: Apple components, SF Symbols, system text styles, VoiceOver labels, leading/trailing layout, and all new strings in `Localizable.xcstrings`. Never show "relay", "MLS", "E2EE", "codec" or "kbps".
- **Owner review before merge:** `Sources/ServerKit/Session/`, `Sources/ServerKit/Passkeys/`, `Sources/ServerKit/Push/` and `Sources/ServerKit/Account/` (added to `.github/CODEOWNERS` in N1).
- Commits: plain messages, no `Co-Authored-By`.
- Branch: start from `main` after `feat/app-ui-sample-data` is merged. The lanes branch from `feat/client-networking`.

## Review Focus

1. **A revoked or deleted session ends cleanly everywhere.** A 401 on any authenticated call, or close code 4003, clears the Keychain session, stops reconnects, and returns the app to Welcome exactly once, even when several calls fail at the same moment. Pinned by N1 `serverClientClearsSessionOnceOn401` and L2 `revokedStopsReconnecting`.
2. **Reconnect storms.** A server that keeps dropping the socket must not cause tight reconnect loops, and the backoff must reset only after 30 s of being connected. Pinned by L2 `backoffGrowsAndCaps` and `resetsAfterStableConnection`.
3. **A link code for another server.** Scanning a QR code from a different host must never send this account's session to that host. Pinned by S1 `approveLinkRejectsOtherHost`.
4. **Wire compatibility with Go.**
   - base64url without padding, both ways
   - Go's nanosecond RFC 3339 dates
   - HPKE framing `enc ‖ ct`

   Pinned by N1 `base64URLRoundTrip`, N1 `decodesGoTimestamps`, and L3 `opensGoSealedVector`.
5. **Passkey availability per host.** A server outside `PASSKEY_DOMAINS` must never start an `ASAuthorization` request. Sign-up finishes without a credential, and Lost device offers only the email code. Pinned by S1 `signUpWithoutPasskeyOnOtherHosts`.

---

## Parallel execution

```
Stage 0  Foundation (1 agent)     N1 → N2                    branch feat/client-networking
Stage 1  Lanes (3 agents)         L1 endpoints | L2 live connection | L3 Apple platform
Stage 2  Integration (1 agent)    S1 LiveAccountService → S2 app wiring → S3 end-to-end
```

- Each lane uses its own worktree: `git worktree add ../myApp-net-<n> -b net/lane-<n> feat/client-networking`.
- Lanes own disjoint folders under `Sources/ServerKit/`:

  | Lane | Folders |
  |---|---|
  | L1 | `API/`, `Models/` |
  | L2 | `Live/` |
  | L3 | `Session/Keychain*`, `Passkeys/`, `Push/`, and `App/DesignSystem/QRScannerView.swift` |

- Merge order: L1, L2, L3. `Package.swift` is written once in N1, and lanes never edit it.
- S3 needs plan 2 (the Go server) merged on the branch. S1 and S2 do not.

---

## Stage 0 — Foundation

### Task N1: Package, coding, HTTP pipeline, errors, session store

**Files:**
- Create: `apps/apple/Packages/ServerKit/Package.swift`
  - targets `ServerKit` (depends on `AppCore`), `ServerKitTesting` (depends on `ServerKit`), `ServerKitTests`, `ServerKitAppleTests`, `ServerKitE2ETests`
  - the three test targets depend on `ServerKitTesting`
- Create: `Sources/ServerKit/Coding/Base64URL.swift`, `Sources/ServerKit/Coding/ServerJSON.swift`
- Create: `Sources/ServerKit/Transport/HTTPTransport.swift`, `Sources/ServerKit/Client/ServerClient.swift`, `Sources/ServerKit/Client/ServerError.swift`
- Create: `Sources/ServerKit/Session/SessionStore.swift`
- Create: `Sources/ServerKitTesting/StubTransport.swift`
- Create placeholder test files so every target compiles: `Tests/ServerKitAppleTests/Placeholder.swift`, `Tests/ServerKitE2ETests/Placeholder.swift`
- Create: `Tests/ServerKitTests/CodingTests.swift`, `Tests/ServerKitTests/ServerClientTests.swift`
- Modify: `.github/workflows/apple.yml`: the `core` job also runs `swift test --no-parallel` in `apps/apple/Packages/ServerKit`
- Modify: `.github/CODEOWNERS`, adding the four owner-review folders

**Interfaces — Produces:**
- `public struct Base64URLData: Codable, Hashable, Sendable { public var data: Data }`: encodes as base64url without padding, and decodes with or without padding
- `enum ServerJSON { static func encoder() -> JSONEncoder; static func decoder() -> JSONDecoder }`: dates are RFC 3339; decoding accepts 0–9 fractional digits
- `public protocol HTTPTransport: Sendable { func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) }`
- `public struct URLSessionTransport: HTTPTransport`, using `URLSession.shared` with a 30 s timeout
- `public struct ServerError: Error, Equatable, Sendable { public let status: Int; public let code: String; public let retryAfter: Int?; public let details: [String: Int]? }`
- `public enum Auth { case none, session }`
- In `ServerClient`:
  - `public init(host: String, session: SessionStore, transport: HTTPTransport = URLSessionTransport())`
  - `#if DEBUG @_spi(Testing) public init(baseURL: URL, session: SessionStore, transport: HTTPTransport)`
  - `public func setOnUnauthorized(_ f: @escaping @Sendable () async -> Void)`
  - `func request<Response: Decodable>(_ method: String, _ path: String, body: (some Encodable)?, auth: Auth) async throws(ServerError) -> Response`
  - `func requestNoContent(...) async throws(ServerError)`, for 202 and 204
  - `func requestRaw(_ method: String, _ path: String, body: Data, contentType: String, headers: [String: String], auth: Auth) async throws(ServerError) -> (Data, HTTPURLResponse)`, used by attachments
- `public struct Session: Codable, Equatable, Sendable { host, accountID, deviceID, token: String }`
- `public protocol SessionStorage: Sendable { func load() throws -> Session?; func save(_ s: Session) throws; func clear() throws }`
- `public final class SessionStore: Sendable`, with `init(storage: SessionStorage)`, `current: Session?`, `save(_:)` and `clear()`. It caches under a lock.
- `public final class MemorySessionStorage: SessionStorage`
- `ServerKitTesting.StubTransport`: an actor-backed `HTTPTransport`
  - `respond(_ method: String, _ path: String, status: Int, json: String, headers: [String: String] = [:])`
  - `fail(_ method: String, _ path: String, with error: URLError)`
  - `requests: [URLRequest]`
  - an unknown route gives `fatalError`, showing the route

- [ ] **Step 1: Failing tests.**
  - `base64URLRoundTrip`:
    - `[0xfb, 0xff]` encodes to `"-_8"`
    - `"-_8"` and `"-_8="` both decode to `[0xfb, 0xff]`
    - an empty value encodes to `""`
  - `decodesGoTimestamps`: `"2026-11-01T12:00:00.123456789Z"` and `"2026-11-01T12:00:00Z"` both decode; the first is 123 ms after the second, within 1 ms.
  - `errorEnvelopeMapsToServerError`: a 409 with body `{"error":{"code":"stale_epoch","message":"x","details":{"currentEpoch":3}}}` → `ServerError(status: 409, code: "stale_epoch", retryAfter: nil, details: ["currentEpoch": 3])`.
  - `retryAfterParsed`: a 429 with `Retry-After: 120` gives `retryAfter == 120`.
  - `transportFailureIsNetwork`: `URLError(.notConnectedToInternet)` gives `ServerError(status: 0, code: "network", …)`.
  - `bearerOnlyWhenAuthed`: `.session` with a saved session sends `Authorization: Bearer tok`; `.none` sends no header.
  - `serverClientClearsSessionOnceOn401`: 5 concurrent authenticated calls all get 401 → `onUnauthorized` ran exactly once, and `SessionStore.current == nil`.
  - `baseURLIsHTTPS`: `ServerClient(host: "chat.test", …)` requests `https://chat.test/v1/...`.
- [ ] **Step 2:** `swift test --no-parallel` → FAIL (types missing).
- [ ] **Step 3: Implement.** `onUnauthorized` is guarded by a "signed out" flag on the actor, which resets on the next `SessionStore.save`.
- [ ] **Step 4:** `swift test --no-parallel` → PASS on Linux.
- [ ] **Step 5: Commit** `feat(serverkit): package, JSON coding, HTTP pipeline, errors, session store`.

### Task N2: AccountService becomes async and throwing

**Files:**
- Modify: `apps/apple/Packages/AppCore/Sources/AppCore/Account/AccountService.swift` and `SampleAccountService.swift`
- Modify: `apps/apple/Packages/AppCore/Sources/AppCore/Account/OnboardingFlow.swift`: add `recovered()`, which goes from `.lostDevice` to `.done`
- Modify: `apps/apple/App/Features/Onboarding/*.swift` and `apps/apple/App/Features/Settings/LinkedDevicesView.swift`, `SettingsView.swift`, `apps/apple/App/AppModel.swift`, so they call the new API (still with the sample service)
- Create: `apps/apple/App/Features/Onboarding/AccountError+Text.swift`, with strings in `Localizable.xcstrings`
- Test: `apps/apple/Packages/AppCore/Tests/AppCoreTests/SampleAccountServiceTests.swift` (update)

**Interfaces — Produces** (in `AppCore`):

```swift
public enum UsernameCheck { case available, taken, invalid, unknown }   // + .unknown
public enum AccountError: Error, Equatable, Sendable {
    case inviteRequired, inviteInvalid, usernameTaken, invalidUsername, invalidEmail
    case codeInvalid, codeExpired, tooManyAttempts(retryAfterSeconds: Int?)
    case linkExpired, wrongServer, passkeyUnavailable, cancelled, network, server
}
public struct LinkCode: Equatable, Sendable { public let qrText: String; public let expiresAt: Date }
public struct DeviceInfo: Identifiable, Equatable, Sendable {
    public let id: String; public let name: String; public let platform: DevicePlatform
    public let lastActive: Date; public let isThisDevice: Bool
}
@MainActor public protocol AccountService: AnyObject {
    var defaultServer: ServerPolicy { get }
    var currentServer: ServerPolicy? { get }
    var isSignedIn: Bool { get }
    var supportsPasskeys: Bool { get }                       // for currentServer, else defaultServer
    func connect(host: String) async throws(AccountError) -> ServerPolicy
    func checkUsername(_ name: String) async -> UsernameCheck
    func redeemInvite(code: String) async throws(AccountError) -> Bool
    func startSignUp(username: String, email: String) async throws(AccountError)
    func verifyEmail(code: String) async throws(AccountError)
    func finishSignUp() async throws(AccountError)          // creates the passkey when supported
    func signInWithPasskey() async throws(AccountError)
    func sendRecoveryCode(to email: String) async throws(AccountError)
    func recover(email: String, code: String) async throws(AccountError)
    func addPasskey() async throws(AccountError)
    func makeLinkCode() async throws(AccountError) -> LinkCode
    func waitForLinkApproval() async throws(AccountError)   // returns when signed in
    func approveLink(qrText: String) async throws(AccountError)
    func devices() async throws(AccountError) -> [DeviceInfo]
    func removeDevice(id: String) async throws(AccountError)
    func deleteAccount() async throws(AccountError)
    func signOutLocally()
}
```

`SampleAccountService` keeps its sample constants: code `123456`, invite `BETA-2026`, taken names `riya` and `admin`.
- Its `linkingCode` becomes `makeLinkCode()` returning `qrText "https://home.example.org/link/sample#7Q2K9"`.
- `waitForLinkApproval()` returns at once.
- `devices()` returns `LinkedDevice.samples` mapped to `DeviceInfo`.

`AppModel` changes:
- `linkedDevices` becomes `[DeviceInfo]`, loaded by `refreshDevices()`.
- `LinkedDevice` is deleted.
- `linkSampleDevice()` and `removeDevice(id:)` call the service.

**`AccountError` text** (exact copy; `cancelled` shows nothing):

| Case | Text |
|---|---|
| `inviteRequired` | "This server needs an invite code." |
| `inviteInvalid` | "That invite code doesn't work. Check it and try again." |
| `usernameTaken` | "That username is taken." |
| `invalidUsername` | "Use 3–32 lowercase letters, numbers, or _, starting with a letter." |
| `invalidEmail` | "Enter a valid email address." |
| `codeInvalid` | "That code isn't right." |
| `codeExpired` | "That code has expired. Ask for a new one." |
| `tooManyAttempts(n)` | "Too many tries. Try again in \(minutes) min.", with minutes = ⌈n/60⌉; when `nil`: "Too many tries. Try again later." |
| `linkExpired` | "This code has expired. Start again on the new device." |
| `wrongServer` | "This code is for a different server." |
| `passkeyUnavailable` | "Passkeys aren't available on this server. Use an email code instead." |
| `network` | "Can't reach the server. Check your connection and try again." |
| `server` | "Something went wrong on the server. Try again later." |

Views show the text under the field with `.foregroundStyle(.red)`, plus `accessibilityIdentifier("onboarding.error")`.

- [ ] **Step 1: Failing tests** (`SampleAccountServiceTests`):
  - `wrongCodeThrows`: `verifyEmail(code: "000000")` throws `.codeInvalid`.
  - `rightCodePasses`.
  - `inviteRedeem`: `BETA-2026` → true; `NOPE` → throws `.inviteInvalid`.
  - `linkCodeShape`: `qrText.hasPrefix("https://home.example.org/link/")`.
  - `devicesIncludeThisDevice`: exactly one has `isThisDevice`.
  - `recoveredFinishesOnboarding`: `OnboardingFlow` goes from `.lostDevice` through `recovered()` to `.done`.
- [ ] **Step 2:** `cd apps/apple/Packages/AppCore && swift test --no-parallel` → FAIL.
- [ ] **Step 3: Implement**, then update the views to call `try await` and show `AccountError` text. `PasskeyView`'s button calls `finishSignUp()`; on `.cancelled` it stays on the screen.
- [ ] **Step 4:** AppCore `swift test` → PASS. Push, and the existing iOS and Mac UI tests in CI → PASS. The flows are unchanged with the sample service.
- [ ] **Step 5: Commit** `feat(account): async throwing AccountService, error text in String Catalog`. Stage 1 can start.

---

## Lane L1 — Endpoints (`Sources/ServerKit/API/`, `Sources/ServerKit/Models/`)

Test command: `cd apps/apple/Packages/ServerKit && swift test --no-parallel --filter API`. All tests run on Linux with `StubTransport`.

### Task L1a: Identity and server info endpoints

**Files:**
- Create: `API/ServerClient+Identity.swift`, `API/ServerClient+Server.swift`
- Create: `Models/IdentityModels.swift`, `Models/ServerInfo.swift`
- Test: `Tests/ServerKitTests/APIIdentityTests.swift`

**Interfaces — Produces** (`extension ServerClient`, all `async throws(ServerError)`):
- `serverInfo() -> ServerInfo`
- `usernameAvailable(_ name: String) -> Bool`
- `checkInvite(code: String) -> Bool`
- `signupStart(inviteCode: String?, username: String, email: String) -> String`
- `signupVerify(signupID: String, code: String)`
- `signupPasskeyOptions(signupID: String) -> CreationOptions`
- `signupFinish(signupID: String, credential: RegistrationCredential?, deviceName: String, platform: String) -> NewSession`
- `signinPasskeyOptions() -> (challengeID: String, options: RequestOptions)`
- `signinPasskeyFinish(challengeID: String, credential: AssertionCredential, deviceName: String, platform: String) -> NewSession`
- `signinEmailStart(email: String)`
- `signinEmailFinish(email: String, code: String, deviceName: String, platform: String) -> NewSession`
- `addPasskeyOptions() -> CreationOptions`, `addPasskey(_ credential: RegistrationCredential)`
- `me() -> Me`, `deleteMe()`
- `devices() -> [Device]`, `revokeDevice(id: String)`
- `linkStart(deviceName: String, platform: String) -> LinkTicket`
- `linkApprove(linkID: String, secret: String, sealed: Data?)`
- `linkClaim(linkID: String, claimToken: String) -> LinkClaim`

Models:
- `NewSession{accountID, deviceID, sessionToken}`
- `Me{accountID, username, email, displayName, findByEmail}`
- `Device{id, name, platform, createdAt, lastSeenAt, current}`
- `LinkTicket{linkID, secret, claimToken, expiresAt}`
- `enum LinkClaim { case pending; case approved(NewSession, sealed: Data?) }`: 202 means `.pending`; 200 means `.approved`
- `ServerInfo` (all spec §7.5 fields), with `func policy() -> ServerPolicy`
- `CreationOptions{challenge: Data, rpID: String, rpName: String, userID: Data, userName: String, userDisplayName: String}`, parsed from go-webauthn's `{"publicKey":{…}}` JSON (`challenge`, `rp.id`, `rp.name`, `user.id`, `user.name`, `user.displayName`)
- `RequestOptions{challenge: Data, rpID: String}`, from `{"publicKey":{"challenge","rpId"}}`
- `RegistrationCredential{id: Data, clientDataJSON: Data, attestationObject: Data}`, which encodes as `{"id":b64u,"rawId":b64u,"type":"public-key","response":{"clientDataJSON":b64u,"attestationObject":b64u}}`
- `AssertionCredential{id, clientDataJSON, authenticatorData, signature, userHandle: Data}`, which encodes as `{"id","rawId","type":"public-key","response":{"clientDataJSON","authenticatorData","signature","userHandle"}}`

- [ ] **Step 1: Failing tests.**
  - `serverInfoMapsToPolicy`: the spec §7.5 example JSON gives `ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "chat.example.org")`. Modes map as `ultraLow`→`.ultraLow` … `high`→`.high`; `"auto"`→`.auto`; IP hiding as `never`→`.never`, `nonContacts`→`.nonContacts`, `always`→`.always`. An unknown value makes the decode fail.
  - `signupStartBody`: the request body is exactly `{"inviteCode":"ABCDE-FGHJK","username":"ana","email":"ana@example.org"}`, and `inviteCode` is omitted when `nil`.
  - `signupFinishWithoutCredentialOmitsField`: when `credential` is nil, the body has no `"credential"` key.
  - `creationOptionsParse`: a captured go-webauthn options fixture gives `challenge`, `rpID == "chat.test"` and a 16-byte `userID`. Keep the fixture in `Tests/ServerKitTests/Fixtures/creation-options.json`, written by hand to match go-webauthn's field names.
  - `registrationCredentialJSON`: the encoded JSON matches the fixture byte for byte, with the keys sorted via `.sortedKeys`.
  - `linkClaimPendingThenApproved`: 202 `{}` gives `.pending`; 200 gives `.approved` with a matching `sealed`.
  - `emailStartAccepts202`.
  - `revokeDeviceUsesDelete`: `DELETE /v1/devices/d2` with the bearer token.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(serverkit): identity and server info endpoints`.

### Task L1b: Delivery, social, attachments, calls and push endpoints

**Files:**
- Create: `API/ServerClient+Delivery.swift`, `API/ServerClient+Social.swift`, `API/ServerClient+Attachments.swift`, `API/ServerClient+Calls.swift`, `API/ServerClient+Push.swift`
- Create: `Models/DeliveryModels.swift` (including the README's `InboxItem`), `Models/SocialModels.swift`, `Models/CallModels.swift`
- Test: `Tests/ServerKitTests/APIDeliveryTests.swift`, `Tests/ServerKitTests/APIOtherTests.swift`

**Interfaces — Produces** (for plans 7 and 8):
- Delivery:
  - `uploadKeyPackages(_ packages: [Data]) -> Int`, `keyPackageCount() -> Int`
  - `claimKeyPackages(accountID: String) -> [ClaimedKeyPackage{deviceID: String, keyPackage: Data}]`
  - `createConversation(with accountID: String) -> NewConversation{conversationID, mode: String, epoch: UInt64}`
  - `postCommit(conversationID: String, epoch: UInt64, commit: Data, welcome: Data?, addDevices: [String], removeDevices: [String]) -> UInt64`. On `stale_epoch` it throws `ServerError` whose `details["currentEpoch"]` is set.
  - `postMessage(conversationID: String, epoch: UInt64, ciphertext: Data, escrow: Data?, clientMsgID: String) -> Int64`
  - `inbox(after: Int64?, limit: Int = 100) -> (items: [InboxItem], more: Bool)`
  - `ackInbox(upTo: Int64)`
- Social:
  - `lookUpUser(username:) -> UserSummary{accountID, username, displayName}`
  - `discover(email:) -> UserSummary`
  - `updateSettings(displayName: String?, findByEmail: Bool?)`
  - `block(accountID:)`, `unblock(accountID:)`, `blocks() -> [String]`
  - `report(accountID: String, conversationID: String?, reason: String, messages: [ReportedMessage{sentAt: Date, text: String}], note: String?)`
- Attachments:
  - `uploadAttachment(_ data: Data) -> UploadedAttachment{id, expiresAt}`, sent as `application/octet-stream` with an explicit `Content-Length`
  - `downloadAttachment(id: String, range: ClosedRange<Int>? = nil) -> Data`
- Calls: `turnCredentials() -> TurnCredentials{iceServers: [IceServer{urls: [String], username: String, credential: String}], ttl: Int}`
- Push: `putPushToken(kind: PushTokenKind, sealed: Data)`, `deletePushToken(kind: PushTokenKind)`, with `enum PushTokenKind: String { case alert, voip }`

- [ ] **Step 1: Failing tests.**
  - `inboxItemDecodes`: the spec §7.2 example gives `payload` bytes, `seq == 1`, and `kind == "message"`; a commit item has `seq == nil`.
  - `postCommitStaleEpochDetails`: a 409 gives `error.details?["currentEpoch"] == 4`.
  - `postMessageOmitsNilEscrow`.
  - `inboxQuery`: `inbox(after: 99, limit: 100)` requests `/v1/inbox?after=99&limit=100`; `after: nil` leaves `after` out.
  - `uploadAttachmentHeaders`: the content type is `application/octet-stream`, the body is the raw bytes, and `Content-Length` equals the byte count.
  - `downloadRange`: `range: 10...19` sends `Range: bytes=10-19`, and 206 is accepted.
  - `turnDecodes`: 4 urls, `ttl == 43200`.
  - `pushTokenPath`: `PUT /v1/push/tokens/voip` with body `{"sealed":"<b64u>"}`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(serverkit): delivery, social, attachment, call and push endpoints`.

---

## Lane L2 — Live connection (`Sources/ServerKit/Live/`)

Test command: `swift test --no-parallel --filter Live` (Linux; uses a fake socket).

### Task L2a: Frames and backoff

**Files:**
- Create: `Live/Frame.swift`, `Live/Backoff.swift`, `Live/ServerEvent.swift`
- Test: `Tests/ServerKitTests/LiveFrameTests.swift`

**Interfaces — Produces:**
- The README's `ServerEvent`. Unknown frame types are ignored and decode to `nil`, not an error.
- `enum IncomingFrame { case event(ServerEvent), ack(id: String, data: Data?), error(id: String, code: String) }`
- `func decodeFrame(_ text: String) -> IncomingFrame?`
- `func encodeFrame(type: String, id: String, data: some Encodable) throws -> String`
- `struct Backoff { init(base: Double = 1, cap: Double = 60, random: @escaping @Sendable (ClosedRange<Double>) -> Double); mutating func next() -> Double; mutating func reset() }`. It returns `random(0...min(cap, base * 2^attempt))`.

- [ ] **Step 1: Failing tests.**
  - `decodesEveryEventType`: the `inbox`, `device.revoked`, `call.ring`, `call.answered`, `call.signal` and `call.ended` frames from spec §7.3 map to the matching `ServerEvent` cases, with payloads decoded from base64url.
  - `decodesAckAndError`: `{"type":"ack","id":"c3","data":{"state":"ringing"}}` gives `.ack("c3", data)`; `{"type":"error","id":"c4","data":{"code":"not_found"}}` gives `.error("c4","not_found")`.
  - `unknownTypeIgnored`: `{"type":"presence"}` → `nil`.
  - `encodesClientFrame`: `encodeFrame(type: "call.hangup", id: "c1", data: ["callID": "x"])` gives the sorted-keys JSON `{"data":{"callID":"x"},"id":"c1","type":"call.hangup"}`.
  - `backoffGrowsAndCaps`: with `random` returning the upper bound, the sequence is 1, 2, 4, 8, 16, 32, 60, 60; after `reset()` it is 1.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(serverkit): websocket frames and reconnect backoff`.

### Task L2b: LiveConnection

**Files:**
- Create: `Live/WebSocketTransport.swift`, `Live/LiveConnection.swift`
- Create: `Live/URLSessionWebSocket.swift` (`#if canImport(Darwin)`)
- Create: `Sources/ServerKitTesting/FakeWebSocket.swift`
- Test: `Tests/ServerKitTests/LiveConnectionTests.swift`

**Interfaces — Produces:**
- `public protocol WebSocketTransport: Sendable { func connect(url: URL, headers: [String: String]) async throws -> any WebSocketChannel }`
- `public protocol WebSocketChannel: Sendable { func send(_ text: String) async throws; func receive() async throws -> String; func ping() async throws; func close(code: Int) async; var closeCode: Int? { get async } }`
- `public enum ConnectionState: Equatable, Sendable { case idle, connecting, connected, waiting(seconds: Double), replaced, revoked }`
- `LiveConnection`:
  - `public init(client: ServerClient, transport: any WebSocketTransport, clock: any Clock<Duration> = ContinuousClock(), random: …)`
  - `public var events: AsyncStream<ServerEvent>`, `public var states: AsyncStream<ConnectionState>`
  - `public func start()`, `public func stop()`
  - `public func send(_ type: String, _ data: some Encodable & Sendable) async throws -> Data?`, which throws `ServerError` for an `error` frame, `ServerError(status: 0, code: "timeout")` after 10 s, and `ServerError(status: 0, code: "network")` when not connected
- `URLSessionWebSocketTransport`, which builds the request `wss://<host>/v1/ws` with the bearer header
- `FakeWebSocket`, with scripted server frames and close codes, and `sent: [String]`

- [ ] **Step 1: Failing tests** (fake socket, `TestClock` from Swift's `Clock` protocol, a deterministic `random`).
  - `deliversEvents`: a scripted `inbox` frame appears on `events`.
  - `sendWaitsForAck`: `send("call.status", …)` resolves with the ack's data; the sent frame has `id:"c1"`; the next one has `"c2"`.
  - `sendErrorThrows`: an `error` frame with `not_found` → `ServerError(code: "not_found")`.
  - `sendTimesOut`: no reply, and the clock advances 10 s → `timeout`.
  - `reconnectsWithBackoff`: an abnormal close (1006) gives `states` `.connected`, `.waiting(1)`, `.connecting`, `.connected`.
  - `resetsAfterStableConnection`: after 30 s connected and then a drop, the wait is 1 s again, not 2 s.
  - `replacedStops`: close 4001 → `.replaced`, and no reconnect.
  - `revokedStopsReconnecting`: close 4003 → `.revoked`, no reconnect, and the `ServerClient` `onUnauthorized` handler runs.
  - `pingTimeoutReconnects`: `ping()` hangs for more than 10 s → the channel is closed and the client reconnects.
  - `stopClosesCleanly`: `stop()` closes with 1000 → `.idle`, with no reconnect.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.** One receive loop task and one ping task per channel. Pending sends live in `[String: CheckedContinuation]` and fail with `network` when the channel drops.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(serverkit): live connection with acks, reconnects and revocation`.

---

## Lane L3 — Apple platform (Keychain, passkeys, push, QR scanning)

Test command: on the macOS runner, `cd apps/apple/Packages/ServerKit && swift test --filter ServerKitAppleTests`. Add that step to the `macos` job in `apple.yml`.

### Task L3a: Keychain session storage

**Files:** Create `Session/KeychainSessionStorage.swift` (`#if canImport(Security)`). Test: `Tests/ServerKitAppleTests/KeychainTests.swift`.

**Interfaces — Produces:** `public struct KeychainSessionStorage: SessionStorage { public init(service: String = "org.example.app.session", accessGroup: String?) }`, which stores one JSON-encoded `Session` with the attributes from the Design section. `accessGroup` is `nil` in package tests, where `swift test` is unsigned.

- [ ] **Step 1: Failing tests.**
  - `saveLoadClear`: a round trip; after `clear()`, `load()` returns `nil`.
  - `saveOverwrites`.
  - `attributes`: the stored item's `kSecAttrAccessible` is `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, and `kSecAttrSynchronizable` is false.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(serverkit): keychain session storage`.

### Task L3b: Passkeys

**Files:**
- Create: `Passkeys/PasskeyPolicy.swift` (cross-platform)
- Create: `Passkeys/PasskeyAuthenticator.swift` (`#if canImport(AuthenticationServices)`)
- Create: `Sources/ServerKitTesting/SoftwarePasskey.swift` (`#if canImport(CryptoKit)`)
- Test: `Tests/ServerKitTests/PasskeyPolicyTests.swift`, `Tests/ServerKitAppleTests/SoftwarePasskeyTests.swift`

**Interfaces — Produces:**
- `public protocol PasskeyAuthenticating: Sendable { func register(_ options: CreationOptions) async throws(AccountError) -> RegistrationCredential; func assert(_ options: RequestOptions) async throws(AccountError) -> AssertionCredential }`
- `public enum PasskeyPolicy { public static func supported(host: String, domains: [String]) -> Bool }`: exact, case-insensitive host match
- `@MainActor public final class PasskeyAuthenticator: NSObject, PasskeyAuthenticating`
  - It uses `ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier:)` with `createCredentialRegistrationRequest(challenge:name:userID:)` and `createCredentialAssertionRequest(challenge:)`, and `userVerificationPreference = .required`.
  - The presentation anchor is the key window.
  - `ASAuthorizationError.canceled` maps to `.cancelled`; other errors map to `.passkeyUnavailable`.
- `ServerKitTesting.SoftwarePasskey: PasskeyAuthenticating`, with `init(origin: String)`
  - `clientDataJSON` is `{"type":"webauthn.create"|"webauthn.get","challenge":b64u,"origin":origin}`, and `rpIdHash` is `SHA256(rpID)`.
  - Flags are `0x45` on create and `0x05` on get; the COSE key is `{1:2,3:-7,-1:1,-2:x,-3:y}`.
  - The attestation is `fmt "none"`.
  - The signature is `P256.Signing` over `authData ‖ SHA256(clientDataJSON)`, as `derRepresentation`.
  - `userHandle` is the `userID` it registered.

- [ ] **Step 1: Failing tests.**
  - `policyExactMatch`: `chat.test` is in `["Chat.Test"]` → true; `evil.chat.test` → false; `[]` → false.
  - `softwareCBOR`: the COSE map's first bytes are `a5 01 02 03 26 20 01 21 58 20`.
  - `softwareAssertionVerifies`: CryptoKit verifies the produced signature against the registered public key.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS (Linux: the policy test; macOS: the rest).
- [ ] **Step 5: Commit** `feat(serverkit): passkey authenticator and test-only software passkey`.

### Task L3c: Push handle sealing and registration

**Files:**
- Create: `Push/PushHandle.swift` (cross-platform), `Push/PushHandleSealer.swift` (`#if canImport(CryptoKit)`), `Push/PushRegistrar.swift` (`#if canImport(UserNotifications)`)
- Create: `tools/vectors/go.mod` (module `vectors`, Go 1.26, standard library only) and `tools/vectors/main.go`, which writes `docs/protocol/vectors/push-handle-v1.json`
- Test: `Tests/ServerKitAppleTests/PushSealTests.swift`

**Interfaces — Produces:**
- `public struct PushHandle: Codable, Equatable, Sendable { t: String; k: String; e: String; b: String }`
- `public enum PushHandleSealer { public static func seal(_ h: PushHandle, gatewayKey: Data) throws -> Data }`, which returns `encapsulatedKey ‖ ciphertext`
- `@MainActor public final class PushRegistrar`
  - `init(client: ServerClient, gatewayKey: Data, environment: String, bundleID: String)`
  - `func didRegister(alertToken: Data) async`: hex-encodes the token, seals with `k:"alert"`, and calls `putPushToken(.alert, …)`, logging no failures with the token
  - `func enableVoIP(onIncoming: @escaping @Sendable (PKPushPayload) -> Void)` (iOS only; called by plan 8)
  - `func unregisterAll() async`: deletes both kinds, used on sign-out
- `tools/vectors/main.go` uses a fixed X25519 private key of 32 bytes `0x01…0x20` and the plaintext `{"t":"00ff","k":"alert","e":"sandbox","b":"org.example.app"}`. It seals with `hpke.Seal(pub, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), []byte("push-handle-v1"), pt)` and writes `{privateKey: hex, publicKey: hex, plaintext: string, sealed: b64u}`.

- [ ] **Step 1: Failing tests.**
  - `opensGoSealedVector`: read the vector and open it with `HPKE.Recipient(privateKey:ciphersuite:info:encapsulatedKey: sealed.prefix(32))`, then `open(sealed.dropFirst(32))` → equals `plaintext`.
  - `sealRoundTrip`: `seal` with a fresh key → open with the matching recipient → the same `PushHandle`.
  - `handleJSONKeys`: the encoded keys are exactly `b`, `e`, `k`, `t`.
- [ ] **Step 2:** Run → FAIL (no vector, no sealer).
- [ ] **Step 3:** Run `cd tools/vectors && go run . ../../docs/protocol/vectors/push-handle-v1.json`, then implement the sealer and registrar.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(serverkit): push handle sealing, Go test vector, alert registration`.

### Task L3d: QR scanner view

**Files:** Create `apps/apple/App/DesignSystem/QRScannerView.swift`.

**Interfaces — Produces:**
- `struct QRScannerView: View { let onCode: (String) -> Void }`.
- iPhone: wraps `DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], isHighlightingEnabled: true)`. When `DataScannerViewController.isSupported` is false, or under `-uiTesting`, it shows a text field "Paste code" (`accessibilityIdentifier("scanner.paste")`) with a Done button.
- Mac: always the paste field.

- [ ] **Step 1:** Build in CI (`apps/apple/scripts/test-ios.sh`). This view has no unit test. S2's UI test covers the paste path.
- [ ] **Step 2: Commit** `feat(app): QR scanner with paste fallback`.

---

## Stage 2 — Integration

### Task S1: LiveAccountService

**Files:** Create `Sources/ServerKit/Account/LiveAccountService.swift`. Test: `Tests/ServerKitTests/LiveAccountServiceTests.swift` (Linux; `StubTransport` plus `FakePasskey: PasskeyAuthenticating` in the test file).

**Interfaces — Produces:**

```swift
@MainActor public final class LiveAccountService: AccountService {
    public init(defaultHost: String, passkeyDomains: [String], platform: DevicePlatform, deviceName: String,
                sessions: SessionStore, makeClient: @escaping @Sendable (String) -> ServerClient,
                passkeys: any PasskeyAuthenticating, sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) })
    public private(set) var client: ServerClient?     // for plans 7–8 once signed in
}
```

`ServerError` maps to `AccountError` as follows:

| Server code | `AccountError` |
|---|---|
| `invite_required` | `inviteRequired` |
| `invite_invalid` | `inviteInvalid` |
| `username_taken` | `usernameTaken` |
| `invalid_username` | `invalidUsername` |
| `invalid_email` | `invalidEmail` |
| `code_invalid` | `codeInvalid` |
| `expired` | `codeExpired`, or `linkExpired` inside link calls |
| `rate_limited` | `tooManyAttempts(retryAfter)` |
| `network` | `network` |
| anything else | `server` |

- [ ] **Step 1: Failing tests.**
  - `connectReadsPolicy`: `connect(host:)` → `currentServer` set from `/v1/server`.
  - `fullSignUpWithPasskey`:
    1. connect `chat.test` (in the domains)
    2. `redeemInvite`
    3. `startSignUp`
    4. `verifyEmail`
    5. `finishSignUp`

    Check: `FakePasskey.register` was called; `signupFinish` carried a credential; `SessionStore` holds the returned session; `isSignedIn`.
  - `signUpWithoutPasskeyOnOtherHosts`: host `other.example` → `finishSignUp` never calls `FakePasskey`, the body has no credential, and `supportsPasskeys == false`; `signInWithPasskey()` throws `.passkeyUnavailable` without calling the fake.
  - `inviteCarriedIntoSignUp`: the code from `redeemInvite` appears in `signupStart`'s body.
  - `codeErrorsMap`: 400 `code_invalid` → `.codeInvalid`; 429 with `Retry-After: 300` → `.tooManyAttempts(300)`.
  - `recoveryThenAddPasskey`: `sendRecoveryCode` → `recover` → signed in → `addPasskey()` calls `addPasskeyOptions` and `addPasskey`.
  - `linkNewDevicePolls`:
    - `makeLinkCode()` → `qrText == "https://chat.test/link/L1#S1"`
    - `waitForLinkApproval()` sees `.pending`, `.pending`, then `.approved`, with 2 sleeps of 2 s each, then signs in
    - when `sleep` has passed 5 minutes in total → `.linkExpired`
  - `approveLinkRejectsOtherHost`: signed in on `chat.test`, scanning `https://evil.test/link/L1#S1` → `.wrongServer`, and `StubTransport.requests` is empty.
  - `approveLinkSendsSecret`: the body has `{"secret":"S1"}`, sent to `/v1/link/L1/approve`.
  - `devicesMapAndRemove`: `current` → `isThisDevice`; `"iphone"` → `.iPhone`, `"mac"` → `.mac`; `removeDevice` calls `DELETE`.
  - `deleteAccountClearsSession`: `deleteMe` → `SessionStore.current == nil`, and `isSignedIn == false`.
  - `unauthorizedSignsOut`: any 401 → `isSignedIn == false`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.** The device name and platform come from `init`; the app passes `UIDevice.current.name`/`.iPhone` or `Host.current().localizedName ?? "Mac"`/`.mac`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(serverkit): live account service`.

### Task S2: App wiring

**Files:**
- Modify: `apps/apple/project.yml`:
  - the `ServerKit` package for both app targets (product `ServerKit` only)
  - the settings `PASSKEY_DOMAINS: home.example.org`, `DEFAULT_SERVER_HOST: home.example.org`, `PUSH_GATEWAY_URL: https://push.example.org`, `PUSH_GATEWAY_KEY: ""`, and `APNS_ENVIRONMENT`: `sandbox` for Debug, `production` for Release
  - these Info.plist keys: `DefaultServerHost`, `PasskeyDomains`, `PushGatewayURL`, `PushGatewayKey`, `APNSEnvironment`
  - `UIBackgroundModes: [remote-notification]` (iOS)
- Create: `apps/apple/App/AppiOS.entitlements` and `AppMac.entitlements`, with:
  - `com.apple.developer.associated-domains`: `webcredentials:$(PASSKEY_DOMAINS)`, `applinks:$(DEFAULT_SERVER_HOST)`
  - `keychain-access-groups`: `$(AppIdentifierPrefix)org.example.app.shared`
  - `aps-environment` (iOS) or `com.apple.developer.aps-environment` (Mac): `development`
- Modify:
  - `apps/apple/App/AppModel.swift`, `apps/apple/App/AppMain.swift` (app delegate adaptor for APNs token callbacks)
  - `apps/apple/App/Features/Onboarding/*.swift`, `apps/apple/App/Features/Settings/LinkedDevicesView.swift`, `SettingsView.swift`
  - `apps/apple/AppUITests/Shared/AppLauncher.swift`: always pass `-sampleData`
- Create: `apps/apple/scripts/check-test-only-libs.sh`, which fails if `project.yml` mentions `ServerKitTesting`. Run it in the `ios` and `macos` jobs.

**Behavior** (`AppModel`):
- `account: any AccountService`. It is `LiveAccountService` unless `LaunchOptions.sampleData` (`-sampleData`) is set, in which case it is `SampleAccountService`.
- On sign-in (after `finishSignUp`, `signInWithPasskey`, `recover` or `waitForLinkApproval` succeeds):
  - request notification authorization in the existing Permissions step
  - `registerForRemoteNotifications()`, and pass the token to `PushRegistrar`
  - when `PushGatewayKey` is empty, or when `/v1/server`'s `push.gateway` differs from `PushGatewayURL`, skip push
  - start `LiveConnection`
- Lifecycle on iPhone: `scenePhase == .active` → `start()`; `.background` → `stop()`.
- `states` reaching `.revoked`, or the `onUnauthorized` callback → `signOutLocally()`, the onboarding flow is reset, and the alert "This device was removed from your account." is shown.
- `ServerEvent.deviceRevoked` → `refreshDevices()`.

**Views:**
- Join server: "Scan invite QR code" opens `QRScannerView`. The parsed `InviteLink` → `connect(host:)` → `redeemInvite` → `choose(.invite)`. "Enter server address" → `connect(host:)`, then `choose(.address, requiresInvite: policy.requiresInvite)`.
- Link this device (Mac, and iPhone "already have an account"):
  - shows `Server: <host>` with a `Change` button (decision 2, `accessibilityIdentifier("link.server")`)
  - renders `makeLinkCode().qrText`
  - runs `waitForLinkApproval()` in `.task`, then calls `linked()`
  - on `.linkExpired`, shows the error and a "Show new code" button
  - the sample-only "I scanned it" button stays only for the sample service
- Lost device: "Sign in with passkey" is hidden when `!supportsPasskeys`. After `recover`, when `supportsPasskeys`, call `addPasskey()`; on `.cancelled`, carry on. Then `recovered()`.
- Linked devices: "Link a device" → `QRScannerView` → `approveLink(qrText:)` → `refreshDevices()`; swipe to remove → `removeDevice`.
- Delete account: confirm with "Delete your account? Your messages on this device are erased and can't be recovered.", then `deleteAccount()` and reset.

- [ ] **Step 1: Failing UI test** (`AppUITests/iOS/LinkingTests.swift`, sample mode): Settings → Linked devices → Link a device → paste `https://home.example.org/link/sample#7Q2K9` into `scanner.paste` → Done → the list shows 3 devices. Also run the existing suite with `-sampleData`.
- [ ] **Step 2:** Push → the CI iOS job FAILS on the new test.
- [ ] **Step 3: Implement** the wiring above, adding every new string to `Localizable.xcstrings`.
- [ ] **Step 4:** Push → the `core`, `ios` and `macos` jobs are green, and `check-test-only-libs.sh` passes.
- [ ] **Step 5: Commit** `feat(app): live accounts, linking, push registration, live connection lifecycle`.

### Task S3: End-to-end against the real server (macOS runner)

Needs plan 2 merged on the branch.

**Files:**
- Create: `tools/smtp-sink/go.mod` and `tools/smtp-sink/main.go`. This is a standard-library Go SMTP sink: `-listen 127.0.0.1:2525 -out DIR`. It accepts `EHLO`/`HELO`, `MAIL`, `RCPT`, `DATA` and `QUIT` without auth or TLS, and writes each message to `DIR/<n>.eml`.
- Create: `apps/apple/scripts/e2e-server.sh`
- Create: `Tests/ServerKitE2ETests/AccountFlowTests.swift`
- Modify: `.github/workflows/apple.yml`, adding an `e2e` job on `macos-26` with `actions/setup-go` (Go 1.26) and Homebrew `postgresql@18`

`e2e-server.sh` does the following in order:
1. `brew install postgresql@18`, `initdb`, `pg_ctl start`, `createdb app`
2. `go build -o "$RUNNER_TEMP/appserver" ./server/cmd/appserver`
3. start the SMTP sink
4. set the environment: `APP_HOST=localhost`, `APP_LISTEN=127.0.0.1:8080`, `APP_ADMIN_LISTEN=127.0.0.1:9090`, `APP_DATABASE_URL=postgres:///app?host=/tmp`, `APP_SMTP_URL=smtp://127.0.0.1:2525`, `APP_DATA_DIR=$RUNNER_TEMP/data`
5. `appserver migrate`, then `E2E_INVITE_CODE=$(appserver invite create | sed 's#.*/invite/##')`
6. `appserver serve &`, then poll `http://127.0.0.1:9090/readyz` for up to 30 s
7. `E2E_BASE_URL=http://127.0.0.1:8080 E2E_MAIL_DIR=… E2E_ORIGIN=https://localhost swift test --package-path apps/apple/Packages/ServerKit --filter ServerKitE2ETests`
8. on exit, kill the server and the sink, and stop Postgres

**Tests** (`@_spi(Testing) import ServerKit`). Each fails, rather than skips, when `E2E_BASE_URL` is unset. The email code is read from the newest `.eml` with the regexp `is (\d{6})\.`.
- `signUpLinkRevoke`:
  1. The iPhone client signs up with `SoftwarePasskey(origin: E2E_ORIGIN)` and `passkeyDomains: ["localhost"]` → `me().username == "ana"`.
  2. The Mac client calls `makeLinkCode()` and starts `waitForLinkApproval()` in a task.
  3. The iPhone runs `approveLink(qrText:)`; its host `localhost` matches.
  4. The Mac task finishes, and the Mac `me()` gives the same account.
  5. The Mac's `LiveConnection` reaches `.connected`.
  6. The iPhone runs `removeDevice(mac)` → the Mac `states` reach `.revoked` within 5 s, and the Mac `me()` throws `ServerError(status: 401)`.
- `recoveryAddsPasskey`: an email code to `ana@example.org` → `recover` → `addPasskey` with a new `SoftwarePasskey` → `signInWithPasskey` with it succeeds and creates a third device.
- `deleteAccountFreesUsername`: `deleteAccount()` → `usernameAvailable("ana") == true`.

Note: a server that rejects an unauthenticated plain SMTP URL is a plan 2 bug. Record it with a `Ruling:` and fix `mail.New` there.

- [ ] **Step 1:** Write the tests and the scripts.
- [ ] **Step 2:** Push → the `e2e` job runs. Expected the first time: FAIL, only where plan 2 needs decision 1 (`signupFinish` without a credential isn't exercised here, because `localhost` is in the domains). Fix any wire mismatch on the client side, unless the server contradicts its spec.
- [ ] **Step 3:** Push → `e2e` is green, and so are `core`, `ios` and `macos`.
- [ ] **Step 4: Commit** `test(e2e): sign-up, linking, revocation, recovery, deletion against the Go server`.
- [ ] **Step 5:** Do the whole-branch review (superpowers:requesting-code-review, on Opus) with this Review Focus and the ledger's `Ruling:` lines. The owner reviews the CODEOWNERS folders before merging to `main`.

## Not in this plan

- Message, conversation and attachment flows (plan 7, which consumes L1b and L2)
- Calls and VoIP push handling (plan 8, which calls `PushRegistrar.enableVoIP`)
- Contact QR codes
- Sending account data across a link in `sealed` (plan 7 decides what syncs)
- Changing a username or email
