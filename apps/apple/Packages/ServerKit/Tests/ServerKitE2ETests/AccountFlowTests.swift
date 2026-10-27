#if canImport(CryptoKit)
import AppCore
import Foundation
import Testing
@_spi(Testing) import ServerKit
import ServerKitTesting

// End-to-end tests against the real Go server. `apps/apple/scripts/e2e-server.sh` starts the
// server and a mail sink and sets these variables. Without them every test fails (never skips):
//   E2E_BASE_URL      http://127.0.0.1:8080
//   E2E_MAIL_DIR      directory the SMTP sink writes <n>.eml files to
//   E2E_ORIGIN        the WebAuthn origin the software passkeys claim, https://localhost
//   E2E_INVITE_CODE   an invite with several uses

private struct E2EError: Error, CustomStringConvertible {
    let description: String
}

private func env(_ key: String) throws -> String {
    guard let v = ProcessInfo.processInfo.environment[key], !v.isEmpty else {
        throw E2EError(description: "\(key) is not set; run apps/apple/scripts/e2e-server.sh")
    }
    return v
}

/// Reads the mail sink's directory.
private struct Mailbox {
    let dir: URL

    /// The highest message number so far (0 when there is none).
    func newest() -> Int {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return names.filter { $0.hasSuffix(".eml") }.compactMap { Int($0.dropLast(4)) }.max() ?? 0
    }

