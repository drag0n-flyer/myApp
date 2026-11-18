import AppCore
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

/// Passes everything through, except attachment uploads: those can be held at a gate or made to fail.
private final class UploadGate: HTTPTransport, @unchecked Sendable {
    private let inner: HTTPTransport
    private let lock = NSLock()
    private var held = false
    private var waiting: [CheckedContinuation<Void, Never>] = []
    private var failures = 0
    private var _uploads = 0
    init(_ inner: HTTPTransport) { self.inner = inner }

    var uploads: Int { lock.withLock { _uploads } }
    func hold() { lock.withLock { held = true } }
    func failNext(_ count: Int = 1) { lock.withLock { failures = count } }
    func release() {
        let continuations = lock.withLock { () -> [CheckedContinuation<Void, Never>] in
            held = false
            defer { waiting = [] }
            return waiting
        }
        continuations.forEach { $0.resume() }
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        guard request.httpMethod == "POST", request.url?.path == "/v1/attachments" else { return try await inner.send(request) }
        lock.withLock { _uploads += 1 }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let resumeNow = lock.withLock { () -> Bool in
                if held { waiting.append(continuation); return false }
                return true
            }
            if resumeNow { continuation.resume() }
        }
        let fail = lock.withLock { () -> Bool in
            if failures > 0 { failures -= 1; return true }
            return false
        }
        if fail { throw URLError(.notConnectedToInternet) }
        return try await inner.send(request)
    }

    /// Waits until `count` uploads have reached the transport.
    func waitForUploads(_ count: Int) async {
        for _ in 0..<500 where uploads < count { try? await Task.sleep(for: .milliseconds(10)) }
    }
}

private final class Clock: @unchecked Sendable {
    private let lock = NSLock()
    private var current = testEpochDate
    func now() -> Date { lock.withLock { current } }
    func advance() { lock.withLock { current = current.addingTimeInterval(1) } }
}

@MainActor
private struct Side {
    let device: TestDevice
    let outbox: Outbox
    let store: LiveChatStore
    var repo: Repository { device.deps.repo }
    var media: URL { device.deps.mediaDirectory }
    var pending: URL { media.appendingPathComponent("pending") }

    init(_ device: TestDevice, withAttachments: Bool) {
        self.device = device
        let deps = device.deps
        let conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        let outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }),
            controls: outbox)
        let service = withAttachments
            ? AttachmentService(deps: deps, outbox: outbox, network: { .wifi }, settings: { AutoDownloadSettings() }) : nil
        self.outbox = outbox
        store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: service)
    }
}

@MainActor
private struct Rig {
    let server: FakeServer
    let gate: UploadGate
    var ana: Side
    let bob: Side

