// The iPhone/Mac interop peer (scripts/interop.sh). One command per line on stdin, one answer per line on
// stdout: `OK [values]` or `ERR <why>`. Logs go to stderr. The commands are the Android peer's
// (apps/android/interop/src/main/kotlin/org/example/app/interop/Main.kt lists them).
import AppCore
import CallCore
import Foundation
import LocalStore
import Messaging
import SecureMessaging
@_spi(Testing) import ServerKit
#if canImport(CryptoKit)
import CryptoKit
#endif
#if os(Linux)
import InteropLinuxMLS
#endif

struct PeerError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

func log(_ text: String) { FileHandle.standardError.write(Data((text + "\n").utf8)) }

func env(_ name: String) -> String {
    guard let value = ProcessInfo.processInfo.environment[name], !value.isEmpty else {
        log("\(name) is not set; run scripts/interop.sh")
        exit(2)
    }
    return value
}

// MARK: - Platform crypto

/// What differs between macOS (the app's CryptoKit code) and Linux (a local check: the Rust HPKE, no attachments).
struct PlatformCrypto {
    let escrow: EscrowSealer
    let attachments: AttachmentCipher
    let link: LinkCrypto
    let canSendAttachments: Bool

    static func make() -> PlatformCrypto {
        #if canImport(CryptoKit)
        PlatformCrypto(
            escrow: CryptoKitEscrowSealer(), attachments: CryptoKitAttachmentCipher(), link: DeviceLinkCrypto(),
            canSendAttachments: true)
        #else
        PlatformCrypto(escrow: RustEscrowSealer(), attachments: NoAttachmentCipher(), link: RustLinkCrypto(),
                       canSendAttachments: false)
        #endif
    }

    static func sha256(_ data: Data) throws -> String {
        #if canImport(CryptoKit)
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        #else
        throw PeerError("no SHA-256 without CryptoKit")
        #endif
    }
}

#if !canImport(CryptoKit)
/// Escrow copies through the Rust HPKE (the suite and info of `Escrow.seal`), as Android seals them.
struct RustEscrowSealer: EscrowSealer {
    func seal(_ plaintext: Data, to recipientKey: Data) throws -> Data {
        try hpkeSeal(recipientPublicKey: recipientKey, info: Data("escrow-v1".utf8), plaintext: plaintext)
    }
}

/// The link snapshot through the Rust HPKE (`DeviceLinkCrypto`'s suite and info). The private key handed to
/// the account service is the private key followed by the public key, which the Rust open needs too.
struct RustLinkCrypto: LinkCrypto {
    static let info = Data("link-v1".utf8)
    func makeKeyPair() throws(AccountError) -> (publicKey: Data, privateKey: Data) {
        guard let pair = try? hpkeGenerateKeypair() else { throw .server }
        return (pair.publicKey, pair.privateKey + pair.publicKey)
    }
    func seal(_ snapshot: Data, to publicKey: Data) throws(AccountError) -> Data {
        guard let sealed = try? hpkeSeal(recipientPublicKey: publicKey, info: Self.info, plaintext: snapshot) else { throw .server }
        return sealed
    }
    func open(_ sealed: Data, privateKey: Data) throws(AccountError) -> Data {
        guard privateKey.count == 64,
              let opened = try? hpkeOpen(privateKey: privateKey.prefix(32), publicKey: privateKey.suffix(32),
                                         info: Self.info, sealed: sealed) else { throw .server }
        return opened
    }
}

struct NoAttachmentCipher: AttachmentCipher {
    func encrypt(file: URL, to: URL) throws -> (key: Data, digest: Data, size: Int64, cipherSize: Int64) {
        throw PeerError("no attachment cipher without CryptoKit")
    }
    func decrypt(file: URL, to: URL, key: Data, digest: Data) throws {
        throw PeerError("no attachment cipher without CryptoKit")
    }
}
#endif

// MARK: - Account service stand-ins

/// Sign-up uses the email code only (`finishSignUpWithEmailCode`); no passkey is ever asked for.
struct NoPasskeys: PasskeyAuthenticating {
    func register(_ options: CreationOptions) async throws(AccountError) -> RegistrationCredential { throw .passkeyUnavailable }
    func assert(_ options: RequestOptions) async throws(AccountError) -> AssertionCredential { throw .passkeyUnavailable }
}

struct AlwaysConfirm: BiometricConfirming {
    func confirm(reason: String) async throws(AccountError) {}
}

