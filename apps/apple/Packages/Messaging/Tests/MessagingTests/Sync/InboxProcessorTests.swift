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

private actor RecordingControls: ControlSender {
    private(set) var sent: [(body: ContentEnvelope.Body, chatID: String)] = []
    func sendControl(_ body: ContentEnvelope.Body, chatID: String) async { sent.append((body, chatID)) }
}

/// Blocks every `GET /v1/inbox` until `open()` is called, and reports when one has arrived.
private actor Gate {
    private var entered = false
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private(set) var count = 0
    func enter() async {
        entered = true
        count += 1
        if !isOpen { await withCheckedContinuation { waiters.append($0) } }
    }
    func open() { isOpen = true; waiters.forEach { $0.resume() }; waiters = [] }
    var hasEntered: Bool { entered }
}

private struct BlockingTransport: HTTPTransport {
    let inner: HTTPTransport
    let gate: Gate
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        if request.url?.path == "/v1/inbox", request.httpMethod == "GET" { await gate.enter() }
        return try await inner.send(request)
    }
}

private struct Rig {
    let server: FakeServer
    let ana1: TestDevice, ana2: TestDevice, bob1: TestDevice, bob2: TestDevice

    static func make(mode: String = "e2ee") async throws -> Rig {
        let server = FakeServer()
        await server.setDefaultMode(mode)
        let rig = Rig(
            server: server,
            ana1: await makeDevice(server, account: "acc-ana", username: "ana"),
            ana2: await makeDevice(server, account: "acc-ana", username: "ana"),
            bob1: await makeDevice(server, account: "acc-bob", username: "bob"),
            bob2: await makeDevice(server, account: "acc-bob", username: "bob"))
        for device in [rig.ana1, rig.ana2, rig.bob1, rig.bob2] {
            _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 3))
        }
        return rig
    }
}

private struct Side {
    let device: TestDevice
    let spy: SpyMLSEngine
    let deps: MessagingDeps
    let processor: InboxProcessor
    let handler: IncomingHandler
    let controls: RecordingControls

    init(_ device: TestDevice, deps override: ((MessagingDeps) -> MessagingDeps)? = nil) {
        var deps = device.deps
        spy = SpyMLSEngine(deps.mls)
        deps.mls = spy
        if let override { deps = override(deps) }
        self.device = device
        self.deps = deps
        handler = IncomingHandler(me: deps.me, now: deps.now, log: { _ in })
        controls = RecordingControls()
        processor = InboxProcessor(
            deps: deps, conversations: ConversationManager(deps: deps), handler: handler, controls: controls)
    }

    func texts(_ chat: String) throws -> [String] {
        try deps.repo.messages(chatID: chat, before: nil, limit: 1000).compactMap {
            if case .text(let t, _) = ContentEnvelope.decode($0.envelope).body, !$0.deleted { return t }
            return nil
        }
    }
}

/// Strictly increasing send times, so display order equals send order.
private enum Clock {
    private static let counter = LockedCounter()
    static func next() -> Int64 { counter.next() }
}
private final class LockedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 0
    func next() -> Int64 { lock.withLock { value += 1; return value } }
}

/// `sender` encrypts `body` in the conversation and posts it.
private func send(_ sender: TestDevice, _ record: ConversationRecord, _ body: ContentEnvelope.Body, id: String = UUID().uuidString.lowercased()) async throws {
    let envelope = ContentEnvelope(id: id, at: 1_700_000_000_000 + Clock.next(), body: body)
    let (ciphertext, epoch) = try await sender.deps.mls.encrypt(envelope.encoded(), in: GroupID(raw: record.groupID))
    _ = try await sender.deps.server.postMessage(
        conversationID: record.serverID, epoch: epoch, ciphertext: ciphertext, escrow: nil, clientMsgID: id)
}

private func startChat(_ rig: Rig) async throws -> ConversationRecord {
    try await ConversationManager(deps: rig.ana1.deps, escrowKey: { Data(repeating: 7, count: 32) }).create(with: "acc-bob")
}

