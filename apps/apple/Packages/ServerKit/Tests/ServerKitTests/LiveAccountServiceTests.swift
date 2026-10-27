import AppCore
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@_spi(Testing) @testable import ServerKit
import ServerKitTesting

private actor FakePasskey: PasskeyAuthenticating {
    private(set) var registered = 0
    private(set) var asserted = 0
    func register(_ options: CreationOptions) async throws(AccountError) -> RegistrationCredential {
        registered += 1
        return RegistrationCredential(id: Data([1]), clientDataJSON: Data([2]), attestationObject: Data([3]))
    }
    func assert(_ options: RequestOptions) async throws(AccountError) -> AssertionCredential {
        asserted += 1
        return AssertionCredential(id: Data([1]), clientDataJSON: Data([2]), authenticatorData: Data([3]), signature: Data([4]), userHandle: Data([5]))
    }
}

private let qrKey = Data(repeating: 7, count: 32)
private let qrKeyText = Base64URLData(qrKey).encoded

private struct FakeCrypto: LinkCrypto {
    var openFails = false
    func makeKeyPair() throws(AccountError) -> (publicKey: Data, privateKey: Data) { (qrKey, Data([9])) }
    func seal(_ snapshot: Data, to publicKey: Data) throws(AccountError) -> Data { Data("sealed:".utf8) + snapshot + publicKey }
    func open(_ sealed: Data, privateKey: Data) throws(AccountError) -> Data {
        if openFails { throw .server }
        return Data("opened".utf8)
    }
}

private actor FakeBiometrics: BiometricConfirming {
    let result: AccountError?
    private(set) var reasons: [String] = []
    init(result: AccountError? = nil) { self.result = result }
    func confirm(reason: String) async throws(AccountError) {
        reasons.append(reason)
        if let result { throw result }
    }
}

private final class ClockBox: @unchecked Sendable {
    private let lock = NSLock()
    private var t = Date(timeIntervalSince1970: 1_793_534_400)   // 2026-11-01T12:00:00Z
    func now() -> Date { lock.withLock { t } }
    func advance(_ d: Duration) { lock.withLock { t += Double(d.components.seconds) } }
}

private actor SleepLog {
    private(set) var total = Duration.zero
    private(set) var count = 0
    func add(_ d: Duration) { total += d; count += 1 }
}