struct LogNotifier: DeviceNotifier {
    func ownDeviceAdded(deviceID: String, name: String) async { log("own device added") }
}

/// Lets `ConversationManager` call the inbox processor that is built after it (as in the app's LiveMessaging).
final class SyncHook: @unchecked Sendable {
    private let lock = NSLock()
    private var action: (@Sendable () async throws -> Void)?
    func set(_ action: @escaping @Sendable () async throws -> Void) { lock.withLock { self.action = action } }
    func run() async throws { try await lock.withLock { action }?() }
}

/// The messaging graph, built once the account exists. The link snapshot reads it from another task.
final class GraphBox: @unchecked Sendable {
    private let lock = NSLock()
    private var sync: DeviceSync?
    func set(_ sync: DeviceSync) { lock.withLock { self.sync = sync } }
    var deviceSync: DeviceSync? { lock.withLock { sync } }
}

// MARK: - The peer

/// One iPhone/Mac device, wired like the app's `LiveMessaging`, with an in-memory session and signing key and
/// the inbox read by polling instead of the live socket.
@MainActor
final class Peer {
    let baseURL: URL
    let host: String
    let mailDir: URL
    let invite: String
    let dataDir: URL
    let crypto = PlatformCrypto.make()
    let sessions = SessionStore(storage: MemorySessionStorage())
    let box = GraphBox()
    let service: LiveAccountService

    var store: LiveChatStore?
    var deviceSync: DeviceSync?
    var repo: Repository?

    init() {
        baseURL = URL(string: env("INTEROP_BASE_URL"))!
        host = env("INTEROP_HOST")
        mailDir = URL(fileURLWithPath: env("INTEROP_MAIL_DIR"), isDirectory: true)
        invite = env("INTEROP_INVITE")
        dataDir = URL(fileURLWithPath: env("INTEROP_DATA"), isDirectory: true)
        let sessions = sessions
        let base = baseURL
        let box = box
        service = LiveAccountService(
            defaultHost: host, passkeyDomains: [], platform: .mac, deviceName: env("INTEROP_DEVICE"),
            sessions: sessions,
            makeClient: { _ in ServerClient(baseURL: base, session: sessions, transport: URLSessionTransport()) },
            passkeys: NoPasskeys(), biometrics: AlwaysConfirm(), approvalReason: "Approve this device",
            linkCrypto: crypto.link,
            linkSnapshot: { (try? await box.deviceSync?.exportSnapshot()) ?? Data() })
    }