private func raw(_ conv: ConversationRecord, cursor: Int64 = 900, kind: String = "message") -> RawInboxRecord {
    RawInboxRecord(cursor: cursor, kind: kind, conversationID: conv.serverID, seq: nil, epoch: 1, senderAccount: "acc-ana",
                   senderDevice: "d", payload: Data(), sentAt: testEpochDate)
}

@Suite("TestInboxProcessor") struct TestInboxProcessor {
    @Test func syncStoresAndAcks() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        for text in ["one", "two", "three"] { try await send(rig.ana1, record, .text(text, replyTo: nil)) }
        let count = try await bob.processor.syncOnce()
        #expect(count == 5)  // welcome, mode, 3 texts
        #expect(try bob.texts("acc-ana") == ["one", "two", "three"])
        #expect(try await rig.bob1.deps.server.inbox(after: nil).items.isEmpty)
        let items = await rig.server.storedMessages(in: record.serverID)
        #expect(items.count == 4)
        let cursor = try #require(try bob.deps.repo.get("inbox.cursor"))
        #expect(Int64(String(decoding: cursor, as: UTF8.self)) != nil)
        #expect(try bob.deps.repo.rawItems(limit: 10).isEmpty)
        let stored = try #require(try bob.deps.repo.conversation(serverID: record.serverID))
        #expect(stored.innerMode == "e2ee" && stored.active)
        #expect(try bob.deps.repo.chat(id: "acc-ana")?.unread == 3)
        // The stored cursor is the last one received: a second sync fetches nothing.
        #expect(try await bob.processor.syncOnce() == 0)
    }

    @Test func syncPagesPast100() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        let expected = (0..<250).map { "m\($0)" }
        for text in expected { try await send(rig.ana1, record, .text(text, replyTo: nil)) }
        _ = try await bob.processor.syncOnce()
        #expect(try bob.texts("acc-ana") == expected)
        #expect(try await rig.bob1.deps.server.inbox(after: nil).items.isEmpty)
    }

    @Test func maxItemsStopsFetching() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        for i in 0..<150 { try await send(rig.ana1, record, .text("m\(i)", replyTo: nil)) }
        #expect(try await bob.processor.syncOnce(maxItems: 100) == 100)
        #expect(try await bob.processor.syncOnce() == 52)
        #expect(try bob.texts("acc-ana").count == 150)
    }

    @Test func duplicatePayloadSkipped() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        _ = try await bob.processor.syncOnce()
        try await send(rig.ana1, record, .text("once", replyTo: nil))
        let payload = try #require(await rig.server.storedMessages(in: record.serverID).last).ciphertext
        try bob.deps.repo.markProcessed(hash: PortableSHA256.hash(payload))
        let before = bob.spy.processed.count
        _ = try await bob.processor.syncOnce()
        #expect(try bob.texts("acc-ana").isEmpty)
        #expect(bob.spy.processed.count == before)
        #expect(try bob.deps.repo.rawItems(limit: 10).isEmpty)
        #expect(try await rig.bob1.deps.server.inbox(after: nil).items.isEmpty)
    }

    @Test func undecryptablePlaceholder() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        _ = try await bob.processor.syncOnce()
        for text in ["a", "b", "c"] { try await send(rig.ana1, record, .text(text, replyTo: nil)) }
        let sent = await rig.server.storedMessages(in: record.serverID)
        bob.spy.fail(sent[2].ciphertext)  // "b"
        _ = try await bob.processor.syncOnce()
        let messages = try bob.deps.repo.messages(chatID: "acc-ana", before: nil, limit: 100)
        #expect(messages.count == 3)
        let placeholders = messages.filter(\.undecryptable)
        #expect(placeholders.count == 1 && placeholders[0].id.hasPrefix("undecryptable-"))
        #expect(try bob.texts("acc-ana") == ["a", "c"])
        await bob.processor.drainRaw()
        #expect(try bob.deps.repo.messages(chatID: "acc-ana", before: nil, limit: 100).count == 3)
        #expect(try bob.deps.repo.isProcessed(hash: PortableSHA256.hash(sent[2].ciphertext)))
        #expect(try bob.deps.repo.rawItems(limit: 10).isEmpty)
        // The placeholder is not receipted as a delivered message.
        let receipts = await bob.controls.sent
        #expect(receipts.count == 1)
    }

    @Test func editDeleteOnlyBySender() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        try await send(rig.ana1, record, .text("original", replyTo: nil), id: "m1")
        try await send(rig.ana1, record, .text("keep", replyTo: nil), id: "m2")
        _ = try await bob.processor.syncOnce()

        func apply(_ body: ContentEnvelope.Body, from sender: String) throws {
            let plaintext = ContentEnvelope(id: UUID().uuidString.lowercased(), at: 1_700_000_100_000, body: body).encoded()
            let incoming = Incoming.application(group: GroupID(raw: record.groupID), sender: sender, plaintext: plaintext, epoch: 1)
            _ = try bob.deps.repo.transaction { try bob.handler.apply(incoming, raw: raw(record), db: bob.deps.repo) }
        }
        try apply(.edit(target: "m1", text: "by carol"), from: "acc-carol/dev-c")
        try apply(.delete(target: "m2"), from: "acc-carol/dev-c")
        #expect(try bob.texts("acc-ana") == ["original", "keep"])
        #expect(try bob.deps.repo.message(id: "m1")?.editedAt == nil)

        try apply(.edit(target: "m1", text: "by ana2"), from: "acc-ana/\(rig.ana2.deviceID)")
        try apply(.delete(target: "m2"), from: "acc-ana/\(rig.ana2.deviceID)")
        #expect(try bob.texts("acc-ana") == ["by ana2"])
        #expect(try bob.deps.repo.message(id: "m1")?.editedAt != nil)
        #expect(try bob.deps.repo.message(id: "m2")?.deleted == true)
    }

    @Test func reactionsAndReceipts() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let ana = Side(rig.ana1)
        let bob = Side(rig.bob1)
        _ = try await bob.processor.syncOnce()
        try await send(rig.bob1, record, .text("from bob", replyTo: nil), id: "b1")
        try await send(rig.ana1, record, .text("from ana", replyTo: nil), id: "a1")
        _ = try await ana.processor.syncOnce()
        // ana's own message "a1" exists on ana only through the outbox; store it as the outbox would.
        try ana.deps.repo.insertMessage(MessageRecord(
            id: "a1", chatID: "acc-bob", conversationID: record.serverID, senderAccount: "acc-ana", senderDevice: rig.ana1.deviceID,
            sentAt: testEpochDate, receivedAt: testEpochDate, envelope: Data(), status: "sent", replyTo: nil, editedAt: nil,
            deleted: false, undecryptable: false, folded: ""))
        try await send(rig.bob1, record, .receipt(.delivered, ids: ["a1", "b1"]), id: "r1")
        try await send(rig.bob1, record, .react(target: "a1", emoji: "👍"), id: "x1")
        _ = try await ana.processor.syncOnce()
        // "b1" is bob's own message: a receipt from bob about it changes nothing on ana.
        #expect(try ana.deps.repo.message(id: "a1")?.status == "delivered")
        #expect(try ana.deps.repo.reactions(messageID: "a1").map(\.emoji) == ["👍"])
        _ = try await bob.processor.syncOnce()
        #expect(try bob.deps.repo.message(id: "a1")?.status == "delivered")
    }

    @Test func deliveredReceiptPerConversation() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        try await send(rig.ana1, record, .text("x", replyTo: nil), id: "t1")
        try await send(rig.ana1, record, .text("y", replyTo: nil), id: "t2")
        _ = try await bob.processor.syncOnce()
        let sent = await bob.controls.sent
        #expect(sent.count == 1 && sent[0].chatID == "acc-ana")
        #expect(sent[0].body == .receipt(.delivered, ids: ["t1", "t2"]))
    }

    @Test func removedMeDeactivates() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob2 = Side(rig.bob2)
        _ = try await bob2.processor.syncOnce()
        #expect(try bob2.deps.repo.conversation(serverID: record.serverID)?.active == true)

        let group = GroupID(raw: record.groupID)
        let bundle = try await rig.ana1.deps.mls.remove(members: ["acc-bob/\(rig.bob2.deviceID)"], from: group)
        _ = try await rig.ana1.deps.server.postCommit(
            conversationID: record.serverID, epoch: 1, commit: bundle.commit, welcome: nil, addDevices: [],
            removeDevices: [rig.bob2.deviceID])
        try await rig.ana1.deps.mls.commitAccepted(group)
        _ = try await bob2.processor.syncOnce()
        let after = try #require(try bob2.deps.repo.conversation(serverID: record.serverID))
        #expect(!after.active)
        #expect(after.innerMode == "e2ee")  // the pin survives
        // bob1 only sees an epoch change.
        let bob1 = Side(rig.bob1)
        _ = try await bob1.processor.syncOnce()
        let other = try #require(try bob1.deps.repo.conversation(serverID: record.serverID))
        #expect(other.active && other.epoch == 2)
    }

    @Test func lockNotHeldAcrossNetwork() async throws {
        let rig = try await Rig.make()
        _ = try await startChat(rig)
        let gate = Gate()
        let bobTransport = BlockingTransport(inner: await rig.server.transport(for: rig.bob1), gate: gate)
        let session = SessionStore(storage: MemorySessionStorage(Session(
            host: FakeServer.host, accountID: rig.bob1.accountID, deviceID: rig.bob1.deviceID,
            token: "test.\(rig.bob1.accountID).\(rig.bob1.deviceID)")))
        let bob = Side(rig.bob1) { deps in
            var deps = deps
            deps.server = ServerClient(host: FakeServer.host, session: session, transport: bobTransport)
            return deps
        }
        let running = Task { try await bob.processor.syncOnce() }
        while !(await gate.hasEntered) { try await Task.sleep(for: .milliseconds(5)) }
        // The request is in flight: the lock is free.
        let got = try bob.deps.lock.withLock(timeout: .milliseconds(50)) { 42 }
        #expect(got == 42)
        await gate.open()
        #expect(try await running.value == 2)
    }

    @Test func callSignalNotStored() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        _ = try await bob.processor.syncOnce()
        let payload = CallSignalPayload(kind: .ring, callID: "call-1", video: false)
        try await send(rig.ana1, record, .callSignal(payload), id: "cs1")
        let item = try #require(await rig.server.storedMessages(in: record.serverID).last)

        // Through the processor the inbox copy is dropped and its hash marked processed.
        _ = try await bob.processor.syncOnce()
        #expect(try bob.deps.repo.messages(chatID: "acc-ana", before: nil, limit: 10).isEmpty)
        #expect(try bob.deps.repo.isProcessed(hash: PortableSHA256.hash(item.ciphertext)))
        #expect(try bob.deps.repo.rawItems(limit: 10).isEmpty)

        // The handler reports it to the caller (D2 and the call code).
        let plaintext = ContentEnvelope(id: "cs1", at: 1, body: .callSignal(payload)).encoded()
        let result = try bob.handler.apply(
            .application(group: GroupID(raw: record.groupID), sender: "acc-ana/\(rig.ana1.deviceID)", plaintext: plaintext, epoch: 1),
            raw: raw(record), db: bob.deps.repo)
        #expect(result == .callSignal(payload, sender: "acc-ana/\(rig.ana1.deviceID)"))
    }

    @Test func ownCommitEchoSkipped() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let ana = Side(rig.ana1)
        let echo = try #require(try await rig.ana1.deps.server.inbox(after: nil).items.first)
        #expect(echo.kind == "commit")
        _ = try await ana.processor.syncOnce()
        #expect(ana.spy.processed.isEmpty)
        #expect(try ana.deps.repo.rawItems(limit: 10).isEmpty)
        #expect(try ana.deps.repo.isProcessed(hash: PortableSHA256.hash(echo.payload)))
        #expect(try ana.deps.repo.conversation(serverID: record.serverID)?.epoch == 1)
    }

    @Test func ownCommitEchoAfterCrash() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let ana = Side(rig.ana1)
        _ = try await ana.processor.syncOnce()

        // A third bob device is added; the app "crashes" after the server's 200, before commitAccepted.
        let bob3 = await makeDevice(rig.server, account: "acc-bob", username: "bob")
        _ = try await bob3.deps.server.uploadKeyPackages(try await bob3.deps.mls.makeKeyPackages(count: 1))
        let claimed = try await rig.ana1.deps.server.claimKeyPackages(accountID: "acc-bob").filter { $0.deviceID == bob3.deviceID }
        let group = GroupID(raw: record.groupID)
        let bundle = try await rig.ana1.deps.mls.add(keyPackages: claimed.map(\.keyPackage), to: group)
        _ = try await rig.ana1.deps.server.postCommit(
            conversationID: record.serverID, epoch: 1, commit: bundle.commit, welcome: bundle.welcome,
            addDevices: [bob3.deviceID], removeDevices: [])
        #expect(try ana.deps.repo.conversation(serverID: record.serverID)?.epoch == 1)

        _ = try await ana.processor.syncOnce()
        #expect(ana.spy.processed == [bundle.commit])
        #expect(try ana.deps.repo.conversation(serverID: record.serverID)?.epoch == 2)
        #expect(try await rig.ana1.deps.mls.epoch(of: group) == 2)
        #expect(try ana.deps.repo.rawItems(limit: 10).isEmpty)
    }

    @Test func modePinnedFromFirstEnvelope() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        _ = try await bob.processor.syncOnce()
        let stored = try #require(try bob.deps.repo.conversation(serverID: record.serverID))
        #expect(stored.mode == "e2ee" && stored.innerMode == "e2ee" && !stored.modeMismatch)
        #expect(try bob.deps.repo.messages(chatID: "acc-ana", before: nil, limit: 10).isEmpty)
    }

    @Test func joinedStaysUnpinnedUntilModeArrives() async throws {
        // A Welcome alone leaves `inner_mode` NULL.
        let rig = try await Rig.make(mode: "server_readable")
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        let welcome = try #require(try await rig.bob1.deps.server.inbox(after: nil).items.first { $0.kind == "welcome" })
        try bob.deps.repo.insertRaw([RawInboxRecord(
            cursor: welcome.cursor, kind: welcome.kind, conversationID: welcome.conversationID, seq: nil, epoch: welcome.epoch,
            senderAccount: welcome.senderAccountID, senderDevice: welcome.senderDeviceID, payload: welcome.payload,
            sentAt: welcome.sentAt)])
        await bob.processor.drainRaw()
        let stored = try #require(try bob.deps.repo.conversation(serverID: record.serverID))
        #expect(stored.mode == "server_readable" && stored.innerMode == nil)
    }

    @Test func modeChangeIgnoredAndFlagged() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let logged = LogBox()
        let bob = Side(rig.bob1)
        let handler = IncomingHandler(me: bob.deps.me, now: bob.deps.now, log: { logged.add($0) })
        let processor = InboxProcessor(deps: bob.deps, conversations: ConversationManager(deps: bob.deps), handler: handler)
        _ = try await processor.syncOnce()
        try await send(rig.ana1, record, .mode("server_readable"), id: "mm1")
        try await send(rig.ana1, record, .mode("server_readable"), id: "mm2")
        _ = try await processor.syncOnce()
        let conversation = try #require(try bob.deps.repo.conversation(serverID: record.serverID))
        #expect(conversation.innerMode == "e2ee" && conversation.modeMismatch)
        let messages = try bob.deps.repo.messages(chatID: "acc-ana", before: nil, limit: 10)
        #expect(messages.count == 1 && messages[0].senderAccount == "system")
        #expect(try JSONDecoder().decode(SystemEvent.self, from: messages[0].envelope) == .messagesPaused)
        let lines = logged.lines
        #expect(lines.count == 2 && lines.allSatisfy { $0 == "mode_mismatch conversation=\(record.serverID)" })
    }

    @Test func liveEventStoresAndDrains() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        _ = try await bob.processor.syncOnce()
        let events = await rig.server.events(for: rig.bob1.deviceID)
        let running = Task { await bob.processor.run(events: events) }
        try await Task.sleep(for: .milliseconds(100))
        try await send(rig.ana1, record, .text("live", replyTo: nil), id: "live1")
        for _ in 0..<200 {
            if try bob.texts("acc-ana") == ["live"] { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        running.cancel()
        #expect(try bob.texts("acc-ana") == ["live"])
    }
}

