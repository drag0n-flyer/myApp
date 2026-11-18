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

/// Answers uploads with the ID it is told to, so a hostile or broken server can be simulated.
final class UploadIDTransport: HTTPTransport, @unchecked Sendable {
    private let inner: HTTPTransport
    private let id: String
    init(_ inner: HTTPTransport, id: String) { self.inner = inner; self.id = id }
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await inner.send(request)
        guard request.httpMethod == "POST", request.url?.path == "/v1/attachments",
              var object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return (data, response) }
        object["id"] = id
        return (try JSONSerialization.data(withJSONObject: object), response)
    }
}

private actor Recorder: EnvelopeEnqueuing {
    var items: [ContentEnvelope] = []
    func enqueue(_ envelope: ContentEnvelope, chatID: String) throws { items.append(envelope) }
}

private let badIDs = ["../x", "a/b", "", String(repeating: "a", count: 200), "a.b", "a b", "x\u{0}y", "..", "é"]

@Suite("TestAttachmentID") struct TestAttachmentID {
    @Test func validAndInvalidIDs() {
        for good in ["att-1", "a_B-9", String(repeating: "z", count: 128)] { #expect(AttachmentID.isValid(good)) }
        for bad in badIDs { #expect(!AttachmentID.isValid(bad)) }
    }

    @Test func extensionsAreLimited() {
        #expect(AttachmentID.safeExtension(of: "report.PDF") == "pdf")
        #expect(AttachmentID.safeExtension(of: "a.tar.gz") == "gz")
        #expect(AttachmentID.safeExtension(of: "noext") == nil)
        #expect(AttachmentID.safeExtension(of: "x.a/b") == nil)
        #expect(AttachmentID.safeExtension(of: "x." + String(repeating: "a", count: 13)) == nil)
        #expect(AttachmentID.safeExtension(of: "x.p\u{202E}g") == nil)
        #expect(AttachmentService.fileExtension(kind: "file", name: "x.a b") == "bin")
        #expect(AttachmentService.fileExtension(kind: "photo", name: nil) == "jpg")
    }

    private func makeService(uploadID: String? = nil) async throws -> (AttachmentService, TestDevice, Recorder, MessagingDeps) {
        let server = FakeServer()
        let device = await makeDevice(server, account: "acc-a", username: "a")
        var deps = device.deps
        if let uploadID {
            deps.server = ServerClient(
                host: FakeServer.host,
                session: SessionStore(storage: MemorySessionStorage(
                    Session(host: FakeServer.host, accountID: device.accountID, deviceID: device.deviceID,
                            token: "test.\(device.accountID).\(device.deviceID)"))),
                transport: UploadIDTransport(await server.transport(for: device), id: uploadID))
        }
        let recorder = Recorder()
        let service = AttachmentService(deps: deps, outbox: recorder, network: { .wifi }, settings: { AutoDownloadSettings() })
        return (service, device, recorder, deps)
    }

    private func filesAround(_ device: TestDevice) -> [String] {
        let fm = FileManager.default
        var found: [String] = []
        for dir in [device.directory, device.deps.mediaDirectory] {
            found += ((try? fm.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0 != "media" && $0 != "messaging.lock" && !$0.hasPrefix("src") }
        }
        return found
    }

    @Test func sendRefusesABadUploadedIDBeforeAnyFileExists() async throws {
        for bad in badIDs {
            let (service, device, recorder, _) = try await makeService(uploadID: bad)
            let source = device.directory.appendingPathComponent("src-\(UUID().uuidString).bin")
            try Data("hello".utf8).write(to: source)
            await #expect(throws: AttachmentServiceError.invalidAttachmentID) {
                try await service.send(file: source, kind: .file, chatID: "acc-b", replyTo: nil, name: "n.bin")
            }
            #expect(filesAround(device).isEmpty, "no file for id \(bad.debugDescription): \(filesAround(device))")
            #expect(await recorder.items.isEmpty)
        }
    }

    @Test func downloadRefusesABadIDBeforeAnyFileExists() async throws {
        for bad in badIDs {
            let (service, device, _, deps) = try await makeService()
            try deps.repo.upsertChat(ChatRecord(id: "acc-b", isRequest: false, unread: 0, muted: false, lastActivity: testEpochDate))
            _ = try deps.repo.insertMessage(MessageRecord(
                id: "m", chatID: "acc-b", conversationID: nil, senderAccount: "acc-b", senderDevice: nil, sentAt: testEpochDate,
                receivedAt: testEpochDate, envelope: Data(), status: "delivered", replyTo: nil, editedAt: nil, deleted: false,
                undecryptable: false, folded: ""))
            try deps.repo.upsertAttachment(AttachmentRecord(
                id: bad, messageID: "m", kind: "file", key: Data(count: 32), digest: Data(count: 32), size: 5, cipherSize: 5,
                state: "remote", downloaded: 0, localPath: nil))
            await #expect(throws: AttachmentServiceError.invalidAttachmentID) { try await service.download(attachmentID: bad) }
            #expect(filesAround(device).isEmpty, "no file for id \(bad.debugDescription): \(filesAround(device))")
        }
    }

    @Test func aReceivedMessageWithABadIDIsStoredAsUnsupportedWithoutAnAttachmentRow() async throws {
        let server = FakeServer()
        let device = await makeDevice(server, account: "acc-me", username: "me")
        let repo = device.deps.repo
        try repo.upsertChat(ChatRecord(id: "acc-p", isRequest: false, unread: 0, muted: false, lastActivity: testEpochDate))
        try repo.upsertConversation(ConversationRecord(
            serverID: "conv", chatID: "acc-p", groupID: Data([1]), mode: "e2ee", innerMode: "e2ee", modeMismatch: false,
            epoch: 1, active: true, createdAt: testEpochDate))
        let handler = IncomingHandler(me: device.deps.me, now: device.deps.now, log: { _ in })
        for (n, bad) in badIDs.enumerated() {
            let att = Att(id: bad, key: Data(count: 32), dg: Data(count: 32), sz: 5, csz: 5)
            let envelope = ContentEnvelope(id: "m\(n)", at: 1_700_000_000_000, body: .file(att, name: "x.bin", mime: "x/y", replyTo: nil))
            let raw = RawInboxRecord(cursor: Int64(n + 1), kind: "message", conversationID: "conv", seq: 1, epoch: 1,
                                     senderAccount: "acc-p", senderDevice: "d", payload: Data(), sentAt: testEpochDate)
            _ = try handler.apply(.application(group: GroupID(raw: Data([1])), sender: "acc-p/d", plaintext: envelope.encoded(), epoch: 1),
                                  raw: raw, db: repo)
            let stored = try #require(try repo.message(id: "m\(n)"))
            #expect(ContentEnvelope.decode(stored.envelope).body == .unsupported(kind: "attachment"), "id \(bad.debugDescription)")
            #expect(try repo.attachment(messageID: "m\(n)") == nil)
        }
        // A good one still becomes an attachment row.
        let good = Att(id: "att-1", key: Data(count: 32), dg: Data(count: 32), sz: 5, csz: 5)
        let ok = ContentEnvelope(id: "ok", at: 1_700_000_000_000, body: .file(good, name: "x.bin", mime: "x/y", replyTo: nil))
        let raw = RawInboxRecord(cursor: 99, kind: "message", conversationID: "conv", seq: 1, epoch: 1,
                                 senderAccount: "acc-p", senderDevice: "d", payload: Data(), sentAt: testEpochDate)
        _ = try handler.apply(.application(group: GroupID(raw: Data([1])), sender: "acc-p/d", plaintext: ok.encoded(), epoch: 1), raw: raw, db: repo)
        #expect(try repo.attachment(messageID: "ok")?.id == "att-1")
    }
}