    func signedIn() throws -> LiveChatStore {
        if let store { return store }
        guard let session = sessions.current, let client = service.client else { throw PeerError("not signed in") }
        let fm = FileManager.default
        try fm.createDirectory(at: dataDir, withIntermediateDirectories: true)
        let media = dataDir.appendingPathComponent("media", isDirectory: true)
        try fm.createDirectory(at: media, withIntermediateDirectories: true)
        let database = try Database(url: dataDir.appendingPathComponent("messages.sqlite"))
        try migrate(database)
        let now: @Sendable () -> Date = { Date() }
        let repo = Repository(db: database, now: now)
        let mls = try LiveMLSEngine(
            identity: "\(session.accountID)/\(session.deviceID)", storage: dataDir.appendingPathComponent("mls"),
            keys: InMemorySigningKeyStore())
        let deps = MessagingDeps(
            me: (session.accountID, session.deviceID), server: client, mls: mls, repo: repo,
            lock: ProcessLock(url: dataDir.appendingPathComponent("messages.lock")),
            escrow: crypto.escrow, attachments: crypto.attachments, notifier: LogNotifier(),
            mediaDirectory: media, now: now)
        let keys = EscrowKeyProvider(server: client)
        let hook = SyncHook()
        let conversations = ConversationManager(deps: deps, escrowKey: keys.closure, syncOnce: { try await hook.run() })
        let outbox = Outbox(deps: deps, conversations: conversations, keys: keys, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: now), controls: outbox)
        hook.set { _ = try await inbox.syncOnce() }
        let sync = DeviceSync(deps: deps, conversations: conversations, wipeMLS: {})
        let attachments = AttachmentService(
            deps: deps, outbox: outbox, network: { .wifi }, settings: { AutoDownloadSettings() })
        let store = LiveChatStore(
            deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: attachments)
        self.store = store
        self.deviceSync = sync
        self.repo = repo
        box.set(sync)
        return store
    }

    var me: String { sessions.current?.accountID ?? "" }

    // MARK: Commands

    func signup(username: String, email: String, displayName: String) async throws -> String {
        _ = try await service.connect(host: host)
        guard try await service.redeemInvite(code: invite) else { throw PeerError("invite refused") }
        let before = Mailbox.newest(mailDir)
        try await service.startSignUp(displayName: displayName, username: username, email: email)
        try await service.verifyEmail(code: try await Mailbox.code(in: mailDir, after: before))
        try await service.finishSignUpWithEmailCode()
        return try await start()
    }

    /// Key packages up, so others can add this device; then the first inbox read.
    private func start() async throws -> String {
        let store = try signedIn()
        try await deviceSync?.replenishKeyPackages()
        await store.syncInbox()
        return me
    }

    func add(username: String) async throws -> String {
        guard let contact = await try signedIn().addContact(username: username) else { throw PeerError("no user \(username)") }
        _ = try signedIn().ensureChat(with: contact.id)
        return contact.id
    }

    func send(_ peer: String, text: String, replyTo: String? = nil) async throws -> String {
        try signedIn().send(.text(text), in: peer, replyTo: replyTo)
        await settle()
        guard let sent = try messages(peer).last(where: { $0.senderID == me && $0.content == .text(text) }) else {
            throw PeerError("message not stored")
        }
        guard sent.status != .sending, sent.status != .failed else { throw PeerError("message not sent (\(sent.status))") }
        return sent.id
    }

    func waitText(_ peer: String, text: String, replyTo: String? = nil) async throws -> String {
        try await waitFor("text from \(peer)") {
            try self.messages(peer).last {
                $0.senderID == peer && $0.content == .text(text) && (replyTo == nil || $0.replyToID == replyTo)
            }?.id
        }
    }

    func react(_ peer: String, messageID: String, emoji: String) async throws {
        try signedIn().react(emoji, to: messageID, in: peer)
        await settle()
    }

    func waitReaction(_ peer: String, messageID: String, emoji: String) async throws {
        _ = try await waitFor("reaction") { try self.messages(peer).first { $0.id == messageID && $0.reactions.contains(emoji) } }
    }

    func edit(_ peer: String, messageID: String, text: String) async throws {
        try signedIn().edit(messageID: messageID, in: peer, text: text)
        await settle()
    }

    func waitEdit(_ peer: String, messageID: String, text: String) async throws {
        _ = try await waitFor("edit") {
            try self.messages(peer).first { $0.id == messageID && $0.editedAt != nil && $0.content == .text(text) }
        }
    }

    func read(_ peer: String) async throws {
        try await sync()
        try signedIn().markRead(chatID: peer)
        await settle()
    }

    func waitStatus(_ peer: String, messageID: String, wanted: String) async throws {
        let rank: (DeliveryStatus) -> Int = { s in
            switch s {
            case .sending, .failed: 0
            case .sent: 1
            case .delivered: 2
            case .read: 3
            }
        }
        let want: Int
        switch wanted {
        case "delivered": want = 2
        case "read": want = 3
        default: throw PeerError("unknown status \(wanted)")
        }
        _ = try await waitFor("status \(wanted)") { try self.messages(peer).first { $0.id == messageID && rank($0.status) >= want } }
    }

    /// Answers the message ID and the SHA-256 of the bytes sent: the re-encoded photo the sender keeps.
    func sendPhoto(_ peer: String, path: String) async throws -> String {
        guard crypto.canSendAttachments else { throw PeerError("attachments need CryptoKit (macOS)") }
        let before = Set(try messages(peer).map(\.id))
        try signedIn().sendAttachment(URL(fileURLWithPath: path), kind: .photo, in: peer)
        await settle()
        guard let sent = try messages(peer).last(where: {
            !before.contains($0.id) && $0.senderID == me && { if case .photo = $0.content { true } else { false } }($0)
        }) else { throw PeerError("photo not stored") }
        guard sent.status != .sending, sent.status != .failed else { throw PeerError("photo not sent (\(sent.status))") }
        guard let local = try repo?.attachment(messageID: sent.id)?.localPath else { throw PeerError("no local copy") }
        return "\(sent.id) \(try PlatformCrypto.sha256(try Data(contentsOf: URL(fileURLWithPath: local))))"
    }

    /// Downloads the photo message `messageID` and answers the SHA-256 of the decrypted bytes.
    func waitPhoto(_ peer: String, messageID: String) async throws -> String {
        guard crypto.canSendAttachments else { throw PeerError("attachments need CryptoKit (macOS)") }
        _ = try await waitFor("photo") { try self.messages(peer).first { $0.id == messageID } }
        try signedIn().download(messageID: messageID, in: peer)
        let path = try await waitFor("download") { () -> String? in
            guard let record = try self.repo?.attachment(messageID: messageID) else { return nil }
            if record.state == "failed" { throw PeerError("download or decryption failed") }
            return record.state == "local" ? record.localPath : nil
        }
        return try PlatformCrypto.sha256(try Data(contentsOf: URL(fileURLWithPath: path)))
    }

    func conversation(_ peer: String) throws -> String {
        _ = try signedIn()
        guard let record = try repo?.preferredConversation(chatID: peer) else { throw PeerError("no conversation with \(peer)") }
        return record.serverID
    }

    func safety(_ peer: String) async throws -> String {
        try await sync()
        guard let number = try signedIn().safetyNumber(for: peer) else { throw PeerError("no safety number") }
        return number.groups.joined()
    }

    func seal(_ peer: String, kind: String, callID: String, sdpPath: String?) async throws -> String {
        let payload: CallSignalPayload
        switch kind {
        case "ring":
            payload = CallSignalPayload(kind: .ring, callID: callID, video: false)
        case "offer":
            guard let sdpPath else { throw PeerError("offer needs an SDP file") }
            let sdp = try String(contentsOfFile: sdpPath, encoding: .utf8)
            payload = CallSignalPayload(kind: .offer, callID: callID, sdp: sdp, dtlsFingerprint: SDPMunger.fingerprint(in: sdp))
        default:
            throw PeerError("unknown kind \(kind)")
        }
        return try await signedIn().seal(payload, to: peer).base64EncodedString()
    }

    /// Answers `<kind> <callID>`, plus `fingerprint-ok` for an offer whose SDP passes the fingerprint check.
    func open(_ peer: String, sealed: String) async throws -> String {
        guard let data = Data(base64Encoded: sealed) else { throw PeerError("not base64") }
        let opened = try await signedIn().open(data, from: peer)
        guard !opened.senderDeviceID.isEmpty else { throw PeerError("no sender device") }
        let p = opened.payload
        if p.kind == .offer {
            guard FingerprintCheck.isValid(sdp: p.sdp, claimed: p.dtlsFingerprint) else { throw PeerError("fingerprint check failed") }
            return "offer \(p.callID) fingerprint-ok"
        }
        return "\(p.kind.rawValue) \(p.callID)"
    }

    func linkCode() async throws -> String {
        _ = try await service.connect(host: host)
        return try await service.makeLinkCode().qrText
    }

    /// Answers `<accountID> received -` once linked and the snapshot has arrived and opened. Nothing imports it:
    /// LiveAccountService leaves that to plan 7 and no app code does it yet, so neither does the peer.
    func linkWait() async throws -> String {
        _ = try await service.waitForLinkApproval()
        guard service.receivedLinkSnapshot != nil else { throw PeerError("no snapshot received") }
        let id = try await start()
        return "\(id) received -"
    }

    func approve(_ qrText: String) async throws {
        _ = try signedIn()
        let approval = try await service.approveLink(try await service.linkRequest(qrText: qrText))
        guard approval.snapshotSent else { throw PeerError("snapshot not sent") }
    }

    // MARK: Helpers

    private func messages(_ peer: String) throws -> [Message] {
        try signedIn().messages(in: peer, before: nil, limit: 200)
    }

    private func settle() async {
        guard let store else { return }
        await store.settle()
        await store.flushOutbox()
        await store.settle()
        store.refresh()
    }

    private func sync() async throws {
        await try signedIn().syncInbox()
        await settle()
    }

    /// Reads the inbox until `found` gives a value, for up to 60 s.
    private func waitFor<T>(_ what: String, _ found: () throws -> T?) async throws -> T {
        let deadline = Date().addingTimeInterval(60)
        while true {
            try await sync()
            if let value = try found() { return value }
            if Date() > deadline { throw PeerError("timed out waiting for \(what)") }
            try await Task.sleep(for: .milliseconds(250))
        }
    }
}