private final class LogBox: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [String] = []
    func add(_ line: String) { lock.withLock { storage.append(line) } }
    var lines: [String] { lock.withLock { storage } }
}

@Suite("TestInboxProcessorFixRound1") struct TestInboxProcessorFixRound1 {
    @Test func liveEventSyncsMissingEarlierItemFirst() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        _ = try await bob.processor.syncOnce()
        let (events, continuation) = AsyncStream<ServerEvent>.makeStream()
        let running = Task { await bob.processor.run(events: events) }
        try await Task.sleep(for: .milliseconds(100))
        try await send(rig.ana1, record, .text("first", replyTo: nil), id: "n1")
        try await send(rig.ana1, record, .text("second", replyTo: nil), id: "n2")
        // Only the later item arrives live; the earlier one was missed.
        let items = try await rig.bob1.deps.server.inbox(after: nil).items
        continuation.yield(.inbox(try #require(items.last)))
        for _ in 0..<200 {
            if try bob.texts("acc-ana").count == 2 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        continuation.finish()
        await running.value
        #expect(try bob.texts("acc-ana") == ["first", "second"])
        let order = bob.spy.processed.suffix(2)
        #expect(Array(order) == (await rig.server.storedMessages(in: record.serverID)).suffix(2).map(\.ciphertext))
        #expect(try await rig.bob1.deps.server.inbox(after: nil).items.isEmpty)
    }

    @Test func burstOfEventsIsCoalesced() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let gate = Gate()
        let session = SessionStore(storage: MemorySessionStorage(Session(
            host: FakeServer.host, accountID: rig.bob1.accountID, deviceID: rig.bob1.deviceID,
            token: "test.\(rig.bob1.accountID).\(rig.bob1.deviceID)")))
        let transport = BlockingTransport(inner: await rig.server.transport(for: rig.bob1), gate: gate)
        let bob = Side(rig.bob1) { deps in
            var deps = deps
            deps.server = ServerClient(host: FakeServer.host, session: session, transport: transport)
            return deps
        }
        for i in 0..<5 { try await send(rig.ana1, record, .text("b\(i)", replyTo: nil)) }
        let items = try await rig.bob1.deps.server.inbox(after: nil).items
        let (events, continuation) = AsyncStream<ServerEvent>.makeStream()
        let running = Task { await bob.processor.run(events: events) }
        // The start-up sync is stuck in its inbox request while the whole burst arrives.
        while !(await gate.hasEntered) { try await Task.sleep(for: .milliseconds(5)) }
        for item in items { continuation.yield(.inbox(item)) }
        continuation.finish()
        try await Task.sleep(for: .milliseconds(200))
        await gate.open()
        await running.value
        #expect(try bob.texts("acc-ana").count == 5)
        // One request for the start-up sync, one for the single extra sync. Not one per event.
        #expect(await gate.count == 2)
    }

    @Test func processedByAnotherProcessBetweenPreCheckAndLock() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        _ = try await bob.processor.syncOnce()
        try await send(rig.ana1, record, .text("raced", replyTo: nil), id: "r1")
        let item = try #require(try await rig.bob1.deps.server.inbox(after: nil).items.last)
        try bob.deps.repo.insertRaw([RawInboxRecord(
            cursor: item.cursor, kind: item.kind, conversationID: item.conversationID, seq: item.seq, epoch: item.epoch,
            senderAccount: item.senderAccountID, senderDevice: item.senderDeviceID, payload: item.payload, sentAt: item.sentAt)])

        // The "NSE" handles the payload after the app's pre-check and before it takes the lock.
        let repo = bob.deps.repo
        let hash = PortableSHA256.hash(item.payload)
        await bob.processor.setBeforeLockHook { _ in try? repo.markProcessed(hash: hash) }
        await bob.processor.drainRaw()
        #expect(!bob.spy.processed.contains(item.payload))
        #expect(try bob.deps.repo.messages(chatID: "acc-ana", before: nil, limit: 10).filter(\.undecryptable).isEmpty)
        #expect(try bob.deps.repo.rawItems(limit: 10).isEmpty)
    }