    static func make() async throws -> Rig {
        let server = FakeServer()
        let clock = Clock()
        var anaDevice = await makeDevice(server, account: "acc-ana", username: "ana", now: clock.now)
        let bobDevice = await makeDevice(server, account: "acc-bob", username: "bob", now: clock.now)
        for device in [anaDevice, bobDevice] {
            _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 3))
        }
        let gate = UploadGate(await server.transport(for: anaDevice))
        var deps = anaDevice.deps
        deps.server = ServerClient(
            host: FakeServer.host,
            session: SessionStore(storage: MemorySessionStorage(
                Session(host: FakeServer.host, accountID: anaDevice.accountID, deviceID: anaDevice.deviceID,
                        token: "test.\(anaDevice.accountID).\(anaDevice.deviceID)"))),
            transport: gate)
        anaDevice = TestDevice(accountID: anaDevice.accountID, deviceID: anaDevice.deviceID, deps: deps,
                               notifier: anaDevice.notifier, directory: anaDevice.directory)
        let ana = Side(anaDevice, withAttachments: true)
        _ = await ana.store.addContact(username: "bob")
        _ = ana.store.ensureChat(with: "acc-bob")
        return Rig(server: server, gate: gate, ana: ana, bob: Side(bobDevice, withAttachments: false))
    }

    func file(named name: String = "report.pdf", size: Int = 100_000) throws -> URL {
        let dir = ana.device.directory.appendingPathComponent("picked-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(name)
        try Data((0..<size).map { UInt8(truncatingIfNeeded: $0 &* 13 &+ 5) }).write(to: url)
        return url
    }

    var last: Message? { ana.store.chat(id: "acc-bob")?.messages.last }

    /// Picks a file and waits until its row shows (the copy happens off the main actor).
    func send() async throws {
        let before = ana.store.chat(id: "acc-bob")?.messages.count ?? 0
        ana.store.sendAttachment(try file(), kind: .file, in: "acc-bob")
        for _ in 0..<500 where (ana.store.chat(id: "acc-bob")?.messages.count ?? 0) == before {
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    func messagePosts() async -> Int {
        await server.requests().filter { $0.hasPrefix("POST /v1/conversations/") && $0.hasSuffix("/messages") }.count
    }

    func bobReceives() async -> Message? {
        await bob.store.syncInbox()
        await bob.store.settle()
        return bob.store.chat(id: "acc-ana")?.messages.last
    }

    func files(in dir: URL) -> [String] { ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).sorted() }
}

@Suite("TestMediaSend") @MainActor struct TestMediaSend {
    @Test func aMediaSendLeavesPinAndMuteAlone() async throws {
        let rig = try await Rig.make()
        #expect(rig.ana.store.setPinned("acc-bob", true))
        rig.ana.store.setMuted("acc-bob", true)
        try await rig.send()
        await rig.ana.store.settle()
        let chat = try #require(rig.ana.store.chat(id: "acc-bob"))
        #expect(chat.pinnedAt != nil && chat.muted && !chat.archived)
        #expect(rig.last?.status == .sent)
    }

    @Test func theRowIsSendingBeforeTheUploadFinishesWithRealNameAndSize() async throws {
        let rig = try await Rig.make()
        rig.gate.hold()
        try await rig.send()
        let sending = try #require(rig.last)
        #expect(sending.status == .sending)
        #expect(sending.content == .file(name: "report.pdf", bytes: 100_000))
        // The sender can already see the file.
        guard case .local(let shown)? = sending.media?.phase else { Issue.record("no local file while sending"); return }
        #expect(FileManager.default.fileExists(atPath: shown.path))
        // Nothing is queued and nothing reached the server's message queue yet.
        #expect(try rig.ana.repo.dueOutbox(at: .distantFuture).isEmpty)
        #expect(await rig.messagePosts() == 0)

        await rig.gate.waitForUploads(1)
        rig.gate.release()
        await rig.ana.store.settle()
        let sent = try #require(rig.last)
        #expect(sent.status == .sent)
        let peer = try #require(await rig.bobReceives())
        #expect(peer.content == .file(name: "report.pdf", bytes: 100_000))
        let theirs = try #require(try rig.bob.repo.attachment(messageID: peer.id))
        let mine = try #require(try rig.ana.repo.attachment(messageID: sent.id))
        #expect(!theirs.id.isEmpty && theirs.id == mine.id)
        #expect(mine.state == "local")
        #expect(FileManager.default.fileExists(atPath: try #require(mine.localPath)))
        #expect(rig.files(in: rig.ana.pending).isEmpty)
    }

    @Test func aFailedUploadLeavesTheRowFailedWithFilesKeptAndRetrySends() async throws {
        let rig = try await Rig.make()
        rig.gate.failNext()
        try await rig.send()
        let id = try #require(rig.last?.id)
        await rig.ana.store.settle()
        #expect(rig.last?.status == .failed)
        #expect(rig.files(in: rig.ana.pending.appendingPathComponent(id)).contains("meta.json"))
        #expect(try rig.ana.repo.dueOutbox(at: .distantFuture).isEmpty)
        #expect(await rig.messagePosts() == 0)
        if case .local(let url)? = rig.last?.media?.phase { #expect(FileManager.default.fileExists(atPath: url.path)) } else {
            Issue.record("the sender lost sight of the file")
        }

        rig.ana.store.retrySend(messageID: id)
        await rig.ana.store.settle()
        #expect(rig.last?.status == .sent)
        let peer = try #require(await rig.bobReceives())
        #expect(rig.bob.store.chat(id: "acc-ana")?.messages.count == 1, "the peer got it once")
        #expect(!(try #require(try rig.bob.repo.attachment(messageID: peer.id)).id.isEmpty))
        #expect(rig.files(in: rig.ana.pending).isEmpty)
    }

    @Test func twoRetriesUploadOnce() async throws {
        let rig = try await Rig.make()
        rig.gate.failNext()
        try await rig.send()
        let id = try #require(rig.last?.id)
        await rig.ana.store.settle()
        #expect(rig.gate.uploads == 1)

        rig.gate.hold()
        rig.ana.store.retrySend(messageID: id)
        await rig.gate.waitForUploads(2)
        rig.ana.store.retrySend(messageID: id)   // tapped again while the first is held
        rig.ana.store.retrySend(messageID: id)
        rig.gate.release()
        await rig.ana.store.settle()
        #expect(rig.gate.uploads == 2)
        #expect(rig.last?.status == .sent)
        #expect(await rig.bobReceives() != nil)
        #expect(rig.bob.store.chat(id: "acc-ana")?.messages.count == 1, "the peer got it once")
    }

    @Test func afterARestartTheRetryReallySends() async throws {
        let rig = try await Rig.make()
        rig.gate.failNext()
        try await rig.send()
        let id = try #require(rig.last?.id)
        await rig.ana.store.settle()
        try? FileManager.default.removeItem(at: rig.ana.device.directory.appendingPathComponent("tmp-sweep"))

        let restarted = Side(rig.ana.device, withAttachments: true)
        #expect(restarted.store.chat(id: "acc-bob")?.messages.last?.status == .failed)
        restarted.store.retrySend(messageID: id)
        await restarted.store.settle()
        #expect(restarted.store.chat(id: "acc-bob")?.messages.last?.status == .sent)
        let peer = try #require(await rig.bobReceives())
        let received = try #require(try rig.bob.repo.attachment(messageID: peer.id))
        let sentRecord = try #require(try rig.ana.repo.attachment(messageID: id))
        #expect(received.id == sentRecord.id && !received.id.isEmpty)
        #expect(peer.content == .file(name: "report.pdf", bytes: 100_000))
    }

    @Test func startUpFailsInterruptedUploadsAndCleansOrphanFolders() async throws {
        let rig = try await Rig.make()
        let at = testEpochDate
        func row(_ id: String, status: String, deleted: Bool = false) throws {
            let envelope = ContentEnvelope(id: id, at: 1_700_000_000_000, body: .file(
                Att(id: "", key: Data(), dg: Data(), sz: 5, csz: 5), name: "a.bin", mime: "x/y", replyTo: nil))
            _ = try rig.ana.repo.insertMessage(MessageRecord(
                id: id, chatID: "acc-bob", conversationID: nil, senderAccount: "acc-ana", senderDevice: nil, sentAt: at, receivedAt: at,
                envelope: envelope.encoded(), status: status, replyTo: nil, editedAt: nil, deleted: deleted, undecryptable: false, folded: ""))
        }
        func folder(_ id: String) throws {
            let dir = rig.ana.pending.appendingPathComponent(id)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try Data("{}".utf8).write(to: dir.appendingPathComponent("meta.json"))
            try Data("x".utf8).write(to: dir.appendingPathComponent("source.bin"))
        }
        try row("interrupted", status: "sending"); try folder("interrupted")
        try row("gone", status: "sending", deleted: true); try folder("gone")
        try folder("norow")
        let restarted = Side(rig.ana.device, withAttachments: true)
        #expect(restarted.store.chat(id: "acc-bob")?.messages.first { $0.id == "interrupted" }?.status == .failed)
        #expect(rig.files(in: rig.ana.pending) == ["interrupted"])
    }

    @Test func aPrepareFailureRemovesTheRow() async throws {
        let rig = try await Rig.make()
        // A failed upload whose source file is gone can never be sent: "Try again" removes the row and its folder.
        let envelope = ContentEnvelope(id: "gone1", at: 1_700_000_000_000, body: .file(
            Att(id: "", key: Data(), dg: Data(), sz: 5, csz: 5), name: "a.bin", mime: "x/y", replyTo: nil))
        let at = testEpochDate
        _ = try rig.ana.repo.insertMessage(MessageRecord(
            id: "gone1", chatID: "acc-bob", conversationID: nil, senderAccount: "acc-ana", senderDevice: nil, sentAt: at, receivedAt: at,
            envelope: envelope.encoded(), status: "failed", replyTo: nil, editedAt: nil, deleted: false, undecryptable: false, folded: ""))
        let folder = rig.ana.pending.appendingPathComponent("gone1")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try PendingUpload(kind: "file", chatID: "acc-bob", name: "a.bin", sourceName: "source.bin", mime: "x/y", duration: 0).write(to: folder)
        rig.ana.store.retrySend(messageID: "gone1")
        await rig.ana.store.settle()
        #expect(try rig.ana.repo.message(id: "gone1") == nil)
        #expect(rig.files(in: rig.ana.pending).isEmpty)
        #expect(rig.gate.uploads == 0)
        #expect(await rig.messagePosts() == 0)
    }

    @Test func aCrashBetweenTheCommitAndTheMoveKeepsTheFile() async throws {
        let rig = try await Rig.make()
        try await rig.send()
        let id = try #require(rig.last?.id)
        await rig.ana.store.settle()
        let record = try #require(try rig.ana.repo.attachment(messageID: id))
        let local = URL(fileURLWithPath: try #require(record.localPath))
        #expect(FileManager.default.fileExists(atPath: local.path))
        // The state after a crash: committed, but the plaintext still sits in the pending folder.
        let folder = rig.ana.pending.appendingPathComponent(id)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try PendingUpload(kind: "file", chatID: "acc-bob", name: "report.pdf", sourceName: "source.pdf", mime: "application/pdf", duration: 0)
            .write(to: folder)
        try FileManager.default.moveItem(at: local, to: folder.appendingPathComponent("source.pdf"))

        let restarted = Side(rig.ana.device, withAttachments: true)
        #expect(FileManager.default.fileExists(atPath: local.path), "the only copy was moved into place")
        #expect(rig.files(in: rig.ana.pending).isEmpty)
        let phase = restarted.store.chat(id: "acc-bob")?.messages.last?.media?.phase
        #expect(phase == .local(local))
        #expect(MediaBubbleState(media: restarted.store.chat(id: "acc-bob")?.messages.last?.media,
                                 fileExists: { FileManager.default.fileExists(atPath: $0.path) }) == .ready(local))
    }

    @Test func ifTheMoveCannotBeRedoneTheAttachmentBecomesRemote() async throws {
        let rig = try await Rig.make()
        try await rig.send()
        let id = try #require(rig.last?.id)
        await rig.ana.store.settle()
        let record = try #require(try rig.ana.repo.attachment(messageID: id))
        try FileManager.default.removeItem(atPath: try #require(record.localPath))
        let folder = rig.ana.pending.appendingPathComponent(id)   // nothing left to move
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try PendingUpload(kind: "file", chatID: "acc-bob", name: "report.pdf", sourceName: "source.pdf", mime: "application/pdf", duration: 0)
            .write(to: folder)
        _ = Side(rig.ana.device, withAttachments: true)
        #expect(try rig.ana.repo.attachment(messageID: id)?.state == "remote")
        #expect(rig.files(in: rig.ana.pending).isEmpty)
    }

    @Test func aDeleteAfterTheCommitSendsTheMessageThenTheDelete() async throws {
        let rig = try await Rig.make()
        try await rig.send()
        let id = try #require(rig.last?.id)
        await rig.ana.store.settle()
        #expect(rig.last?.status == .sent)
        rig.ana.store.delete(messageID: id, in: "acc-bob")
        await rig.ana.store.settle()
        let peer = try #require(await rig.bobReceives())
        #expect(peer.content == .deleted, "the peer never keeps a message the sender deleted")
    }

    @Test func deletingDuringAHeldUploadSendsNothingAndLeavesNothing() async throws {
        let rig = try await Rig.make()
        rig.gate.hold()
        try await rig.send()
        let id = try #require(rig.last?.id)
        await rig.gate.waitForUploads(1)
        rig.ana.store.delete(messageID: id, in: "acc-bob")
        rig.gate.release()
        await rig.ana.store.settle()
        #expect(await rig.messagePosts() == 0, "neither the message nor a delete went out")
        #expect(try rig.ana.repo.dueOutbox(at: .distantFuture).isEmpty)
        #expect(try rig.ana.repo.attachment(messageID: id) == nil)
        #expect(rig.files(in: rig.ana.pending).isEmpty)
        #expect(rig.files(in: rig.ana.media).isEmpty, "no plaintext left: \(rig.files(in: rig.ana.media))")
        #expect(await rig.bobReceives() == nil)
    }

    @Test func cancelDuringAHeldUploadLeavesNoMessageNoBlobAndDeliversNothing() async throws {
        let rig = try await Rig.make()
        rig.gate.hold()
        try await rig.send()
        let id = try #require(rig.last?.id)
        await rig.gate.waitForUploads(1)
        #expect(rig.last?.uploadProgress != nil, "an uploading row shows its fraction")
        rig.ana.store.cancelUpload(messageID: id, in: "acc-bob")
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.isEmpty == true, "the row is gone at once")
        rig.gate.release()
        await rig.ana.store.settle()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.isEmpty == true)
        #expect(try rig.ana.repo.message(id: id) == nil)
        #expect(try rig.ana.repo.attachment(messageID: id) == nil)
        #expect(try rig.ana.repo.dueOutbox(at: .distantFuture).isEmpty)
        #expect(await rig.messagePosts() == 0, "nothing was delivered")
        #expect(rig.files(in: rig.ana.pending).isEmpty)
        #expect(rig.files(in: rig.ana.media).isEmpty, "no staged or encrypted file left: \(rig.files(in: rig.ana.media))")
        #expect(await rig.bobReceives() == nil)
    }

    @Test func cancelOfAFailedUploadRemovesItToo() async throws {
        let rig = try await Rig.make()
        rig.gate.failNext()
        try await rig.send()
        let id = try #require(rig.last?.id)
        await rig.ana.store.settle()
        #expect(rig.last?.status == .failed)
        rig.ana.store.cancelUpload(messageID: id, in: "acc-bob")
        #expect(try rig.ana.repo.message(id: id) == nil)
        #expect(rig.files(in: rig.ana.pending).isEmpty)
        #expect(rig.files(in: rig.ana.media).isEmpty)
    }

    @Test func cancelAfterTheSendChangesNothing() async throws {
        let rig = try await Rig.make()
        try await rig.send()
        let id = try #require(rig.last?.id)
        await rig.ana.store.settle()
        #expect(rig.last?.status == .sent)
        #expect(rig.last?.uploadProgress == nil)
        let before = try #require(try rig.ana.repo.attachment(messageID: id))
        rig.ana.store.cancelUpload(messageID: id, in: "acc-bob")
        await rig.ana.store.settle()
        #expect(rig.last?.status == .sent, "the message stays")
        let after = try #require(try rig.ana.repo.attachment(messageID: id))
        #expect(after == before)
        #expect(FileManager.default.fileExists(atPath: try #require(after.localPath)))
        #expect(await rig.bobReceives() != nil)
    }

    @Test func cancelOfSomeoneElsesMessageDoesNothing() async throws {
        let rig = try await Rig.make()
        try await rig.send()
        await rig.ana.store.settle()
        let peer = try #require(await rig.bobReceives())
        rig.bob.store.cancelUpload(messageID: peer.id, in: "acc-ana")
        #expect(rig.bob.store.chat(id: "acc-ana")?.messages.count == 1)
    }

    @Test func aBatchSendsPhotoCaptionThenTheRestInOrder() async throws {
        let rig = try await Rig.make()
        let urls = try (0..<3).map { try rig.file(named: "p\($0).bin", size: 2_000) }
        rig.ana.store.sendMedia(urls.map { OutgoingMedia(url: $0, kind: .file) }, caption: "hi", in: "acc-bob")
        for _ in 0..<500 where (rig.ana.store.chat(id: "acc-bob")?.messages.count ?? 0) < 4 {
            try await Task.sleep(for: .milliseconds(10))
        }
        await rig.ana.store.settle()
        let kinds = (rig.ana.store.chat(id: "acc-bob")?.messages ?? []).map { message -> String in
            switch message.content {
            case .file(let name, _): name
            case .text(let text): text
            default: "?"
            }
        }
        #expect(kinds == ["p0.bin", "hi", "p1.bin", "p2.bin"])
    }

    @Test func aPlaceholderIsNeverQueued() async throws {
        let rig = try await Rig.make()
        let placeholder = ContentEnvelope(id: "p1", at: 1_700_000_000_000, body: .file(
            Att(id: "", key: Data(), dg: Data(), sz: 5, csz: 5), name: "a.bin", mime: "x/y", replyTo: nil))
        #expect(throws: AttachmentServiceError.invalidAttachmentID) { try rig.ana.outbox.enqueue(placeholder, chatID: "acc-bob") }
        #expect(try rig.ana.repo.message(id: "p1") == nil)

        // A failed placeholder row cannot be requeued either.
        let at = testEpochDate
        _ = try rig.ana.repo.insertMessage(MessageRecord(
            id: "p2", chatID: "acc-bob", conversationID: nil, senderAccount: "acc-ana", senderDevice: nil, sentAt: at, receivedAt: at,
            envelope: placeholder.encoded(), status: "failed", replyTo: nil, editedAt: nil, deleted: false, undecryptable: false, folded: ""))
        #expect(throws: AttachmentServiceError.invalidAttachmentID) { try rig.ana.outbox.retry(messageID: "p2") }
        #expect(try rig.ana.repo.dueOutbox(at: .distantFuture).isEmpty)

        // And one that got into the queue anyway is failed, not sent.
        try rig.ana.repo.enqueue(OutboxRecord(messageID: "p2", chatID: "acc-bob", envelope: placeholder.encoded(), attempts: 0, nextAttemptAt: at))
        try await rig.ana.outbox.flush()
        #expect(await rig.messagePosts() == 0)
        #expect(try rig.ana.repo.message(id: "p2")?.status == "failed")
        #expect(try rig.ana.repo.dueOutbox(at: .distantFuture).isEmpty)
    }
}