    /// The six-digit code in the first message newer than `after`, waiting up to 10 s for it.
    func code(after: Int) async throws -> String {
        for _ in 0..<100 {
            let n = newest()
            if n > after {
                let text = try String(contentsOf: dir.appendingPathComponent("\(n).eml"), encoding: .utf8)
                let re = try NSRegularExpression(pattern: #"is (\d{6})\."#)
                let range = NSRange(text.startIndex..., in: text)
                if let m = re.firstMatch(in: text, range: range), let r = Range(m.range(at: 1), in: text) {
                    return String(text[r])
                }
                throw E2EError(description: "message \(n).eml has no code")
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw E2EError(description: "no email arrived within 10 s")
    }
}

private struct AlwaysConfirm: BiometricConfirming {
    func confirm(reason: String) async throws(AccountError) {}
}

private actor StateLog {
    private var seen: [ConnectionState] = []
    private var task: Task<Void, Never>?

    func watch(_ connection: LiveConnection) {
        let states = connection.states
        task = Task { for await s in states { self.add(s) } }
    }
    private func add(_ s: ConnectionState) { seen.append(s) }
    func stop() { task?.cancel() }

    func reached(_ target: ConnectionState, within seconds: Double) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(seconds)
        while ContinuousClock.now < deadline {
            if seen.contains(target) { return true }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return seen.contains(target)
    }
}

/// One device: its own session storage, passkey and account service, talking to the test server.
@MainActor private struct TestDevice {
    let store = SessionStore(storage: MemorySessionStorage())
    let passkey: SoftwarePasskey
    let service: LiveAccountService

    init(name: String, platform: DevicePlatform, passkey: SoftwarePasskey? = nil) throws {
        let base = try #require(URL(string: try env("E2E_BASE_URL")))
        let origin = try env("E2E_ORIGIN")
        let store = store
        let pk = passkey ?? SoftwarePasskey(origin: origin)
        self.passkey = pk
        service = LiveAccountService(
            defaultHost: "localhost", passkeyDomains: ["localhost"], platform: platform, deviceName: name,
            sessions: store,
            makeClient: { _ in ServerClient(baseURL: base, session: store, transport: URLSessionTransport()) },
            passkeys: pk, biometrics: AlwaysConfirm(), approvalReason: "Approve this device")
    }

    var client: ServerClient { get throws { try #require(service.client) } }
    var deviceID: String { store.current?.deviceID ?? "" }

    /// Connects, signs up "ana" with a passkey and returns the account's username as the server sees it.
    func signUpAna(mail: Mailbox) async throws -> String {
        _ = try await service.connect(host: "localhost")
        #expect(try await service.redeemInvite(code: try env("E2E_INVITE_CODE")))
        let before = mail.newest()
        try await service.startSignUp(username: "ana", email: "ana@example.org")
        try await service.verifyEmail(code: try await mail.code(after: before))
        try await service.finishSignUp()
        return try await client.me().username
    }
}

@MainActor private func httpStatus<T>(_ call: () async throws -> T) async -> Int? {
    do {
        _ = try await call()
        return nil
    } catch let e as ServerError {
        return e.status
    } catch {
        return -1
    }
}

@MainActor @Suite(.serialized) struct AccountFlowTests {
    private let mail: Mailbox

    init() throws {
        mail = Mailbox(dir: URL(fileURLWithPath: try env("E2E_MAIL_DIR"), isDirectory: true))
    }

    @Test func signUpLinkRevoke() async throws {
        let iphone = try TestDevice(name: "Ana's iPhone", platform: .iPhone)
        #expect(try await iphone.signUpAna(mail: mail) == "ana")

        // The Mac shows a link code and waits.
        let mac = try TestDevice(name: "Ana's Mac", platform: .mac)
        _ = try await mac.service.connect(host: "localhost")
        let code = try await mac.service.makeLinkCode()
        let macService = mac.service
        let waiting = Task { try await macService.waitForLinkApproval() }

        // The iPhone approves it; the code's host (localhost) matches the iPhone's server.
        let request = try await iphone.service.linkRequest(qrText: code.qrText)
        #expect(request.deviceName == "Ana's Mac")
        try await iphone.service.approveLink(request)
        try await waiting.value

        // The Mac now has the same account and a live connection.
        let phoneMe = try await iphone.client.me()
        let macMe = try await mac.client.me()
        #expect(macMe.accountID == phoneMe.accountID)
        #expect(mac.service.isSignedIn)

        let live = LiveConnection(client: try mac.client, transport: URLSessionWebSocketTransport())
        let log = StateLog()
        await log.watch(live)
        await live.start()
        #expect(await log.reached(.connected, within: 10))

        // The iPhone removes the Mac: the Mac is told within 5 s and its session stops working.
        try await iphone.service.removeDevice(id: mac.deviceID)
        #expect(await log.reached(.revoked, within: 5))
        #expect(await httpStatus { try await mac.client.me() } == 401)

        await live.stop()
        await log.stop()
        try await iphone.service.deleteAccount()
    }

    @Test func recoveryAddsPasskey() async throws {
        let first = try TestDevice(name: "Ana's iPhone", platform: .iPhone)
        #expect(try await first.signUpAna(mail: mail) == "ana")

        // A new device that has no passkey recovers with an email code, then adds its own passkey.
        let second = try TestDevice(name: "Ana's iPad", platform: .iPhone)
        _ = try await second.service.connect(host: "localhost")
        let before = mail.newest()
        try await second.service.sendRecoveryCode(to: "ana@example.org")
        try await second.service.recover(email: "ana@example.org", code: try await mail.code(after: before))
        #expect(second.service.isSignedIn)
        try await second.service.addPasskey()

        // A third device signs in with the new passkey only.
        let third = try TestDevice(name: "Ana's Mac", platform: .mac, passkey: second.passkey)
        _ = try await third.service.connect(host: "localhost")
        try await third.service.signInWithPasskey()
        #expect(third.service.isSignedIn)
        let devices = try await third.service.devices()
        #expect(devices.count == 3)
        #expect(devices.filter(\.isThisDevice).count == 1)

        try await third.service.deleteAccount()
    }

    @Test func deleteAccountFreesUsername() async throws {
        let device = try TestDevice(name: "Ana's iPhone", platform: .iPhone)
        #expect(try await device.signUpAna(mail: mail) == "ana")
        let client = try device.client
        #expect(try await client.usernameAvailable("ana") == false)
        try await device.service.deleteAccount()
        #expect(try await client.usernameAvailable("ana") == true)
    }
}
#endif