    @Test func welcomeNotProcessedUntilConversationStored() async throws {
        let rig = try await Rig.make()
        let record = try await startChat(rig)
        let bob = Side(rig.bob1)
        let welcome = try #require(try await rig.bob1.deps.server.inbox(after: nil).items.first { $0.kind == "welcome" })
        bob.spy.failMembers(true)
        _ = try? await bob.processor.syncOnce()
        // Nothing is stored, the Welcome is neither processed nor dropped, and nothing behind it ran.
        #expect(try bob.deps.repo.conversation(serverID: record.serverID) == nil)
        #expect(try bob.deps.repo.chat(id: "acc-ana") == nil)
        #expect(try !bob.deps.repo.isProcessed(hash: PortableSHA256.hash(welcome.payload)))
        #expect(try bob.deps.repo.rawItems(limit: 10).count == 2)

        bob.spy.failMembers(false)
        await bob.processor.drainRaw()
        let stored = try #require(try bob.deps.repo.conversation(serverID: record.serverID))
        #expect(stored.innerMode == "e2ee")
        #expect(try bob.deps.repo.isProcessed(hash: PortableSHA256.hash(welcome.payload)))
        #expect(try bob.deps.repo.rawItems(limit: 10).isEmpty)
        #expect(bob.spy.processed.filter { $0 == welcome.payload }.count == 1)  // not processed twice
    }