private let serverJSON = #"{"protocol":1,"host":"chat.test","requiresInvite":true,"policy":{"defaultMode":"auto","maxMode":"high","defaultIPHiding":"nonContacts"},"encryption":{"newConversations":"e2ee","escrowKey":null},"features":[],"federation":false,"limits":{"attachmentBytes":1}}"#
private let creationJSON = #"{"publicKey":{"challenge":"AAEC","rp":{"name":"Chat","id":"chat.test"},"user":{"name":"ana","displayName":"ana","id":"AAEC"}}}"#
private let sessionJSON = #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1"}"#
private func err(_ code: String) -> String { #"{"error":{"code":"\#(code)","message":"x"}}"# }

@MainActor private struct Rig {
    let t = StubTransport()
    let store = SessionStore(storage: MemorySessionStorage())
    let passkey = FakePasskey()
    let service: LiveAccountService
    let sleeps = SleepLog()
    let biometrics: FakeBiometrics

    init(host: String = "chat.test", signedIn: Bool = false, biometrics: FakeBiometrics = FakeBiometrics(),
         crypto: FakeCrypto = FakeCrypto(),
         onSleep: @escaping @Sendable (Int, StubTransport) async -> Void = { _, _ in }) {
        if signedIn { try? store.save(Session(host: "chat.test", accountID: "a", deviceID: "d", token: "tok")) }
        self.biometrics = biometrics
        let t = t, store = store, sleeps = sleeps
        let clock = ClockBox()
        service = LiveAccountService(
            defaultHost: host, passkeyDomains: ["chat.test"], platform: .iPhone, deviceName: "Ana's iPhone",
            sessions: store,
            makeClient: { _ in ServerClient(baseURL: URL(string: "https://chat.test")!, session: store, transport: t) },
            passkeys: passkey, biometrics: biometrics, approvalReason: "Approve this device", linkCrypto: crypto,
            linkSnapshot: { Data("snap".utf8) }, now: { clock.now() },
            sleep: { d in clock.advance(d); await sleeps.add(d); await onSleep(await sleeps.count, t) })
    }
}

@MainActor @Suite struct LiveAccountServiceTests {
    @Test func connectReadsPolicy() async throws {
        let r = Rig()
        await r.t.respond("GET", "/v1/server", status: 200, json: serverJSON)
        let p = try await r.service.connect(host: "chat.test")
        #expect(r.service.currentServer == p)
        #expect(p.host == "chat.test" && p.requiresInvite)
    }

    @Test func fullSignUpWithPasskey() async throws {
        let r = Rig()
        await r.t.respond("GET", "/v1/server", status: 200, json: serverJSON)
        await r.t.respond("POST", "/v1/invites/check", status: 200, json: #"{"valid":true}"#)
        await r.t.respond("POST", "/v1/signup/start", status: 200, json: #"{"signupID":"s1"}"#)
        await r.t.respond("POST", "/v1/signup/verify", status: 200, json: "{}")
        await r.t.respond("POST", "/v1/signup/passkey/options", status: 200, json: creationJSON)
        await r.t.respond("POST", "/v1/signup/finish", status: 200, json: sessionJSON)
        _ = try await r.service.connect(host: "chat.test")
        #expect(try await r.service.redeemInvite(code: "ABCDE-FGHJK"))
        try await r.service.startSignUp(username: "ana", email: "ana@example.org")
        try await r.service.verifyEmail(code: "123456")
        try await r.service.finishSignUp()
        #expect(await r.passkey.registered == 1)
        let finish = try #require(await r.t.requests.last)
        #expect(try jsonBody(finish)["credential"] != nil)
        #expect(try jsonBody(finish)["platform"] as? String == "iphone")
        #expect(r.store.current == Session(host: "chat.test", accountID: "a1", deviceID: "d1", token: "t1"))
        #expect(r.service.isSignedIn)
        #expect(r.service.client != nil)
    }

    @Test func signUpWithoutPasskeyOnOtherHosts() async throws {
        let r = Rig(host: "other.example")
        await r.t.respond("POST", "/v1/signup/start", status: 200, json: #"{"signupID":"s1"}"#)
        await r.t.respond("POST", "/v1/signup/finish", status: 200, json: sessionJSON)
        #expect(!r.service.supportsPasskeys)
        try await r.service.startSignUp(username: "ana", email: "ana@example.org")
        try await r.service.finishSignUp()
        #expect(await r.passkey.registered == 0)
        #expect(try jsonBody(try #require(await r.t.requests.last))["credential"] == nil)
        await #expect(throws: AccountError.passkeyUnavailable) { try await r.service.signInWithPasskey() }
        #expect(await r.passkey.asserted == 0)
    }

    @Test func inviteCarriedIntoSignUp() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/invites/check", status: 200, json: #"{"valid":true}"#)
        await r.t.respond("POST", "/v1/signup/start", status: 200, json: #"{"signupID":"s1"}"#)
        _ = try await r.service.redeemInvite(code: "ABCDE-FGHJK")
        try await r.service.startSignUp(username: "ana", email: "ana@example.org")
        #expect(try jsonBody(try #require(await r.t.requests.last))["inviteCode"] as? String == "ABCDE-FGHJK")
    }

    @Test func invalidInviteThrows() async {
        let r = Rig()
        await r.t.respond("POST", "/v1/invites/check", status: 200, json: #"{"valid":false}"#)
        await #expect(throws: AccountError.inviteInvalid) { _ = try await r.service.redeemInvite(code: "NOPE") }
    }

    @Test func codeErrorsMap() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/signup/start", status: 200, json: #"{"signupID":"s1"}"#)
        try await r.service.startSignUp(username: "ana", email: "ana@example.org")
        await r.t.respond("POST", "/v1/signup/verify", status: 400, json: err("code_invalid"))
        await #expect(throws: AccountError.codeInvalid) { try await r.service.verifyEmail(code: "000000") }
        await r.t.respond("POST", "/v1/signup/verify", status: 429, json: err("rate_limited"), headers: ["Retry-After": "300"])
        await #expect(throws: AccountError.tooManyAttempts(retryAfterSeconds: 300)) { try await r.service.verifyEmail(code: "000000") }
    }

    @Test func recoveryThenAddPasskey() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/signin/email/start", status: 202, json: "{}")
        await r.t.respond("POST", "/v1/signin/email/finish", status: 200, json: sessionJSON)
        await r.t.respond("POST", "/v1/me/passkeys/options", status: 200, json: creationJSON)
        await r.t.respond("POST", "/v1/me/passkeys", status: 200, json: "{}")
        try await r.service.sendRecoveryCode(to: "ana@example.org")
        try await r.service.recover(email: "ana@example.org", code: "123456")
        #expect(r.service.isSignedIn)
        try await r.service.addPasskey()
        #expect(await r.passkey.registered == 1)
        let paths = await r.t.requests.map { $0.url!.path }
        #expect(paths.suffix(2) == ["/v1/me/passkeys/options", "/v1/me/passkeys"])
    }

    @Test func signInWithPasskey() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/signin/passkey/options", status: 200,
                          json: #"{"challengeID":"ch1","options":{"publicKey":{"challenge":"AAEC","rpId":"chat.test"}}}"#)
        await r.t.respond("POST", "/v1/signin/passkey/finish", status: 200, json: sessionJSON)
        try await r.service.signInWithPasskey()
        #expect(await r.passkey.asserted == 1)
        #expect(r.service.isSignedIn)
    }

    @Test func linkNewDevicePolls() async throws {
        let r = Rig(onSleep: { n, t in
            if n == 2 {
                await t.respond("POST", "/v1/link/L1/claim", status: 200,
                                json: #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1","sealed":"AQ"}"#)
            }
        })
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 202, json: "{}")
        let code = try await r.service.makeLinkCode()
        #expect(code.qrText == "https://chat.test/link/L1#S1.\(qrKeyText)")
        try await r.service.waitForLinkApproval()
        #expect(await r.sleeps.count == 2)
        #expect(await r.sleeps.total == .seconds(4))
        #expect(r.service.isSignedIn)
        #expect(r.store.current?.token == "t1")
        #expect(r.service.receivedLinkSnapshot == Data("opened".utf8))
    }

    @Test func linkExpiresAfterFiveMinutes() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 202, json: "{}")
        _ = try await r.service.makeLinkCode()
        await #expect(throws: AccountError.linkExpired) { try await r.service.waitForLinkApproval() }
        #expect(await r.sleeps.total >= .seconds(298))
        #expect(!r.service.isSignedIn)
    }

    @Test func approveLinkRejectsOtherHost() async throws {
        let r = Rig(signedIn: true)
        await #expect(throws: AccountError.wrongServer) { _ = try await r.service.linkRequest(qrText: "https://evil.test/link/L1#S1.\(qrKeyText)") }
        await #expect(throws: AccountError.wrongServer) {
            try await r.service.approveLink(LinkRequest(qrText: "https://evil.test/link/L1#S1.\(qrKeyText)", deviceName: "x", platform: .mac))
        }
        #expect(await r.t.requests.isEmpty)
    }

    @Test func linkRequestShowsDetails() async throws {
        let r = Rig(signedIn: true)
        await r.t.respond("POST", "/v1/link/L1/info", status: 200, json: #"{"deviceName":"New Mac","platform":"mac","createdAt":"2026-11-01T12:00:00Z"}"#)
        let req = try await r.service.linkRequest(qrText: "https://chat.test/link/L1#S1.\(qrKeyText)")
        #expect(req.deviceName == "New Mac" && req.platform == .mac)
        #expect(try jsonBody(try #require(await r.t.requests.last)) as? [String: String] == ["secret": "S1"])
        await r.t.respond("POST", "/v1/link/L1/info", status: 403, json: err("forbidden"))
        await #expect(throws: AccountError.linkExpired) { _ = try await r.service.linkRequest(qrText: "https://chat.test/link/L1#S1.\(qrKeyText)") }
    }

    @Test func approveLinkSendsSecretAndSealedSnapshot() async throws {
        let r = Rig(signedIn: true)
        await r.t.respond("POST", "/v1/link/L1/approve", status: 204, json: "")
        let req = LinkRequest(qrText: "https://chat.test/link/L1#S1.\(qrKeyText)", deviceName: "New Mac", platform: .mac)
        try await r.service.approveLink(req)
        let sent = try #require(await r.t.requests.last)
        #expect(sent.url?.path == "/v1/link/L1/approve")
        let body = try jsonBody(sent)
        #expect(body["secret"] as? String == "S1")
        let sealed = try #require(Base64URLData(encoded: body["sealed"] as? String ?? "")).data
        #expect(sealed == Data("sealed:snap".utf8) + qrKey)
        #expect(await r.biometrics.reasons == ["Approve this device"])
    }

    @Test func invalidQRKeysAreRejectedBeforeAnyRequest() async {
        let r = Rig(signedIn: true)
        let bad = ["https://chat.test/link/L1#S1", "https://chat.test/link/L1#S1.", "https://chat.test/link/L1#S1.AAAA",
                   "https://chat.test/link/L1#.\(qrKeyText)", "https://chat.test/link/L1#S1.\(qrKeyText)."]
        for text in bad {
            await #expect(throws: AccountError.linkExpired) { _ = try await r.service.linkRequest(qrText: text) }
            await #expect(throws: AccountError.linkExpired) {
                try await r.service.approveLink(LinkRequest(qrText: text, deviceName: "x", platform: .mac))
            }
        }
        #expect(await r.t.requests.isEmpty)
        #expect(await r.biometrics.reasons.isEmpty)
    }

    @Test func signedOutLinkCallsFailBeforeHostCheck() async {
        let r = Rig()
        await #expect(throws: AccountError.server) { _ = try await r.service.linkRequest(qrText: "https://evil.test/link/L1#S1.\(qrKeyText)") }
        await #expect(throws: AccountError.server) {
            try await r.service.approveLink(LinkRequest(qrText: "https://evil.test/link/L1#S1.\(qrKeyText)", deviceName: "x", platform: .mac))
        }
    }

    @Test func connectRefusesOtherHostWhileSignedIn() async {
        let r = Rig(signedIn: true)
        await #expect(throws: AccountError.wrongServer) { _ = try await r.service.connect(host: "evil.test") }
        #expect(await r.t.requests.isEmpty)
        #expect(r.store.current?.token == "tok")
    }

    @Test func failedOpenFailsClosedAndForgetsTicket() async throws {
        let r = Rig(crypto: FakeCrypto(openFails: true))
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 200, json: #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1","sealed":"AQ"}"#)
        _ = try await r.service.makeLinkCode()
        await #expect(throws: AccountError.server) { try await r.service.waitForLinkApproval() }
        #expect(r.store.current == nil)
        #expect(!r.service.isSignedIn)
        #expect(r.service.receivedLinkSnapshot == nil)
        // The ticket is gone: waiting again needs a new code.
        await #expect(throws: AccountError.linkExpired) { try await r.service.waitForLinkApproval() }
    }

    @Test func deviceBiometricsNeverFallsBackToPasscodeOnPlatformsWithoutIt() async {
        #if !canImport(LocalAuthentication)
        await #expect(throws: AccountError.biometricsUnavailable) { try await DeviceBiometrics().confirm(reason: "x") }
        #endif
    }

    @Test func approveLinkNeedsBiometrics() async throws {
        let r = Rig(signedIn: true, biometrics: FakeBiometrics(result: .biometricsUnavailable))
        await #expect(throws: AccountError.biometricsUnavailable) {
            try await r.service.approveLink(LinkRequest(qrText: "https://chat.test/link/L1#S1.\(qrKeyText)", deviceName: "x", platform: .mac))
        }
        #expect(await r.t.requests.isEmpty)
    }

    @Test func devicesMapAndRemove() async throws {
        let r = Rig(signedIn: true)
        await r.t.respond("GET", "/v1/devices", status: 200, json: #"{"devices":[{"id":"d1","name":"a","platform":"iphone","createdAt":"2026-11-01T12:00:00Z","lastSeenAt":"2026-11-01T12:00:00Z","current":true},{"id":"d2","name":"b","platform":"mac","createdAt":"2026-11-01T12:00:00Z","lastSeenAt":"2026-11-01T12:00:00Z","current":false}]}"#)
        await r.t.respond("DELETE", "/v1/devices/d2", status: 204, json: "")
        let list = try await r.service.devices()
        #expect(list.map(\.platform) == [.iPhone, .mac])
        #expect(list.map(\.isThisDevice) == [true, false])
        try await r.service.removeDevice(id: "d2")
        #expect(await r.t.requests.last?.httpMethod == "DELETE")
    }

    @Test func deleteAccountClearsSession() async throws {
        let r = Rig(signedIn: true)
        await r.t.respond("DELETE", "/v1/me", status: 204, json: "")
        #expect(r.service.isSignedIn)
        try await r.service.deleteAccount()
        #expect(r.store.current == nil)
        #expect(!r.service.isSignedIn)
    }

    @Test func unauthorizedSignsOut() async {
        let r = Rig(signedIn: true)
        await r.t.respond("GET", "/v1/devices", status: 401, json: err("unauthorized"))
        _ = try? await r.service.devices()
        #expect(!r.service.isSignedIn)
        #expect(r.store.current == nil)
    }
}
