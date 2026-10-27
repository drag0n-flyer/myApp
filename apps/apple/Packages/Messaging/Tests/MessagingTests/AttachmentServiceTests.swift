import AppCore
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import LocalStore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

private actor RecordingEnqueuer: EnvelopeEnqueuing {
    var items: [(envelope: ContentEnvelope, chatID: String)] = []
    let repo: Repository
    let me: String
    init(repo: Repository, me: String) { self.repo = repo; self.me = me }
    func enqueue(_ envelope: ContentEnvelope, chatID: String) throws {
        items.append((envelope, chatID))
        // Like the real outbox: the message row exists before the attachment row.
        _ = try repo.insertMessage(MessageRecord(
            id: envelope.id, chatID: chatID, conversationID: nil, senderAccount: me, senderDevice: nil,
            sentAt: testEpochDate, receivedAt: testEpochDate, envelope: envelope.encoded(), status: "sending",
            replyTo: nil, editedAt: nil, deleted: false, undecryptable: false, folded: ""))
    }
}

/// Passes requests through; the first attachment download may come back cut short.
private final class FlakyTransport: HTTPTransport, @unchecked Sendable {
    private let inner: HTTPTransport
    private let lock = NSLock()
    private var _ranges: [String] = []
    private var cutFirstDownloadAt: Int?
    private var forceStatus: Int?

    init(_ inner: HTTPTransport, cutFirstDownloadAt: Int? = nil, forceStatus: Int? = nil) {
        self.inner = inner
        self.cutFirstDownloadAt = cutFirstDownloadAt
        self.forceStatus = forceStatus
    }
    var ranges: [String] { lock.withLock { _ranges } }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        guard request.httpMethod == "GET", request.url?.path.hasPrefix("/v1/attachments/") == true else {
            return try await inner.send(request)
        }
        let range = request.value(forHTTPHeaderField: "Range") ?? ""
        let (cut, status) = lock.withLock { () -> (Int?, Int?) in
            _ranges.append(range)
            let cut = cutFirstDownloadAt
            cutFirstDownloadAt = nil
            return (cut, forceStatus)
        }
        if let status {
            let body = Data(#"{"error":{"code":"not_found","message":"gone"}}"#.utf8)
            return (body, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                          headerFields: ["Content-Type": "application/json"])!)
        }
        let (data, response) = try await inner.send(request)
        if let cut, data.count > cut { return (data.prefix(cut), response) }
        return (data, response)
    }
}

private struct Rig {
    let server: FakeServer
    let alice: TestDevice
    let bob: TestDevice
    let bobDeps: MessagingDeps
    let flaky: FlakyTransport
    let bobEnqueuer: RecordingEnqueuer
    let aliceEnqueuer: RecordingEnqueuer
}

private func makeRig(cut: Int? = nil, status: Int? = nil) async -> Rig {
    let server = FakeServer()
    let alice = await makeDevice(server, account: "acc-alice", username: "alice")
    let bob = await makeDevice(server, account: "acc-bob", username: "bob")
    for (device, peer) in [(alice, bob.accountID), (bob, alice.accountID)] {
        try? device.deps.repo.upsertChat(ChatRecord(id: peer, isRequest: false, unread: 0, muted: false, lastActivity: testEpochDate))
    }
    let flaky = FlakyTransport(await server.transport(for: bob), cutFirstDownloadAt: cut, forceStatus: status)
    var deps = bob.deps
    deps.server = ServerClient(
        host: FakeServer.host,
        session: SessionStore(storage: MemorySessionStorage(
            Session(host: FakeServer.host, accountID: bob.accountID, deviceID: bob.deviceID,
                    token: "test.\(bob.accountID).\(bob.deviceID)"))),
        transport: flaky)
    return Rig(server: server, alice: alice, bob: bob, bobDeps: deps, flaky: flaky,
               bobEnqueuer: RecordingEnqueuer(repo: bob.deps.repo, me: bob.accountID),
               aliceEnqueuer: RecordingEnqueuer(repo: alice.deps.repo, me: alice.accountID))
}

private func sampleFile(in dir: URL, size: Int, name: String = "pic.jpg") throws -> (URL, Data) {
    let data = Data((0..<size).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ 7) })
    let url = dir.appendingPathComponent(name)
    try data.write(to: url)
    return (url, data)
}