    @Test func welcomeWithoutPeerIsDroppedWithoutRows() async throws {
        let rig = try await Rig.make()
        let bob1 = Side(rig.bob1)
        // bob2 makes a conversation with ana and adds only bob1: no member of another account stays in the group.
        let created = try await rig.bob2.deps.server.createConversation(with: "acc-ana")
        let group = try await rig.bob2.deps.mls.createGroup()
        let claimed = try await rig.bob2.deps.server.claimKeyPackages(accountID: "acc-bob").filter { $0.deviceID == rig.bob1.deviceID }
        let bundle = try await rig.bob2.deps.mls.add(keyPackages: claimed.map(\.keyPackage), to: group)
        _ = try await rig.bob2.deps.server.postCommit(
            conversationID: created.conversationID, epoch: 0, commit: bundle.commit, welcome: bundle.welcome,
            addDevices: [rig.bob1.deviceID], removeDevices: [])
        _ = try await bob1.processor.syncOnce()
        #expect(try bob1.deps.repo.conversation(serverID: created.conversationID) == nil)
        #expect(try bob1.deps.repo.chats().isEmpty)
        #expect(try bob1.deps.repo.rawItems(limit: 10).isEmpty)
        #expect(try bob1.deps.repo.isProcessed(hash: PortableSHA256.hash(bundle.welcome ?? Data())))
    }
}
