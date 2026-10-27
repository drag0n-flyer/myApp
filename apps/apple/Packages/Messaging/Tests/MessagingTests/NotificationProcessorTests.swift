import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import Testing
@testable import Messaging
import MessagingTestKit

// Lane A's IncomingHandler and InboxProcessor are not merged yet: these tests use a small
// handler and inbox that follow the same contract (see the D2 report).

private struct TestHandler: IncomingApplying {
    var ownDeviceIDs: [String] = []

    func apply(_ incoming: Incoming, raw: RawInboxRecord, db: Repository) throws -> AppliedIncoming {
        switch incoming {
        case .application(let group, let sender, let plaintext, _):
            let account = String(sender.split(separator: "/").first ?? "")
            let env = ContentEnvelope.decode(plaintext)
            switch env.body {
            case .text, .photo, .voice, .file, .unsupported:
                let chatID = try db.conversation(groupID: group.raw)?.chatID ?? account
                if try db.chat(id: chatID) == nil {
                    try db.upsertChat(ChatRecord(id: chatID, isRequest: false, unread: 0, muted: false, lastActivity: testEpochDate))
                }
                let message = MessageRecord(
                    id: env.id, chatID: chatID, conversationID: nil, senderAccount: account, senderDevice: nil,
                    sentAt: testEpochDate, receivedAt: testEpochDate, envelope: plaintext, status: "delivered",
                    replyTo: nil, editedAt: nil, deleted: false, undecryptable: false, folded: "")
                try db.insertMessage(message)
                return .stored(message)
            case .callSignal(let payload): return .callSignal(payload, sender: sender)
            default: return .ignored
            }
        case .commit: return ownDeviceIDs.isEmpty ? .ignored : .ownDeviceAdded(deviceIDs: ownDeviceIDs)
        case .joined: return .ignored
        }
    }
}

private final class FakeInbox: InboxSyncing, @unchecked Sendable {
    private let lock = NSLock()
    private var calls: [Int] = []
    var onSync: (@Sendable () throws -> Void)?
    var recorded: [Int] { lock.withLock { calls } }
    func syncOnce(maxItems: Int) async throws -> Int {
        lock.withLock { calls.append(maxItems) }
        try onSync?()
        return 1
    }
}

private struct NoConversations: ConversationManaging {
    func preferredConversation(with peer: String) async throws -> ConversationRecord { throw MLSError.unknownGroup }
    func handleStaleEpoch(_ conversation: ConversationRecord) async throws {}
}

private func padded(_ payload: Data, bucket: Int = 256) -> Data {
    var out = Data([UInt8(payload.count >> 8), UInt8(payload.count & 0xff)]) + payload
    if out.count < bucket { out += Data(repeating: 0, count: bucket - out.count) }
    return out
}

private func envelope(_ id: String, _ body: ContentEnvelope.Body) -> Data {
    ContentEnvelope(id: id, at: 1_700_000_000_000, body: body).encoded()
}

private struct Pair {
    let ana: TestDevice
    let bob: TestDevice
    let group: GroupID
    let inbox = FakeInbox()

    /// Ana and bob share a group; bob knows ana as a contact (unless `contact` is false).
    init(contact: Bool = true) async throws {
        let server = FakeServer()
        ana = await makeDevice(server, account: "acc-ana", username: "ana")
        bob = await makeDevice(server, account: "acc-bob", username: "bob")
        let packages = try await bob.deps.mls.makeKeyPackages(count: 1)
        group = try await ana.deps.mls.createGroup()
        let bundle = try await ana.deps.mls.add(keyPackages: packages, to: group)
        try await ana.deps.mls.commitAccepted(group)
        _ = try await bob.deps.mls.process(try #require(bundle.welcome))
        try bob.deps.repo.upsertChat(ChatRecord(id: "acc-ana", isRequest: !contact, unread: 0, muted: false, lastActivity: testEpochDate))
        try bob.deps.repo.upsertConversation(ConversationRecord(
            serverID: "conv-1", chatID: "acc-ana", groupID: group.raw, mode: "e2ee", innerMode: "e2ee",
            modeMismatch: false, epoch: 1, active: true, createdAt: testEpochDate))
        if contact {
            try bob.deps.repo.upsertContact(ContactRecord(
                accountID: "acc-ana", username: "ana", displayName: "Ana", isContact: true, addedAt: testEpochDate))
        }
    }

    func push(_ body: ContentEnvelope.Body, id: String = "m1") async throws -> Data {
        padded(try await ana.deps.mls.encrypt(envelope(id, body), in: group).ciphertext)
    }

    func processor(_ device: TestDevice? = nil, handler: TestHandler = TestHandler()) -> NotificationProcessor {
        NotificationProcessor(deps: (device ?? bob).deps, handler: handler, inbox: inbox, conversations: NoConversations())
    }

    static let deadline = testEpochDate.addingTimeInterval(20)
}

@Suite("TestNotificationProcessor") struct TestNotificationProcessor {
    @Test func testShowsSenderAndPreview() async throws {
        let pair = try await Pair()
        let outcome = await pair.processor().process(pushData: try await pair.push(.text("Lunch?", replyTo: nil)), deadline: Pair.deadline)
        #expect(outcome == .show(title: "Ana", body: "Lunch?", threadID: "acc-ana"))
        #expect(try pair.bob.deps.repo.message(id: "m1") != nil)
    }

