import AppCore
import Foundation

/// `AccountService` over the Go server. Passkeys are used only for hosts in `passkeyDomains`;
/// on other hosts sign-up has no credential and sign-in uses an email code.
@MainActor
public final class LiveAccountService: AccountService {
    private let defaultHost: String
    private let passkeyDomains: [String]
    private let platform: DevicePlatform
    private let deviceName: String
    private let sessions: SessionStore
    private let makeClient: @Sendable (String) -> ServerClient
    private let passkeys: any PasskeyAuthenticating
    private let biometrics: any BiometricConfirming
    private let linkCrypto: any LinkCrypto
    private let linkSnapshot: @Sendable () async -> Data
    private let approvalReason: String
    private let now: @Sendable () -> Date
    private let sleep: @Sendable (Duration) async throws -> Void

    private var host: String
    private var working: ServerClient?
    private var installedFor: ObjectIdentifier?

    public private(set) var defaultServer: ServerPolicy
    public private(set) var currentServer: ServerPolicy?
    public private(set) var isSignedIn: Bool
    /// Called when the server ends this session (401 or the device was removed), not on a local sign-out.
    public var onSignedOut: (@MainActor () -> Void)?
    /// The client for the signed-in (or connecting) server, for plans 7 and 8.
    public var client: ServerClient? { working }
    /// Set when this device was linked and the approver sent a snapshot; plan 7 imports it.
    public private(set) var receivedLinkSnapshot: Data?

    private var inviteCode: String?
    private var signupID: String?
    /// Sent with `PATCH /v1/me/settings` once sign-up has finished; nil when blank.
    private var pendingDisplayName: String?
    private var ticket: LinkTicket?
    private var linkPrivateKey: Data?
    /// A claimed link session whose save failed, with its opened snapshot; memory only. The claim answers
    /// once, so a later `waitForLinkApproval` retries only the save and never claims again.
    private var unsaved: (session: NewSession, snapshot: Data?)?

