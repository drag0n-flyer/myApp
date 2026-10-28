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

/// Fails the first `failures` saves, then saves; `failures` can be raised again between calls.
private final class FlakyStorage: SessionStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var value: Session?
    private var left: Int
    private(set) var attempts = 0
    init(failures: Int) { left = failures }
    func fail(_ n: Int) { lock.withLock { left = n } }
    func load() throws -> Session? { lock.withLock { value } }
    func save(_ s: Session) throws {
        try lock.withLock {
            attempts += 1
            if left > 0 { left -= 1; throw CocoaError(.fileWriteUnknown) }
            value = s
        }
    }
    func clear() throws { lock.withLock { value = nil } }
}

private actor SleepLog {
    private(set) var total = Duration.zero
    private(set) var count = 0
    func add(_ d: Duration) { total += d; count += 1 }
}

private let serverJSON = #"{"protocol":1,"host":"chat.test","requiresInvite":true,"policy":{"defaultMode":"auto","maxMode":"high","defaultIPHiding":"nonContacts"},"encryption":{"newConversations":"e2ee","escrowKey":null},"features":[],"federation":false,"limits":{"attachmentBytes":1}}"#
private let creationJSON = #"{"challenge":"AAEC","rp":{"name":"Chat","id":"chat.test"},"user":{"name":"ana","displayName":"ana","id":"AAEC"}}"#
private let sessionJSON = #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1"}"#
private func err(_ code: String) -> String { #"{"error":{"code":"\#(code)","message":"x"}}"# }

@MainActor private struct Rig {
    let t = StubTransport()
    let store: SessionStore
    let passkey = FakePasskey()
    let service: LiveAccountService
    let sleeps = SleepLog()
    let biometrics: FakeBiometrics

    init(host: String = "chat.test", signedIn: Bool = false, sessionHost: String = "chat.test",
         biometrics: FakeBiometrics = FakeBiometrics(), crypto: FakeCrypto = FakeCrypto(),
         snapshot: Data = Data("snap".utf8), storage: any SessionStorage = MemorySessionStorage(),
         onSleep: @escaping @Sendable (Int, StubTransport) async throws -> Void = { _, _ in }) {
        store = SessionStore(storage: storage)
        if signedIn { try? store.save(Session(host: sessionHost, accountID: "a", deviceID: "d", token: "tok")) }
        self.biometrics = biometrics
        let t = t, store = store, sleeps = sleeps
        let clock = ClockBox()
        service = LiveAccountService(
            defaultHost: host, passkeyDomains: ["chat.test"], platform: .iPhone, deviceName: "Ana's iPhone",
            sessions: store,
            makeClient: { h in ServerClient(baseURL: URL(string: "https://\(h)")!, session: store, transport: t) },
            passkeys: passkey, biometrics: biometrics, approvalReason: "Approve this device", linkCrypto: crypto,
            linkSnapshot: { snapshot }, now: { clock.now() },
            sleep: { d in clock.advance(d); await sleeps.add(d); try await onSleep(await sleeps.count, t) })
    }
}

@MainActor private final class ServiceBox {
    var service: LiveAccountService?
    private(set) var changed = false
    func changeServer(to host: String) async {
        changed = (try? await service?.connect(host: host)) != nil
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
        try await r.service.startSignUp(displayName: "", username: "ana", email: "ana@example.org")
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
        try await r.service.startSignUp(displayName: "", username: "ana", email: "ana@example.org")
        try await r.service.finishSignUp()
        #expect(await r.passkey.registered == 0)
        #expect(try jsonBody(try #require(await r.t.requests.last))["credential"] == nil)
        await #expect(throws: AccountError.passkeyUnavailable) { try await r.service.signInWithPasskey() }
        #expect(await r.passkey.asserted == 0)
    }

    @Test func signUpSendsDisplayNameAfterFinish() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/signup/start", status: 200, json: #"{"signupID":"s1"}"#)
        await r.t.respond("POST", "/v1/signup/passkey/options", status: 200, json: creationJSON)
        await r.t.respond("POST", "/v1/signup/finish", status: 200, json: sessionJSON)
        await r.t.respond("PATCH", "/v1/me/settings", status: 204, json: "")
        try await r.service.startSignUp(displayName: "  Ana Lima ", username: "ana", email: "ana@example.org")
        #expect(try jsonBody(try #require(await r.t.requests.last))["displayName"] == nil)
        try await r.service.finishSignUp()
        let requests = await r.t.requests
        #expect(requests.map { $0.url?.path ?? "" }.suffix(2) == ["/v1/signup/finish", "/v1/me/settings"])
        let patch = try #require(requests.last)
        #expect(patch.httpMethod == "PATCH")
        #expect(patch.value(forHTTPHeaderField: "Authorization") == "Bearer t1")
        let body = try jsonBody(patch)
        #expect(body["displayName"] as? String == "Ana Lima")
        #expect(body["findByEmail"] == nil)
    }

    @Test func blankDisplayNameSendsNoSettings() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/signup/start", status: 200, json: #"{"signupID":"s1"}"#)
        await r.t.respond("POST", "/v1/signup/passkey/options", status: 200, json: creationJSON)
        await r.t.respond("POST", "/v1/signup/finish", status: 200, json: sessionJSON)
        try await r.service.startSignUp(displayName: "   ", username: "ana", email: "ana@example.org")
        try await r.service.finishSignUp()
        #expect(await r.t.requests.last?.url?.path == "/v1/signup/finish")
    }

    @Test func displayNameFailureDoesNotFailSignUp() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/signup/start", status: 200, json: #"{"signupID":"s1"}"#)
        await r.t.respond("POST", "/v1/signup/passkey/options", status: 200, json: creationJSON)
        await r.t.respond("POST", "/v1/signup/finish", status: 200, json: sessionJSON)
        await r.t.respond("PATCH", "/v1/me/settings", status: 500, json: err("internal"))
        try await r.service.startSignUp(displayName: "Ana", username: "ana", email: "ana@example.org")
        try await r.service.finishSignUp()
        #expect(r.service.isSignedIn)
        #expect(r.store.current?.token == "t1")
    }

    @Test func emailCodeSignUpNeverRegistersPasskey() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/signup/start", status: 200, json: #"{"signupID":"s1"}"#)
        await r.t.respond("POST", "/v1/signup/finish", status: 200, json: sessionJSON)
        await r.t.respond("PATCH", "/v1/me/settings", status: 204, json: "")
        #expect(r.service.supportsPasskeys)
        try await r.service.startSignUp(displayName: "Ana", username: "ana", email: "ana@example.org")
        try await r.service.finishSignUpWithEmailCode()
        #expect(await r.passkey.registered == 0)
        let requests = await r.t.requests
        #expect(!requests.contains { $0.url?.path == "/v1/signup/passkey/options" })
        let finish = try #require(requests.first { $0.url?.path == "/v1/signup/finish" })
        #expect(try jsonBody(finish)["credential"] == nil)
        #expect(requests.last?.url?.path == "/v1/me/settings")
        #expect(r.service.isSignedIn)
    }

    @Test func inviteCarriedIntoSignUp() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/invites/check", status: 200, json: #"{"valid":true}"#)
        await r.t.respond("POST", "/v1/signup/start", status: 200, json: #"{"signupID":"s1"}"#)
        _ = try await r.service.redeemInvite(code: "ABCDE-FGHJK")
        try await r.service.startSignUp(displayName: "", username: "ana", email: "ana@example.org")
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
        try await r.service.startSignUp(displayName: "", username: "ana", email: "ana@example.org")
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
                          json: #"{"challengeID":"ch1","options":{"challenge":"AAEC","rpId":"chat.test"}}"#)
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
        let result = try await r.service.waitForLinkApproval()
        // Opened, but nothing imports it yet (plan 7): not copied.
        #expect(!result.contactsCopied)
        #expect(await r.sleeps.count == 2)
        #expect(await r.sleeps.total == .seconds(4))
        #expect(r.service.isSignedIn)
        #expect(r.store.current?.token == "t1")
        #expect(r.service.receivedLinkSnapshot == Data("opened".utf8))
    }

    /// A "Change server" tap while the claim is in flight must not file the session under the new host.
    @Test func linkSessionKeepsTheHostTheClaimWasMadeOn() async throws {
        let box = ServiceBox()
        let r = Rig(onSleep: { n, t in
            if n == 1 {
                await t.respond("GET", "/v1/server", status: 200, json: serverJSON)
                await box.changeServer(to: "other.test")
                await t.respond("POST", "/v1/link/L1/claim", status: 200, json: sessionJSON)
            }
        })
        box.service = r.service
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 202, json: "{}")
        _ = try await r.service.makeLinkCode()
        _ = try await r.service.waitForLinkApproval()
        #expect(box.changed)
        #expect(r.store.current?.host == "chat.test")
    }

    @Test func linkExpiresAfterFiveMinutes() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 202, json: "{}")
        _ = try await r.service.makeLinkCode()
        await #expect(throws: AccountError.linkExpired) { _ = try await r.service.waitForLinkApproval() }
        #expect(await r.sleeps.total >= .seconds(298))
        #expect(!r.service.isSignedIn)
    }

    @Test func approveLinkRejectsOtherHost() async throws {
        let r = Rig(signedIn: true)
        await #expect(throws: AccountError.wrongServer) { _ = try await r.service.linkRequest(qrText: "https://evil.test/link/L1#S1.\(qrKeyText)") }
        await #expect(throws: AccountError.wrongServer) {
            _ = try await r.service.approveLink(LinkRequest(qrText: "https://evil.test/link/L1#S1.\(qrKeyText)", deviceName: "x", platform: .mac))
        }
        #expect(await r.t.requests.isEmpty)
    }

    @Test func linkRequestShowsDetails() async throws {
        let r = Rig(signedIn: true)
        await r.t.respond("POST", "/v1/link/L1/info", status: 200, json: #"{"deviceName":"New Mac","platform":"mac","createdAt":"2026-11-01T12:00:00Z"}"#)
        let req = try await r.service.linkRequest(qrText: "https://chat.test/link/L1#S1.\(qrKeyText)")
        #expect(req.deviceName == "New Mac" && req.platform == .mac)
        #expect(req.requestedAt != nil)
        #expect(try jsonBody(try #require(await r.t.requests.last)) as? [String: String] == ["secret": "S1"])
        await r.t.respond("POST", "/v1/link/L1/info", status: 403, json: err("forbidden"))
        await #expect(throws: AccountError.linkExpired) { _ = try await r.service.linkRequest(qrText: "https://chat.test/link/L1#S1.\(qrKeyText)") }
    }

    @Test func approveLinkSendsSecretAndSealedSnapshot() async throws {
        let r = Rig(signedIn: true)
        await r.t.respond("POST", "/v1/link/L1/approve", status: 204, json: "")
        let req = LinkRequest(qrText: "https://chat.test/link/L1#S1.\(qrKeyText)", deviceName: "New Mac", platform: .mac)
        let approval = try await r.service.approveLink(req)
        #expect(approval.snapshotSent)
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
                   "https://chat.test/link/L1#.\(qrKeyText)", "https://chat.test/link/L1#S1.\(qrKeyText).",
                   "http://chat.test/link/L1#S1.\(qrKeyText)"]
        for text in bad {
            await #expect(throws: AccountError.linkExpired) { _ = try await r.service.linkRequest(qrText: text) }
            await #expect(throws: AccountError.linkExpired) {
                _ = try await r.service.approveLink(LinkRequest(qrText: text, deviceName: "x", platform: .mac))
            }
        }
        #expect(await r.t.requests.isEmpty)
        #expect(await r.biometrics.reasons.isEmpty)
    }

    @Test func signedOutLinkCallsFailBeforeHostCheck() async {
        let r = Rig()
        await #expect(throws: AccountError.server) { _ = try await r.service.linkRequest(qrText: "https://evil.test/link/L1#S1.\(qrKeyText)") }
        await #expect(throws: AccountError.server) {
            _ = try await r.service.approveLink(LinkRequest(qrText: "https://evil.test/link/L1#S1.\(qrKeyText)", deviceName: "x", platform: .mac))
        }
    }

    @Test func connectRefusesOtherHostWhileSignedIn() async {
        let r = Rig(signedIn: true)
        await #expect(throws: AccountError.wrongServer) { _ = try await r.service.connect(host: "evil.test") }
        #expect(await r.t.requests.isEmpty)
        #expect(r.store.current?.token == "tok")
    }

    @Test func snapshotThatWontOpenIsSkippedAndDeviceSignsIn() async throws {
        let r = Rig(crypto: FakeCrypto(openFails: true))
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 200, json: #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1","sealed":"AQ"}"#)
        _ = try await r.service.makeLinkCode()
        let result = try await r.service.waitForLinkApproval()
        #expect(result == LinkedResult(contactsCopied: false))
        #expect(r.store.current?.token == "t1")
        #expect(r.service.isSignedIn)
        #expect(r.service.receivedLinkSnapshot == nil)
        // The ticket is gone: waiting again needs a new code.
        await #expect(throws: AccountError.linkExpired) { _ = try await r.service.waitForLinkApproval() }
    }

    private static func claims(_ t: StubTransport) async -> Int {
        await t.requests.filter { $0.url?.path == "/v1/link/L1/claim" }.count
    }

    @Test func linkSessionSaveIsRetriedAfterTheClaim() async throws {
        let storage = FlakyStorage(failures: 3)
        let r = Rig(storage: storage)
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 200, json: #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1"}"#)
        _ = try await r.service.makeLinkCode()
        _ = try await r.service.waitForLinkApproval()
        #expect(await Self.claims(r.t) == 1)
        #expect(storage.attempts == 4)
        #expect(r.service.isSignedIn)
        #expect(r.store.current?.token == "t1")
    }

    @Test func linkSessionSaveThatKeepsFailingIsReportedAndRetriedWithoutClaimingAgain() async throws {
        let storage = FlakyStorage(failures: 100)
        let r = Rig(storage: storage)
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 200, json: #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1"}"#)
        _ = try await r.service.makeLinkCode()
        await #expect(throws: AccountError.server) { _ = try await r.service.waitForLinkApproval() }
        #expect(storage.attempts == LiveAccountService.linkSaveAttempts)
        #expect(await Self.claims(r.t) == 1)
        #expect(!r.service.isSignedIn)
        // The person tries again: only the save is retried; the spent claim is not sent again.
        storage.fail(0)
        _ = try await r.service.waitForLinkApproval()
        #expect(await Self.claims(r.t) == 1)
        #expect(r.service.isSignedIn)
        #expect(r.store.current?.token == "t1")
    }

    /// The app's retry after a failed save goes through `linkThisDevice`: it must retry the save only, and
    /// never ask for a new code (which would drop the claimed session and orphan the device).
    @Test func retryAfterFailedLinkSaveOnlyRetriesTheSave() async throws {
        let storage = FlakyStorage(failures: 100)
        let r = Rig(storage: storage)
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 200, json: #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1"}"#)
        var codes = 0
        await #expect(throws: AccountError.server) { _ = try await r.service.linkThisDevice { _ in codes += 1 } }
        #expect(codes == 1)
        #expect(r.service.linkSavePending)
        storage.fail(0)
        _ = try await r.service.linkThisDevice { _ in codes += 1 }
        #expect(codes == 1)
        #expect(await r.t.requests.filter { $0.url?.path == "/v1/link" }.count == 1)
        #expect(await Self.claims(r.t) == 1)
        #expect(!r.service.linkSavePending)
        #expect(r.store.current?.token == "t1")
    }

    /// A claimed session belongs to the server that issued it: while its save is pending another server
    /// is refused, and the token is filed under (and sent to) the original host only.
    @Test func pendingLinkSaveCannotMoveToAnotherServer() async throws {
        let storage = FlakyStorage(failures: 100)
        let r = Rig(storage: storage)
        await r.t.respond("GET", "/v1/server", status: 200, json: serverJSON)
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 200, json: #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1"}"#)
        await r.t.respond("GET", "/v1/devices", status: 200, json: #"{"devices":[]}"#)
        await #expect(throws: AccountError.server) { _ = try await r.service.linkThisDevice { _ in } }
        await #expect(throws: AccountError.wrongServer) { _ = try await r.service.connect(host: "other.test") }
        storage.fail(0)
        _ = try await r.service.linkThisDevice { _ in Issue.record("asked for a new code") }
        #expect(r.store.current?.host == "chat.test")
        _ = try? await r.service.devices()
        let hosts = await r.t.requests.compactMap { $0.url?.host }
        #expect(!hosts.contains("other.test"))
        #expect(hosts.last == "chat.test")
    }

    /// Leaving the screen mid-backoff cancels the wait; coming back resumes the save.
    @Test func leavingDuringSaveBackoffKeepsTheSessionToResume() async throws {
        let storage = FlakyStorage(failures: 100)
        let r = Rig(storage: storage, onSleep: { _, _ in throw CancellationError() })
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 200, json: #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1"}"#)
        await #expect(throws: AccountError.cancelled) { _ = try await r.service.linkThisDevice { _ in } }
        #expect(r.service.linkSavePending)
        storage.fail(0)
        _ = try await r.service.linkThisDevice { _ in Issue.record("asked for a new code") }
        #expect(await Self.claims(r.t) == 1)
        #expect(r.service.isSignedIn)
    }

    @Test func approvalWithoutSnapshotSignsInWithoutContacts() async throws {
        let r = Rig()
        await r.t.respond("POST", "/v1/link", status: 200, json: #"{"linkID":"L1","secret":"S1","claimToken":"C1","expiresAt":"2026-11-01T12:05:00Z"}"#)
        await r.t.respond("POST", "/v1/link/L1/claim", status: 200, json: #"{"accountID":"a1","deviceID":"d1","sessionToken":"t1"}"#)
        _ = try await r.service.makeLinkCode()
        let result = try await r.service.waitForLinkApproval()
        #expect(result == LinkedResult(contactsCopied: false))
        #expect(r.service.isSignedIn)
        #expect(r.service.receivedLinkSnapshot == nil)
    }

    /// FakeCrypto adds 7 bytes in front and the 32-byte key behind: a snapshot of 64 KiB - 39 bytes seals
    /// to exactly the 64 KiB limit.
    @Test func snapshotAtTheLimitIsSent() async throws {
        let r = Rig(signedIn: true, snapshot: Data(repeating: 1, count: 64 * 1024 - 39))
        await r.t.respond("POST", "/v1/link/L1/approve", status: 204, json: "")
        let approval = try await r.service.approveLink(LinkRequest(qrText: "https://chat.test/link/L1#S1.\(qrKeyText)", deviceName: "x", platform: .mac))
        #expect(approval == LinkApproval(snapshotSent: true))
        let body = try jsonBody(try #require(await r.t.requests.last))
        let sealed = try #require(Base64URLData(encoded: body["sealed"] as? String ?? "")).data
        #expect(sealed.count == LiveAccountService.maxSealedSnapshotBytes)
    }

    @Test func tooLargeSnapshotIsLeftOutAndApproverIsTold() async throws {
        let r = Rig(signedIn: true, snapshot: Data(repeating: 1, count: 64 * 1024 - 38))
        await r.t.respond("POST", "/v1/link/L1/approve", status: 204, json: "")
        let approval = try await r.service.approveLink(LinkRequest(qrText: "https://chat.test/link/L1#S1.\(qrKeyText)", deviceName: "x", platform: .mac))
        #expect(approval == LinkApproval(snapshotSent: false))
        let sent = try #require(await r.t.requests.last)
        #expect(sent.url?.path == "/v1/link/L1/approve")
        let body = try jsonBody(sent)
        #expect(body["secret"] as? String == "S1")
        #expect(body["sealed"] == nil)
    }

    @Test func linkCodeMustMatchServerPort() async {
        let r = Rig(signedIn: true)
        let others = ["https://chat.test:8443/link/L1#S1.\(qrKeyText)", "https://chat.test:443/link/L1#S1.\(qrKeyText)",
                      "https://chat.test:/link/L1#S1.\(qrKeyText)", "https://own@chat.test/link/L1#S1.\(qrKeyText)",
                      "https:/link/L1?x=https://chat.test#S1.\(qrKeyText)", "https:link/L1?https://chat.test#S1.\(qrKeyText)"]
        for text in others {
            await #expect(throws: AccountError.wrongServer) { _ = try await r.service.linkRequest(qrText: text) }
            await #expect(throws: AccountError.wrongServer) {
                _ = try await r.service.approveLink(LinkRequest(qrText: text, deviceName: "x", platform: .mac))
            }
        }
        #expect(await r.t.requests.isEmpty)
        #expect(await r.biometrics.reasons.isEmpty)
    }

    @Test func linkCodeWithPortMatchesSessionWithSamePort() async throws {
        let r = Rig(signedIn: true, sessionHost: "chat.test:8443")
        await #expect(throws: AccountError.wrongServer) { _ = try await r.service.linkRequest(qrText: "https://chat.test/link/L1#S1.\(qrKeyText)") }
        await #expect(throws: AccountError.wrongServer) { _ = try await r.service.linkRequest(qrText: "https://chat.test:9443/link/L1#S1.\(qrKeyText)") }
        #expect(await r.t.requests.isEmpty)
        await r.t.respond("POST", "/v1/link/L1/info", status: 200, json: #"{"deviceName":"New Mac","platform":"mac","createdAt":"2026-11-01T12:00:00Z"}"#)
        let req = try await r.service.linkRequest(qrText: "  HTTPS://CHAT.test:8443/link/L1#S1.\(qrKeyText)\n")
        #expect(req.deviceName == "New Mac")
    }

    @Test func deviceBiometricsNeverFallsBackToPasscodeOnPlatformsWithoutIt() async {
        #if !canImport(LocalAuthentication)
        await #expect(throws: AccountError.biometricsUnavailable) { try await DeviceBiometrics().confirm(reason: "x") }
        #endif
    }

    @Test func approveLinkNeedsBiometrics() async throws {
        let r = Rig(signedIn: true, biometrics: FakeBiometrics(result: .biometricsUnavailable))
        await #expect(throws: AccountError.biometricsUnavailable) {
            _ = try await r.service.approveLink(LinkRequest(qrText: "https://chat.test/link/L1#S1.\(qrKeyText)", deviceName: "x", platform: .mac))
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
        await r.t.respond("GET", "/v1/me", status: 401, json: err("unauthorized"))
        var reasons: [SignOutReason] = []
        r.service.onSignedOut = { reasons.append($0) }
        _ = try? await r.service.devices()
        // The server's own 401, confirmed by GET /v1/me: the app wipes (ruling 2026-10-28).
        #expect(reasons == [.revoked])
        #expect(!r.service.isSignedIn)
        #expect(r.store.current == nil)
    }

    @Test func unconfirmedUnauthorizedSignsOutWithoutWipe() async {
        let r = Rig(signedIn: true)
        await r.t.respond("GET", "/v1/devices", status: 401, json: err("unauthorized"))
        await r.t.respond("GET", "/v1/me", status: 200, json: "{}")
        var reasons: [SignOutReason] = []
        r.service.onSignedOut = { reasons.append($0) }
        _ = try? await r.service.devices()
        #expect(reasons == [.sessionEnded])
        #expect(!r.service.isSignedIn)
        #expect(r.store.current == nil)
    }
}