    @Test func testDuplicatePushDropsAndStoresOnce() async throws {
        // The "app syncs afterwards" half needs lane A's InboxProcessor; it is checked at merge.
        let pair = try await Pair()
        let data = try await pair.push(.text("Lunch?", replyTo: nil))
        let processor = pair.processor()
        _ = await processor.process(pushData: data, deadline: Pair.deadline)
        let again = await processor.process(pushData: data, deadline: Pair.deadline)
        #expect(again == .drop)
        #expect(try pair.bob.deps.repo.messages(chatID: "acc-ana", before: nil, limit: 10).count == 1)
        let payload = try Padding.unpad(data)
        #expect(try pair.bob.deps.repo.isProcessed(hash: PortableSHA256.hash(payload)))
    }

    @Test func testStrangerShowsRequest() async throws {
        let pair = try await Pair(contact: false)
        let outcome = await pair.processor().process(pushData: try await pair.push(.text("hi", replyTo: nil)), deadline: Pair.deadline)
        #expect(outcome == .show(title: "Message request", body: "Open [App] to see it", threadID: "acc-ana"))
    }

    @Test func testMutedDrops() async throws {
        let pair = try await Pair()
        try pair.bob.deps.repo.upsertChat(ChatRecord(id: "acc-ana", isRequest: false, unread: 0, muted: true, lastActivity: testEpochDate))
        let outcome = await pair.processor().process(pushData: try await pair.push(.text("hi", replyTo: nil)), deadline: Pair.deadline)
        #expect(outcome == .drop)
        #expect(try pair.bob.deps.repo.message(id: "m1") != nil)   // still stored
    }

    @Test func testBlockedDrops() async throws {
        let pair = try await Pair()
        try pair.bob.deps.repo.block("acc-ana")
        let outcome = await pair.processor().process(pushData: try await pair.push(.text("hi", replyTo: nil)), deadline: Pair.deadline)
        #expect(outcome == .drop)
    }

    @Test func testReceiptEditDeleteModeDrop() async throws {
        let pair = try await Pair()
        let bodies: [ContentEnvelope.Body] = [
            .receipt(.read, ids: ["x"]), .edit(target: "x", text: "y"), .delete(target: "x"), .mode("e2ee"),
        ]
        for (i, body) in bodies.enumerated() {
            let outcome = await pair.processor().process(
                pushData: try await pair.push(body, id: "r\(i)"), deadline: Pair.deadline)
            #expect(outcome == .drop)
        }
    }

    @Test func testReactionShowsEmoji() async throws {
        let pair = try await Pair()
        let outcome = await pair.processor().process(
            pushData: try await pair.push(.react(target: "x", emoji: "👍")), deadline: Pair.deadline)
        #expect(outcome == .show(title: "Ana", body: "Reacted 👍", threadID: "acc-ana"))
        let removed = await pair.processor().process(
            pushData: try await pair.push(.react(target: "x", emoji: ""), id: "m2"), deadline: Pair.deadline)
        #expect(removed == .drop)
    }

    @Test func testLockBusyFallsBack() async throws {
        let pair = try await Pair()
        let data = try await pair.push(.text("hi", replyTo: nil))
        let lock = pair.bob.deps.lock
        let acquired = DispatchSemaphore(value: 0)
        let holder = Thread {
            try? lock.withLock(timeout: .seconds(1)) { acquired.signal(); Thread.sleep(forTimeInterval: 3) }
        }
        holder.start()
        acquired.wait()
        let started = ContinuousClock.now
        let outcome = await pair.processor().process(pushData: data, deadline: Pair.deadline)
        #expect(outcome == .fallback)
        #expect(ContinuousClock.now - started < .milliseconds(2500))
        #expect(try pair.bob.deps.repo.message(id: "m1") == nil)
    }