/// The SMTP sink's directory: `<n>.eml` per message.
enum Mailbox {
    static func newest(_ dir: URL) -> Int {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return names.filter { $0.hasSuffix(".eml") }.compactMap { Int($0.dropLast(4)) }.max() ?? 0
    }

    /// The six-digit code in the first message newer than `after`, waiting up to 10 s.
    static func code(in dir: URL, after: Int) async throws -> String {
        for _ in 0..<100 {
            let n = newest(dir)
            if n > after {
                let text = try String(contentsOf: dir.appendingPathComponent("\(n).eml"), encoding: .utf8)
                let re = try NSRegularExpression(pattern: #"is (\d{6})\."#)
                if let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
                   let r = Range(m.range(at: 1), in: text) {
                    return String(text[r])
                }
                throw PeerError("message \(n).eml has no code")
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw PeerError("no email arrived within 10 s")
    }
}

// MARK: - Command loop

@MainActor
func run(_ peer: Peer, _ line: String) async throws -> String {
    let name = line.split(separator: " ", maxSplits: 1).first.map(String.init) ?? ""
    let rest = line.dropFirst(name.count).drop { $0 == " " }
    /// The first `n` words after the command, then the rest of the line as one value.
    func args(_ n: Int) throws -> [String] {
        let parts = rest.split(separator: " ", maxSplits: n, omittingEmptySubsequences: false).map(String.init)
        guard parts.count == n + 1, !parts.contains(where: \.isEmpty) else { throw PeerError("usage: \(name) needs \(n + 1) values") }
        return parts
    }
    switch name {
    case "signup": let a = try args(2); return try await peer.signup(username: a[0], email: a[1], displayName: a[2])
    case "add": return try await peer.add(username: try args(0)[0])
    case "send": let a = try args(1); return try await peer.send(a[0], text: a[1])
    case "wait-text": let a = try args(1); return try await peer.waitText(a[0], text: a[1])
    case "reply": let a = try args(2); return try await peer.send(a[0], text: a[2], replyTo: a[1])
    case "wait-reply": let a = try args(2); return try await peer.waitText(a[0], text: a[2], replyTo: a[1])
    case "react": let a = try args(2); try await peer.react(a[0], messageID: a[1], emoji: a[2]); return ""
    case "wait-reaction": let a = try args(2); try await peer.waitReaction(a[0], messageID: a[1], emoji: a[2]); return ""
    case "edit": let a = try args(2); try await peer.edit(a[0], messageID: a[1], text: a[2]); return ""
    case "wait-edit": let a = try args(2); try await peer.waitEdit(a[0], messageID: a[1], text: a[2]); return ""
    case "read": try await peer.read(try args(0)[0]); return ""
    case "wait-status": let a = try args(2); try await peer.waitStatus(a[0], messageID: a[1], wanted: a[2]); return ""
    case "send-photo": let a = try args(1); return try await peer.sendPhoto(a[0], path: a[1])
    case "wait-photo": let a = try args(1); return try await peer.waitPhoto(a[0], messageID: a[1])
    case "conversation": return try peer.conversation(try args(0)[0])
    case "safety": return try await peer.safety(try args(0)[0])
    case "seal":
        let a = rest.split(separator: " ").map(String.init)
        guard (3...4).contains(a.count) else { throw PeerError("usage: seal <peer> <kind> <callID> [<sdp path>]") }
        return try await peer.seal(a[0], kind: a[1], callID: a[2], sdpPath: a.count == 4 ? a[3] : nil)
    case "open": let a = try args(1); return try await peer.open(a[0], sealed: a[1])
    case "link-code": return try await peer.linkCode()
    case "link-wait": return try await peer.linkWait()
    case "approve": try await peer.approve(try args(0)[0]); return ""
    default: throw PeerError("unknown command \(name)")
    }
}

let peer = Peer()
while true {
    // stdin is read off the main actor, so the store's main-actor tasks keep running while the peer waits.
    guard let raw = await Task.detached(operation: { readLine() }).value else { break }
    let line = raw.trimmingCharacters(in: .whitespaces)
    if line.isEmpty { continue }
    if line == "quit" { break }
    let answer: String
    do {
        let values = try await run(peer, line)
        answer = values.isEmpty ? "OK" : "OK \(values)"
    } catch {
        log("command failed: \(line.split(separator: " ").first ?? ""): \(error)")
        answer = "ERR \(error)".replacingOccurrences(of: "\n", with: " ")
    }
    FileHandle.standardOutput.write(Data((answer + "\n").utf8))
}
exit(0)