    public init(defaultHost: String, passkeyDomains: [String], platform: DevicePlatform, deviceName: String,
                sessions: SessionStore, makeClient: @escaping @Sendable (String) -> ServerClient,
                passkeys: any PasskeyAuthenticating,
                biometrics: any BiometricConfirming = DeviceBiometrics(),
                approvalReason: String,
                linkCrypto: any LinkCrypto = DeviceLinkCrypto(),
                linkSnapshot: @escaping @Sendable () async -> Data = { Data() },
                now: @escaping @Sendable () -> Date = { Date() },
                sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.defaultHost = defaultHost
        self.passkeyDomains = passkeyDomains
        self.platform = platform
        self.deviceName = deviceName
        self.sessions = sessions
        self.makeClient = makeClient
        self.passkeys = passkeys
        self.biometrics = biometrics
        self.linkCrypto = linkCrypto
        self.linkSnapshot = linkSnapshot
        self.approvalReason = approvalReason
        self.now = now
        self.sleep = sleep
        self.host = sessions.current?.host ?? defaultHost
        self.defaultServer = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts,
                                          requiresInvite: true, host: defaultHost)
        self.isSignedIn = sessions.current != nil
        if let s = sessions.current { adopt(makeClient(s.host)) }
    }

    public var supportsPasskeys: Bool { PasskeyPolicy.supported(host: host, domains: passkeyDomains) }

    // MARK: - Server

    /// While signed in, the session token must never reach another host, so only the session's own
    /// host can be connected to.
    public func connect(host: String) async throws(AccountError) -> ServerPolicy {
        if let mine = sessions.current?.host, mine.lowercased() != host.lowercased() { throw .wrongServer }
        self.host = host
        adopt(makeClient(host))
        let c = await ready()
        do throws(ServerError) {
            let policy = try await c.serverInfo().policy()
            currentServer = policy
            if host == defaultHost { defaultServer = policy }
            return policy
        } catch {
            throw Self.map(error)
        }
    }

    // MARK: - Sign-up

    public func checkUsername(_ name: String) async -> UsernameCheck {
        guard UsernameCheck.isValid(name) else { return .invalid }
        do throws(ServerError) {
            return try await ready().usernameAvailable(name) ? .available : .taken
        } catch {
            return error.code == "invalid_username" ? .invalid : .unknown
        }
    }

    public func redeemInvite(code: String) async throws(AccountError) -> Bool {
        do throws(ServerError) {
            guard try await ready().checkInvite(code: code) else { throw AccountError.inviteInvalid.asServerError }
            inviteCode = code
            return true
        } catch {
            throw Self.map(error)
        }
    }

    public func startSignUp(displayName: String, username: String, email: String) async throws(AccountError) {
        do throws(ServerError) {
            signupID = try await ready().signupStart(inviteCode: inviteCode, username: username, email: email)
            let name = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
            pendingDisplayName = name.isEmpty ? nil : name
        } catch {
            throw Self.map(error)
        }
    }

    public func verifyEmail(code: String) async throws(AccountError) {
        guard let signupID else { throw .server }
        do throws(ServerError) {
            try await ready().signupVerify(signupID: signupID, code: code)
        } catch {
            throw Self.map(error)
        }
    }

    public func finishSignUp() async throws(AccountError) {
        try await finishSignUp(withPasskey: true)
    }

    /// Never registers a passkey, even on a host that supports them.
    public func finishSignUpWithEmailCode() async throws(AccountError) {
        try await finishSignUp(withPasskey: false)
    }

    private func finishSignUp(withPasskey: Bool) async throws(AccountError) {
        guard let signupID else { throw .server }
        let c = await ready()
        var credential: RegistrationCredential?
        if withPasskey && supportsPasskeys {
            do throws(ServerError) {
                let options = try await c.signupPasskeyOptions(signupID: signupID)
                credential = try await register(options)
            } catch {
                throw Self.map(error)
            }
        }
        do throws(ServerError) {
            let s = try await c.signupFinish(signupID: signupID, credential: credential, deviceName: deviceName,
                                             platform: Self.wire(platform))
            try store(s)
            self.signupID = nil
            self.inviteCode = nil
        } catch {
            throw Self.map(error)
        }
        // The server's signup/start takes no display name yet: set it now that the session exists.
        // A failure here does not fail the sign-up.
        if let name = pendingDisplayName {
            try? await c.updateSettings(displayName: name, findByEmail: nil)
        }
        pendingDisplayName = nil
    }

    // MARK: - Sign-in and recovery

    public func signInWithPasskey() async throws(AccountError) {
        guard supportsPasskeys else { throw .passkeyUnavailable }
        let c = await ready()
        let (challengeID, options) = try await call { () async throws(ServerError) in try await c.signinPasskeyOptions() }
        let assertion = try await passkeys.assert(options)
        let s = try await call { () async throws(ServerError) in
            try await c.signinPasskeyFinish(challengeID: challengeID, credential: assertion, deviceName: self.deviceName,
                                            platform: Self.wire(self.platform))
        }
        try save(s)
    }

    public func sendRecoveryCode(to email: String) async throws(AccountError) {
        let c = await ready()
        try await call { () async throws(ServerError) in try await c.signinEmailStart(email: email) }
    }

    public func recover(email: String, code: String) async throws(AccountError) {
        let c = await ready()
        let s = try await call { () async throws(ServerError) in
            try await c.signinEmailFinish(email: email, code: code, deviceName: self.deviceName,
                                          platform: Self.wire(self.platform))
        }
        try save(s)
    }

    public func addPasskey() async throws(AccountError) {
        guard supportsPasskeys else { throw .passkeyUnavailable }
        guard isSignedIn else { throw .server }
        let c = await ready()
        let options = try await call { () async throws(ServerError) in try await c.addPasskeyOptions() }
        let credential = try await passkeys.register(options)
        try await call { () async throws(ServerError) in try await c.addPasskey(credential) }
    }

    // MARK: - Linking, new device

    public var linkSavePending: Bool { unsaved != nil }

    public func makeLinkCode() async throws(AccountError) -> LinkCode {
        let c = await ready()
        unsaved = nil
        let keys = try linkCrypto.makeKeyPair()
        let t = try await call(link: true) { () async throws(ServerError) in
            try await c.linkStart(deviceName: self.deviceName, platform: Self.wire(self.platform))
        }
        ticket = t
        linkPrivateKey = keys.privateKey
        let key = Base64URLData(keys.publicKey).encoded
        return LinkCode(qrText: "https://\(host)/link/\(t.linkID)#\(t.secret).\(key)", expiresAt: t.expiresAt)
    }

    /// Polls every 2 s until approved or the ticket's expiry passes. Every exit path forgets the ticket
    /// and the private key. A snapshot that is missing or cannot be opened is skipped: the session is
    /// saved, the device signs in without its contacts, and the result says so (the claim is single-use,
    /// so failing here would leave an orphaned device and make the person start again). For the same
    /// reason a failing session save is retried, and after an error the next call retries only the save.
    public func waitForLinkApproval() async throws(AccountError) -> LinkedResult {
        if let unsaved { return try await saveLinked(unsaved.session, snapshot: unsaved.snapshot) }
        guard let ticket else { throw .linkExpired }
        let key = linkPrivateKey
        defer { self.ticket = nil; linkPrivateKey = nil }
        let c = await ready()
        while true {
            let claim = try await call(link: true) { () async throws(ServerError) in
                try await c.linkClaim(linkID: ticket.linkID, claimToken: ticket.claimToken)
            }
            if case .approved(let s, let sealed) = claim {
                var snapshot: Data?
                if let sealed, let key {
                    snapshot = try? linkCrypto.open(sealed, privateKey: key)
                }
                unsaved = (s, snapshot)
                return try await saveLinked(s, snapshot: snapshot)
            }
            if now() >= ticket.expiresAt { throw .linkExpired }
            do { try await sleep(.seconds(2)) } catch { throw .cancelled }
        }
    }

    /// About 11 seconds of backoff (0.25 s doubling, at most 4 s) before giving up for now, as on Android:
    /// a claimed link session is worth waiting for.
    public static let linkSaveAttempts = 8

    /// Saves a claimed link session, retrying a failing store with backoff. When it still fails the error
    /// is thrown and the session is kept in `unsaved`, so the next wait retries the save alone.
    private func saveLinked(_ s: NewSession, snapshot: Data?) async throws(AccountError) -> LinkedResult {
        var wait = Duration.milliseconds(250)
        var attempt = 0
        while true {
            do throws(ServerError) {
                try store(s)
                break
            } catch {
                attempt += 1
                if attempt >= Self.linkSaveAttempts { throw Self.map(error) }
                do { try await sleep(wait) } catch { throw .cancelled }
                wait = min(wait * 2, .seconds(4))
            }
        }
        unsaved = nil
        receivedLinkSnapshot = snapshot
        // TODO(plan 7): import `snapshot` and report copied only when the import succeeds, as Android
        // does. Nothing imports it yet, so the contacts were not copied.
        return LinkedResult(contactsCopied: false)
    }

    // MARK: - Linking, approving device

    public func linkRequest(qrText: String) async throws(AccountError) -> LinkRequest {
        guard isSignedIn else { throw .server }
        let code = try parseLink(qrText)
        let c = await ready()
        let info = try await call(link: true) { () async throws(ServerError) in
            try await c.linkInfo(linkID: code.linkID, secret: code.secret)
        }
        return LinkRequest(qrText: qrText, deviceName: info.deviceName, platform: Self.platform(info.platform),
                           requestedAt: info.createdAt)
    }

    /// The server refuses a larger sealed snapshot (`too_large`).
    public static let maxSealedSnapshotBytes = 64 * 1024

    public func approveLink(_ request: LinkRequest) async throws(AccountError) -> LinkApproval {
        guard isSignedIn else { throw .server }
        let code = try parseLink(request.qrText)
        let c = await ready()
        try await biometrics.confirm(reason: approvalReason)
        let sealed = try linkCrypto.seal(await linkSnapshot(), to: code.publicKey)
        // Over the server's limit: approve without it; the new device starts without its contacts.
        let fits = sealed.count <= Self.maxSealedSnapshotBytes
        try await call(link: true) { () async throws(ServerError) in
            try await c.linkApprove(linkID: code.linkID, secret: code.secret, sealed: fits ? sealed : nil)
        }
        return LinkApproval(snapshotSent: fits)
    }

    // MARK: - Devices and account

    public func devices() async throws(AccountError) -> [DeviceInfo] {
        guard isSignedIn else { throw .server }
        let c = await ready()
        let list = try await call { () async throws(ServerError) in try await c.devices() }
        return list.map {
            DeviceInfo(id: $0.id, name: $0.name, platform: Self.platform($0.platform), lastActive: $0.lastSeenAt,
                       isThisDevice: $0.current)
        }
    }

    public func removeDevice(id: String) async throws(AccountError) {
        guard isSignedIn else { throw .server }
        let c = await ready()
        try await call { () async throws(ServerError) in try await c.revokeDevice(id: id) }
    }

    public func deleteAccount() async throws(AccountError) {
        guard isSignedIn else { throw .server }
        let c = await ready()
        try await call { () async throws(ServerError) in try await c.deleteMe() }
        isSignedIn = false
        do { try sessions.clear() } catch { throw .server }
    }

    public func signOutLocally() {
        isSignedIn = false
        // If the Keychain delete fails the stored session remains; the next launch restores it.
        try? sessions.clear()
    }

    // MARK: - Helpers

    /// The client for the current host, with the sign-out handler installed before first use.
    private func ready() async -> ServerClient {
        let c = working ?? makeClient(host)
        working = c
        if installedFor != ObjectIdentifier(c) {
            installedFor = ObjectIdentifier(c)
            await c.setOnUnauthorized { [weak self] in await self?.didSignOut() }
        }
        return c
    }

    private func adopt(_ c: ServerClient) {
        working = c
        installedFor = nil
    }

    private func didSignOut() {
        isSignedIn = false
        onSignedOut?()
    }

    private func register(_ options: CreationOptions) async throws(ServerError) -> RegistrationCredential {
        do { return try await passkeys.register(options) } catch { throw ServerError(status: 0, code: Self.accountCode(error)) }
    }

    private func save(_ s: NewSession) throws(AccountError) {
        do throws(ServerError) { try store(s) } catch { throw Self.map(error) }
    }

    private func store(_ s: NewSession) throws(ServerError) {
        do {
            try sessions.save(Session(host: host, accountID: s.accountID, deviceID: s.deviceID, token: s.sessionToken))
        } catch {
            throw ServerError(status: 0, code: "internal")
        }
        isSignedIn = true
    }

    private func call<T>(link: Bool = false, _ body: @MainActor () async throws(ServerError) -> T) async throws(AccountError) -> T {
        do throws(ServerError) { return try await body() } catch { throw Self.map(error, link: link) }
    }

    private struct ParsedLink { let linkID: String; let secret: String; let publicKey: Data }

    /// `https://<host>/link/<linkID>#<secret>.<publicKey b64url>`. The authority (host and port) must be
    /// exactly this device's server: another port, or user info (`own@evil`), is another server and
    /// nothing is sent anywhere.
    private func parseLink(_ raw: String) throws(AccountError) -> ParsedLink {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        // A code with no `scheme://` authority at its very start (`https:/link?…https://own`) is another server too.
        guard let mine = sessions.current?.host, let authority = Self.rawAuthority(text),
              authority.lowercased() == mine.lowercased() else { throw .wrongServer }
        // The host check above and the parsed URL below must agree: the text starts with exactly
        // `https://<host[:port]>/` and the parsed URL has a host.
        guard text.lowercased().hasPrefix("https://" + mine.lowercased() + "/"),
              let url = URL(string: text), url.host != nil else { throw .linkExpired }
        let parts = url.path.split(separator: "/")
        guard parts.count == 2, parts[0] == "link", let fragment = url.fragment, !fragment.isEmpty else { throw .linkExpired }
        // The QR public key is required and must be a 32-byte X25519 key; anything else is an invalid
        // code (shown as "start again on the new device") and nothing is sent to the server.
        let bits = fragment.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard bits.count == 2, !bits[0].isEmpty, let key = Base64URLData(encoded: bits[1])?.data, key.count == 32 else {
            throw .linkExpired
        }
        return ParsedLink(linkID: String(parts[1]), secret: bits[0], publicKey: key)
    }

    /// For `scheme://authority…` at the start of the text: the authority between `://` and the first `/`,
    /// `?` or `#`, exactly as written (host, and port if any). Nil when the text does not start that way.
    private nonisolated static func rawAuthority(_ text: String) -> String? {
        guard let sep = text.range(of: "://") else { return nil }
        let scheme = text[..<sep.lowerBound]
        guard let first = scheme.first, first.isASCII, first.isLetter,
              scheme.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "+-.".contains($0)) }) else { return nil }
        let rest = text[sep.upperBound...]
        let authority = rest[..<(rest.firstIndex { "/?#".contains($0) } ?? rest.endIndex)]
        return authority.isEmpty ? nil : String(authority)
    }

    @_spi(Testing) public nonisolated static func wire(_ p: DevicePlatform) -> String {
        switch p {
        case .iPhone: "iphone"
        case .mac: "mac"
        case .android: "android"
        }
    }

    /// Unknown strings stay `.mac`, as before Android existed.
    @_spi(Testing) public nonisolated static func platform(_ s: String) -> DevicePlatform {
        switch s {
        case "iphone": .iPhone
        case "android": .android
        default: .mac
        }
    }

    private static func accountCode(_ e: AccountError) -> String { "account:\(e)" }

    static func map(_ e: ServerError, link: Bool = false) -> AccountError {
        if e.code.hasPrefix("account:") { return fromAccountCode(e.code) }
        switch e.code {
        case "invite_required": return .inviteRequired
        case "invite_invalid": return .inviteInvalid
        case "username_taken": return .usernameTaken
        case "invalid_username": return .invalidUsername
        case "invalid_email": return .invalidEmail
        case "code_invalid": return .codeInvalid
        case "expired": return link ? .linkExpired : .codeExpired
        case "forbidden", "not_found": return link ? .linkExpired : .server
        case "rate_limited": return .tooManyAttempts(retryAfterSeconds: e.retryAfter)
        case "network": return .network
        case "cancelled": return .cancelled
        default: return .server
        }
    }

    private static func fromAccountCode(_ s: String) -> AccountError {
        switch s {
        case "account:cancelled": .cancelled
        case "account:passkeyUnavailable": .passkeyUnavailable
        default: .server
        }
    }
}

private extension AccountError {
    /// Lets `redeemInvite` reuse the one catch block.
    var asServerError: ServerError {
        ServerError(status: 0, code: self == .inviteInvalid ? "invite_invalid" : "internal")
    }
}