    @Test func testEmptyPayloadSyncs() async throws {
        let pair = try await Pair()
        let repo = pair.bob.deps.repo
        pair.inbox.onSync = {
            try repo.upsertChat(ChatRecord(id: "acc-ana", isRequest: false, unread: 1, muted: false, lastActivity: testEpochDate))
            try repo.insertMessage(MessageRecord(
                id: "n1", chatID: "acc-ana", conversationID: nil, senderAccount: "acc-ana", senderDevice: nil,
                sentAt: testEpochDate, receivedAt: testEpochDate, envelope: envelope("n1", .text("Newest", replyTo: nil)),
                status: "delivered", replyTo: nil, editedAt: nil, deleted: false, undecryptable: false, folded: ""))
        }
        let outcome = await pair.processor().process(pushData: padded(Data()), deadline: Pair.deadline)
        #expect(pair.inbox.recorded == [20])
        #expect(outcome == .show(title: "Ana", body: "Newest", threadID: "acc-ana"))
    }

    @Test func testEmptyPayloadWithNothingNewFallsBack() async throws {
        let pair = try await Pair()
        let outcome = await pair.processor().process(pushData: padded(Data()), deadline: Pair.deadline)
        #expect(outcome == .fallback)
    }

    @Test func testDeadlinePassed() async throws {
        let pair = try await Pair()
        let data = try await pair.push(.text("hi", replyTo: nil))
        let outcome = await pair.processor().process(pushData: data, deadline: testEpochDate.addingTimeInterval(-1))
        #expect(outcome == .fallback)
        #expect(try pair.bob.deps.repo.message(id: "m1") == nil)
    }

    @Test func testMalformedAndUndecryptableFallBack() async throws {
        let pair = try await Pair()
        #expect(await pair.processor().process(pushData: Data([1]), deadline: Pair.deadline) == .fallback)
        let garbage = padded(Data("not an mls message".utf8))
        #expect(await pair.processor().process(pushData: garbage, deadline: Pair.deadline) == .fallback)
        // Nothing is marked processed, so the app still handles it from the inbox.
        let hash = PortableSHA256.hash(Data("not an mls message".utf8))
        #expect(try pair.bob.deps.repo.isProcessed(hash: hash) == false)
    }

    @Test func testCallSignalOutcome() async throws {
        let pair = try await Pair()
        let signal = CallSignalPayload(kind: .ring, callID: "call-1", video: false)
        let outcome = await pair.processor().process(pushData: try await pair.push(.callSignal(signal)), deadline: Pair.deadline)
        #expect(outcome == .incomingCall(signal, from: Contact(id: "acc-ana", displayName: "Ana", username: "ana")))

        let stranger = try await Pair(contact: false)
        let other = await stranger.processor().process(pushData: try await stranger.push(.callSignal(signal)), deadline: Pair.deadline)
        #expect(other == .incomingCall(signal, from: Contact(id: "acc-ana", displayName: "Unknown", username: "")))
    }

    @Test func testOwnDeviceAddedShows() async throws {
        let server = FakeServer()
        let ana1 = await makeDevice(server, account: "acc-ana", username: "ana")
        let ana2 = await makeDevice(server, account: "acc-ana", username: "ana")
        let ana3 = await makeDevice(server, account: "acc-ana", username: "ana")
        let group = try await ana1.deps.mls.createGroup()
        let first = try await ana1.deps.mls.add(keyPackages: try await ana2.deps.mls.makeKeyPackages(count: 1), to: group)
        try await ana1.deps.mls.commitAccepted(group)
        _ = try await ana2.deps.mls.process(try #require(first.welcome))
        let second = try await ana1.deps.mls.add(keyPackages: try await ana3.deps.mls.makeKeyPackages(count: 1), to: group)
        try await ana1.deps.mls.commitAccepted(group)

        let inbox = FakeInbox()
        let processor = NotificationProcessor(
            deps: ana2.deps, handler: TestHandler(ownDeviceIDs: [ana3.deviceID]), inbox: inbox, conversations: NoConversations())
        let outcome = await processor.process(pushData: padded(second.commit), deadline: Pair.deadline)
        #expect(outcome == .show(
            title: "New device added",
            body: "A new device can now read your new messages. If this wasn't you, remove it in Settings > Linked devices.",
            threadID: "devices"))
        #expect(try ana2.deps.repo.get("notified.device.\(ana3.deviceID)") != nil)
        // The processor shows the notice itself: it never calls the notifier.
        #expect(await ana2.notifier.added.isEmpty)

    }

    @Test func testOwnDeviceAlreadyNotifiedDrops() async throws {
        let pair = try await Pair()
        try pair.bob.deps.repo.set("notified.device.d9", Data([1]))
        let carol = await makeDevice(FakeServer(), account: "acc-bob", username: "bob")
        let packages = try await carol.deps.mls.makeKeyPackages(count: 1)
        let bundle = try await pair.ana.deps.mls.add(keyPackages: packages, to: pair.group)
        let outcome = await pair.processor(handler: TestHandler(ownDeviceIDs: ["d9"])).process(
            pushData: padded(bundle.commit), deadline: Pair.deadline)
        #expect(outcome == .drop)
    }
}