/// Alice sends `data` as `kind`; returns bob's stored message and the attachment as bob would
/// have it after the inbox processor ran (state `remote`).
private func deliver(_ rig: Rig, size: Int, kind: MediaKind, name: String = "pic.jpg") async throws -> (MessageRecord, Data) {
    let aliceService = AttachmentService(
        deps: rig.alice.deps, outbox: rig.aliceEnqueuer, network: { .wifi }, settings: { AutoDownloadSettings() })
    let (file, data) = try sampleFile(in: rig.alice.directory, size: size, name: name)
    try await aliceService.send(file: file, kind: kind, chatID: rig.bob.accountID, replyTo: nil, duration: 3)
    let queued = await rig.aliceEnqueuer.items
    let sent = queued[0].envelope
    let att: Att
    switch sent.body {
    case .photo(let a, _, _, _, _), .voice(let a, _, _), .file(let a, _, _, _): att = a
    default: throw AttachmentServiceError.unknownAttachment
    }
    let record = MessageRecord(
        id: sent.id, chatID: rig.alice.accountID, conversationID: nil, senderAccount: rig.alice.accountID,
        senderDevice: rig.alice.deviceID, sentAt: testEpochDate, receivedAt: testEpochDate, envelope: sent.encoded(),
        status: "delivered", replyTo: nil, editedAt: nil, deleted: false, undecryptable: false, folded: "")
    _ = try rig.bob.deps.repo.insertMessage(record)
    try rig.bob.deps.repo.upsertAttachment(AttachmentRecord(
        id: att.id, messageID: sent.id, kind: kind == .voice ? "voice" : kind == .photo ? "photo" : "file",
        key: att.key, digest: att.dg, size: att.sz, cipherSize: att.csz, state: "remote", downloaded: 0, localPath: nil))
    return (record, data)
}

@Suite("TestAttachmentService") struct TestAttachmentService {
    @Test func sendUploadsThenEnqueues() async throws {
        let rig = await makeRig()
        let (message, _) = try await deliver(rig, size: 100_000, kind: .photo)
        let bobService = AttachmentService(
            deps: rig.bobDeps, outbox: rig.bobEnqueuer, network: { .wifi }, settings: { AutoDownloadSettings() })
        let state = await bobService.mediaState(messageID: message.id)
        #expect(state?.phase == .remote)

        let sent = await rig.aliceEnqueuer.items
        #expect(sent.count == 1 && sent[0].chatID == rig.bob.accountID)
        guard case .photo(let att, _, _, _, _) = sent[0].envelope.body else { Issue.record("not a photo"); return }
        #expect(att.sz == 100_000 && att.csz == 100_000)   // the fake cipher adds no tag
        let stored = try rig.bob.deps.repo.attachment(id: att.id)
        #expect(stored?.size == 100_000)
        // The FakeServer holds the blob.
        let blob = try await rig.bob.deps.server.downloadAttachment(id: att.id)
        #expect(blob.count == 100_000)
        // Alice's own copy shows at once.
        let own = try rig.alice.deps.repo.attachment(id: att.id)
        #expect(own?.state == "local")
        #expect(own?.localPath.map { FileManager.default.fileExists(atPath: $0) } == true)
    }

    @Test func downloadResumes() async throws {
        let rig = await makeRig(cut: 70_000)
        let (message, data) = try await deliver(rig, size: 200_000, kind: .photo)
        let service = AttachmentService(
            deps: rig.bobDeps, outbox: rig.bobEnqueuer, network: { .wifi }, settings: { AutoDownloadSettings() })
        let id = try #require(try rig.bob.deps.repo.attachment(messageID: message.id)).id

        await #expect(throws: AttachmentServiceError.incomplete) { try await service.download(attachmentID: id) }
        let mid = try #require(try rig.bob.deps.repo.attachment(id: id))
        #expect(mid.state == "failed" && mid.downloaded == 70_000)

        try await service.download(attachmentID: id)
        #expect(rig.flaky.ranges == ["bytes=0-199999", "bytes=70000-199999"])
        let done = try #require(try rig.bob.deps.repo.attachment(id: id))
        #expect(done.state == "local")
        let path = try #require(done.localPath)
        #expect(path.hasSuffix("\(id).jpg"))
        #expect(try Data(contentsOf: URL(fileURLWithPath: path)) == data)
        #expect(await service.mediaState(messageID: message.id)?.phase == .local(URL(fileURLWithPath: path)))
        #expect(!FileManager.default.fileExists(atPath: rig.bobDeps.mediaDirectory.appendingPathComponent("\(id).part").path))
    }

    @Test func segmentsAreRangeRequests() async throws {
        let rig = await makeRig()
        let (message, data) = try await deliver(rig, size: 150_000, kind: .file, name: "notes.pdf")
        let service = AttachmentService(
            deps: rig.bobDeps, outbox: rig.bobEnqueuer, network: { .wifi }, settings: { AutoDownloadSettings() },
            segmentSize: 64_000)
        let id = try #require(try rig.bob.deps.repo.attachment(messageID: message.id)).id
        try await service.download(attachmentID: id)
        #expect(rig.flaky.ranges == ["bytes=0-63999", "bytes=64000-127999", "bytes=128000-149999"])
        let done = try #require(try rig.bob.deps.repo.attachment(id: id))
        #expect(try Data(contentsOf: URL(fileURLWithPath: try #require(done.localPath))) == data)
        #expect(done.localPath?.hasSuffix(".pdf") == true)
    }

    @Test func digestFailureMarksFailed() async throws {
        let rig = await makeRig()
        let (message, _) = try await deliver(rig, size: 100_000, kind: .photo)
        var record = try #require(try rig.bob.deps.repo.attachment(messageID: message.id))
        record.digest = Data(repeating: 0, count: 32)
        try rig.bob.deps.repo.upsertAttachment(record)
        let service = AttachmentService(
            deps: rig.bobDeps, outbox: rig.bobEnqueuer, network: { .wifi }, settings: { AutoDownloadSettings() })
        await #expect(throws: FakeAttachmentCipherError.digestMismatch) { try await service.download(attachmentID: record.id) }
        let after = try #require(try rig.bob.deps.repo.attachment(id: record.id))
        #expect(after.state == "failed" && after.downloaded == 0 && after.localPath == nil)
        #expect(!FileManager.default.fileExists(atPath: rig.bobDeps.mediaDirectory.appendingPathComponent("\(record.id).part").path))
    }

    @Test func autoDownloadPolicy() async throws {
        // Wi-Fi with .all: a photo downloads.
        let rig = await makeRig()
        let (photo, _) = try await deliver(rig, size: 50_000, kind: .photo)
        let settings: AutoDownloadSettings = {
            var s = AutoDownloadSettings()
            s.onWiFi = .all
            s.onMobile = .none
            return s
        }()
        let wifi = AttachmentService(deps: rig.bobDeps, outbox: rig.bobEnqueuer, network: { .wifi }, settings: { settings })
        await wifi.autoDownload(for: photo)
        #expect(try rig.bob.deps.repo.attachment(messageID: photo.id)?.state == "local")

        // Mobile with .none: a photo does not, voice does (voiceAlways).
        let rig2 = await makeRig()
        let (photo2, _) = try await deliver(rig2, size: 50_000, kind: .photo)
        let mobile = AttachmentService(deps: rig2.bobDeps, outbox: rig2.bobEnqueuer, network: { .mobile }, settings: { settings })
        await mobile.autoDownload(for: photo2)
        #expect(try rig2.bob.deps.repo.attachment(messageID: photo2.id)?.state == "remote")
        #expect(rig2.flaky.ranges.isEmpty)

        let rig3 = await makeRig()
        let (voice, _) = try await deliver(rig3, size: 20_000, kind: .voice, name: "v.caf")
        let mobile3 = AttachmentService(deps: rig3.bobDeps, outbox: rig3.bobEnqueuer, network: { .mobile }, settings: { settings })
        await mobile3.autoDownload(for: voice)
        #expect(try rig3.bob.deps.repo.attachment(messageID: voice.id)?.state == "local")

        // Offline: nothing; and with voiceAlways off, mobile voice waits.
        let rig4 = await makeRig()
        let (voice4, _) = try await deliver(rig4, size: 20_000, kind: .voice, name: "v.caf")
        let strict: AutoDownloadSettings = {
            var s = settings
            s.voiceAlways = false
            return s
        }()
        let off = AttachmentService(deps: rig4.bobDeps, outbox: rig4.bobEnqueuer, network: { .offline }, settings: { settings })
        await off.autoDownload(for: voice4)
        let strictMobile = AttachmentService(deps: rig4.bobDeps, outbox: rig4.bobEnqueuer, network: { .mobile }, settings: { strict })
        await strictMobile.autoDownload(for: voice4)
        #expect(try rig4.bob.deps.repo.attachment(messageID: voice4.id)?.state == "remote")
        #expect(rig4.flaky.ranges.isEmpty)
    }

    @Test func expiredAttachment404() async throws {
        let rig = await makeRig(status: 404)
        let (message, _) = try await deliver(rig, size: 100_000, kind: .photo)
        let service = AttachmentService(
            deps: rig.bobDeps, outbox: rig.bobEnqueuer, network: { .wifi }, settings: { AutoDownloadSettings() })
        let record = try #require(try rig.bob.deps.repo.attachment(messageID: message.id))
        await #expect(throws: ServerError.self) { try await service.download(attachmentID: record.id) }
        #expect(try rig.bob.deps.repo.attachment(id: record.id)?.state == "failed")
        #expect(rig.flaky.ranges.count == 1)   // one request, no retry loop

        // Auto-download does not retry a failed attachment on its own.
        await service.autoDownload(for: message)
        #expect(rig.flaky.ranges.count == 1)
    }

    @Test func progressNotificationsAreThrottled() async throws {
        let rig = await makeRig()
        let (message, _) = try await deliver(rig, size: 300_000, kind: .photo)
        let service = AttachmentService(
            deps: rig.bobDeps, outbox: rig.bobEnqueuer, network: { .wifi }, settings: { AutoDownloadSettings() },
            segmentSize: 10_000)
        let counter = Counter()
        await service.setOnChange { counter.bump() }
        let id = try #require(try rig.bob.deps.repo.attachment(messageID: message.id)).id
        try await service.download(attachmentID: id)
        // 30 segments with a frozen clock: one progress call plus the final state change.
        #expect(counter.value == 2)
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    func bump() { lock.lock(); n += 1; lock.unlock() }
    var value: Int { lock.lock(); defer { lock.unlock() }; return n }
}
