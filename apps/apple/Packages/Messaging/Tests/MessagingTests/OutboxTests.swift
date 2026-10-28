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

// MARK: - Helpers

final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current = testEpochDate
    var now: Date { lock.withLock { current } }
    func advance(_ seconds: TimeInterval) { lock.withLock { current = current.addingTimeInterval(seconds) } }
}

/// Wraps the fake server's transport: records message POSTs, can fail them, and can lie about the mode.
final class ProbeTransport: HTTPTransport, @unchecked Sendable {
    private let inner: HTTPTransport
    private let lock = NSLock()
    private var _posts: [[String: Any]] = []
    private var failures = 0
    private var _lieMode: String?
    private var _rejectAllWith: String?

    init(_ inner: HTTPTransport) { self.inner = inner }

    var posts: [[String: Any]] { lock.withLock { _posts } }
    func failNext(_ n: Int) { lock.withLock { failures = n } }
    /// `GET /v1/conversations/{id}` reports `mode`, and every message POST answers 409 with `code`.
    func lie(mode: String, rejectingWith code: String) { lock.withLock { _lieMode = mode; _rejectAllWith = code } }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let path = request.url?.path ?? ""
        let method = request.httpMethod ?? "GET"
        let isPost = method == "POST" && path.hasSuffix("/messages")
        if isPost {
            let body = (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: Any] ?? [:]
            let (fail, reject): (Bool, String?) = lock.withLock {
                _posts.append(body)
                if failures > 0 { failures -= 1; return (true, nil) }
                return (false, _rejectAllWith)
            }
            if fail { throw URLError(.notConnectedToInternet) }
            if let reject { return Self.reply(400, #"{"error":{"code":"\#(reject)","message":"x"}}"#, for: request) }
        }
        if method == "GET", path.hasPrefix("/v1/conversations/"), let mode = lock.withLock({ _lieMode }) {
            let id = String(path.dropFirst("/v1/conversations/".count))
            return Self.reply(200, #"{"conversationID":"\#(id)","mode":"\#(mode)","epoch":1}"#, for: request)
        }
        return try await inner.send(request)
    }

    private static func reply(_ status: Int, _ json: String, for request: URLRequest) -> (Data, HTTPURLResponse) {
        (Data(json.utf8), HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                          headerFields: ["Content-Type": "application/json"])!)
    }
}

final class CountingSealer: EscrowSealer, @unchecked Sendable {
    private let lock = NSLock()
    private var _calls = 0
    var calls: Int { lock.withLock { _calls } }
    func seal(_ plaintext: Data, to recipientKey: Data) throws -> Data {
        lock.withLock { _calls += 1 }
        return try FakeEscrowSealer().seal(plaintext, to: recipientKey)
    }
}

/// Lane A's manager is not available here; this one reads the repository.
final class RepoConversations: ConversationManaging, @unchecked Sendable {
    let repo: Repository
    private let lock = NSLock()
    private var _staleCalls = 0
    var staleCalls: Int { lock.withLock { _staleCalls } }
    init(repo: Repository) { self.repo = repo }
    func preferredConversation(with peer: String) async throws -> ConversationRecord {
        guard let c = try repo.preferredConversation(chatID: peer) else { throw MLSError.unknownGroup }
        return c
    }
    func handleStaleEpoch(_ conversation: ConversationRecord) async throws { lock.withLock { _staleCalls += 1 } }
}

struct Pair {
    let server: FakeServer
    let ana: TestDevice
    let bob: TestDevice
    let conversation: ConversationRecord
    let probe: ProbeTransport
    let sealer: CountingSealer
    let clock: TestClock
    let outbox: Outbox
    let conversations: RepoConversations

    var repo: Repository { ana.deps.repo }
    var chatID: String { bob.accountID }

    func envelope(_ text: String, id: String = UUID().uuidString.lowercased()) -> ContentEnvelope {
        ContentEnvelope(id: id, at: 1_700_000_000_000, body: .text(text, replyTo: nil))
    }

    func messagePosts() -> [[String: Any]] { probe.posts }

    func outboxRow(_ id: String) throws -> OutboxRecord? {
        try repo.dueOutbox(at: Date.distantFuture).first { $0.messageID == id }
    }

    /// What bob receives: the application messages in his inbox, deduplicated by envelope ID.
    func bobTexts() async throws -> [String] {
        var seen: Set<String> = []
        var texts: [String] = []
        for item in try await bob.deps.server.inbox(after: nil).items where item.kind == "message" {
            guard case .application(_, _, let plaintext, _) = try await bob.deps.mls.process(item.payload) else { continue }
            let env = ContentEnvelope.decode(plaintext)
            if case .text(let t, _) = env.body, seen.insert(env.id).inserted { texts.append(t) }
        }
        return texts
    }
}

/// ana and bob share one conversation (set up through the fake server). `serverMode` is the
/// server's mode; `inner` is the pinned `inner_mode` ana has stored.
func makePair(serverMode: String, inner: String?, clock: TestClock = TestClock(), database: Database? = nil) async throws -> Pair {
    let server = FakeServer(now: { clock.now })
    await server.setDefaultMode(serverMode)
    let clockNow: @Sendable () -> Date = { clock.now }
    let ana0 = await makeDevice(server, account: "acc-ana", username: "ana", now: clockNow)
    let bob = await makeDevice(server, account: "acc-bob", username: "bob", now: clockNow)

    let probe = ProbeTransport(await server.transport(accountID: ana0.accountID, deviceID: ana0.deviceID))
    let sealer = CountingSealer()
    var deps = ana0.deps
    deps.server = ServerClient(
        host: FakeServer.host,
        session: SessionStore(storage: MemorySessionStorage(
            Session(host: FakeServer.host, accountID: ana0.accountID, deviceID: ana0.deviceID,
                    token: "test.\(ana0.accountID).\(ana0.deviceID)"))),
        transport: probe)
    deps.escrow = sealer
    if let database {
        try migrate(database)
        deps.repo = Repository(db: database, now: clockNow)
    }
    let ana = TestDevice(accountID: ana0.accountID, deviceID: ana0.deviceID, deps: deps, notifier: ana0.notifier, directory: ana0.directory)

    _ = try await bob.deps.server.uploadKeyPackages(try await bob.deps.mls.makeKeyPackages(count: 2))
    let claimed = try await ana.deps.server.claimKeyPackages(accountID: bob.accountID)
    let created = try await ana.deps.server.createConversation(with: bob.accountID)
    let group = try await ana.deps.mls.createGroup()
    let bundle = try await ana.deps.mls.add(keyPackages: claimed.map(\.keyPackage), to: group)
    _ = try await ana.deps.server.postCommit(
        conversationID: created.conversationID, epoch: 0, commit: bundle.commit, welcome: bundle.welcome,
        addDevices: [bob.deviceID], removeDevices: [])
    try await ana.deps.mls.commitAccepted(group)
    for item in try await bob.deps.server.inbox(after: nil).items where item.kind == "welcome" {
        _ = try await bob.deps.mls.process(item.payload)
    }

    try ana.deps.repo.upsertChat(ChatRecord(id: bob.accountID, isRequest: false, unread: 0, muted: false, lastActivity: clock.now))
    let record = ConversationRecord(
        serverID: created.conversationID, chatID: bob.accountID, groupID: group.raw, mode: created.mode, innerMode: inner,
        modeMismatch: false, epoch: 1, active: true, createdAt: clock.now)
    try ana.deps.repo.upsertConversation(record)
    let conversations = RepoConversations(repo: ana.deps.repo)
    return Pair(server: server, ana: ana, bob: bob, conversation: record, probe: probe, sealer: sealer, clock: clock,
                outbox: Outbox(deps: ana.deps, conversations: conversations, readReceiptsEnabled: { true }), conversations: conversations)
}

// MARK: - Tests

@Suite("TestOutbox") struct TestOutbox {
    @Test func sendReachesPeer() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        let env = p.envelope("hello bob")
        try await p.outbox.enqueue(env, chatID: p.chatID)
        #expect(try p.repo.message(id: env.id)?.status == "sending")
        try await p.outbox.flush()
        #expect(try p.repo.message(id: env.id)?.status == "sent")
        #expect(try p.outboxRow(env.id) == nil)
        #expect(try await p.bobTexts() == ["hello bob"])
    }

    @Test func retryWithBackoff() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        let env = p.envelope("retry me")
        try await p.outbox.enqueue(env, chatID: p.chatID)
        p.probe.failNext(2)

        try await p.outbox.flush()                                   // attempt 1 fails, next in 1 s
        #expect(try p.outboxRow(env.id)?.attempts == 1)
        try await p.outbox.flush()                                   // not due yet
        #expect(p.messagePosts().count == 1)
        p.clock.advance(1)
        try await p.outbox.flush()                                   // attempt 2 fails, next in 2 s
        #expect(try p.outboxRow(env.id)?.attempts == 2)
        p.clock.advance(1)
        try await p.outbox.flush()
        #expect(p.messagePosts().count == 2)                     // still backing off
        p.clock.advance(1)
        try await p.outbox.flush()                                   // third attempt succeeds
        #expect(p.messagePosts().count == 3)
        #expect(try p.repo.message(id: env.id)?.status == "sent")
        #expect(try p.outboxRow(env.id) == nil)
    }

    @Test func sameClientMsgIDOnRetry() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        let env = p.envelope("once only")
        try await p.outbox.enqueue(env, chatID: p.chatID)
        p.probe.failNext(1)
        try await p.outbox.flush()
        p.clock.advance(5)
        try await p.outbox.flush()
        let ids = p.messagePosts().compactMap { $0["clientMsgID"] as? String }
        #expect(ids == [env.id, env.id])
        #expect(try await p.bobTexts() == ["once only"])
    }

    struct Row: Sendable, CustomStringConvertible {
        let inner: String?, server: String
        let posts: Bool, escrow: Bool, status: String, mismatch: Bool
        var description: String { "inner=\(inner ?? "nil") server=\(server)" }
    }

    @Test(arguments: [
        Row(inner: "server_readable", server: "server_readable", posts: true, escrow: true, status: "sent", mismatch: false),
        Row(inner: "e2ee", server: "e2ee", posts: true, escrow: false, status: "sent", mismatch: false),
        Row(inner: "e2ee", server: "server_readable", posts: false, escrow: false, status: "failed", mismatch: true),
        Row(inner: "server_readable", server: "e2ee", posts: true, escrow: false, status: "sent", mismatch: true),
        Row(inner: nil, server: "e2ee", posts: true, escrow: false, status: "sent", mismatch: false),
        Row(inner: nil, server: "server_readable", posts: false, escrow: false, status: "sending", mismatch: false),
    ])
    func escrowFollowsServerMode(_ row: Row) async throws {
        let p = try await makePair(serverMode: row.server, inner: row.inner)
        let env = p.envelope("table row")
        try await p.outbox.enqueue(env, chatID: p.chatID)
        try await p.outbox.flush()

        let posts = p.messagePosts()
        // `server_readable` inner with an e2ee server may legitimately be answered 400 by the fake: it must never carry escrow.
        #expect((posts.count == 1) == row.posts)
        #expect(posts.contains { $0["escrow"] != nil } == row.escrow)
        #expect(try p.repo.message(id: env.id)?.status == row.status)
        #expect(try p.repo.conversation(serverID: p.conversation.serverID)?.modeMismatch == row.mismatch)
        #expect(try p.repo.conversation(serverID: p.conversation.serverID)?.innerMode == row.inner)
        if row.escrow {
            let sent = try #require(posts.first?["escrow"] as? String)
            let bytes = try #require(Base64URLData(encoded: sent)).data
            let prefix = Data("ESCROW:".utf8)
            #expect(bytes.prefix(prefix.count) == prefix)
            let plain = try #require(JSONSerialization.jsonObject(with: bytes.dropFirst(prefix.count)) as? [String: Any])
            #expect(plain["conversationID"] as? String == p.conversation.serverID)
            #expect(plain["senderAccountID"] as? String == p.ana.accountID)
            #expect(plain["sentAt"] as? String == "2023-11-14T22:13:20.000Z")
            let body = try #require(plain["body"] as? NSDictionary)
            let expected = try #require(JSONSerialization.jsonObject(with: env.encoded()) as? NSDictionary)
            #expect(body == expected)
            #expect(p.sealer.calls == 1)
        } else {
            #expect(p.sealer.calls == 0)
        }
        if row.status == "sending" { #expect(try p.outboxRow(env.id)?.attempts == 0) }
    }

    @Test func e2eeChatIgnoresServerReadableClaim() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        p.probe.lie(mode: "server_readable", rejectingWith: "escrow_required")
        let first = p.envelope("one"), second = p.envelope("two")
        try await p.outbox.enqueue(first, chatID: p.chatID)
        try await p.outbox.flush()
        try await p.outbox.enqueue(second, chatID: p.chatID)
        try await p.outbox.flush()
        try await p.outbox.flush()

        #expect(p.messagePosts().allSatisfy { $0["escrow"] == nil })
        #expect(try p.repo.message(id: first.id)?.status == "failed")
        #expect(try p.repo.message(id: second.id)?.status == "failed")
        let conv = try #require(try p.repo.conversation(serverID: p.conversation.serverID))
        #expect(conv.modeMismatch)
        #expect(conv.innerMode == "e2ee")
        #expect(conv.mode == "server_readable")
        #expect(p.sealer.calls == 0)
    }

    @Test func waitsForModeEnvelope() async throws {
        let p = try await makePair(serverMode: "server_readable", inner: nil)
        let env = p.envelope("hold on")
        try await p.outbox.enqueue(env, chatID: p.chatID)
        try await p.outbox.flush()
        #expect(p.messagePosts().isEmpty)
        #expect(try p.outboxRow(env.id)?.attempts == 0)

        #expect(try p.repo.pinInnerMode(serverID: p.conversation.serverID, mode: "server_readable"))
        try await p.outbox.flush()
        let posts = p.messagePosts()
        #expect(posts.count == 1)
        let sent = try #require(posts.first?["escrow"] as? String)
        #expect(try #require(Base64URLData(encoded: sent)).data.starts(with: Data("ESCROW:".utf8)))
        #expect(try p.repo.message(id: env.id)?.status == "sent")
    }

    @Test func controlMessagesAreSentButNotStored() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        try await p.outbox.sendControl(.receipt(.delivered, ids: ["m1"]), chatID: p.chatID)
        #expect(p.messagePosts().count == 1)
        #expect(try p.repo.messages(chatID: p.chatID, before: nil, limit: 10).isEmpty)
    }

    @Test func newerMessageNeverOvertakesBackedOffOlderOne() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        let older = p.envelope("older"), newer = p.envelope("newer")
        try await p.outbox.enqueue(older, chatID: p.chatID)
        p.probe.failNext(1)
        try await p.outbox.flush()                       // older fails, backs off 1 s
        try await p.outbox.enqueue(newer, chatID: p.chatID)
        try await p.outbox.flush()                       // newer is due, older is not: nothing may be sent
        #expect(p.messagePosts().count == 1)
        #expect(try p.repo.message(id: newer.id)?.status == "sending")
        p.clock.advance(1)
        try await p.outbox.flush()                       // older first, then newer, in one flush
        #expect(try p.repo.message(id: older.id)?.status == "sent")
        #expect(try p.repo.message(id: newer.id)?.status == "sent")
        #expect(try await p.bobTexts() == ["older", "newer"])
    }

    @Test func sendThrowsOnFailureAndAttachesEscrow() async throws {
        let p = try await makePair(serverMode: "server_readable", inner: "server_readable")
        let env = p.envelope("direct")
        try await p.outbox.send(env, in: p.conversation)
        #expect(p.messagePosts().first?["escrow"] != nil)

        p.probe.failNext(1)
        await #expect(throws: OutboxError.retryable) { try await p.outbox.send(p.envelope("x"), in: p.conversation) }

        var waiting = p.conversation
        waiting.innerMode = nil
        await #expect(throws: OutboxError.waitingForMode) { try await p.outbox.send(p.envelope("y"), in: waiting) }
        var e2ee = p.conversation
        e2ee.innerMode = "e2ee"
        await #expect(throws: OutboxError.modeMismatch) { try await p.outbox.send(p.envelope("z"), in: e2ee) }
    }

    @Test func sendControlReportsFailure() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        p.probe.failNext(1)
        await #expect(throws: OutboxError.retryable) {
            try await p.outbox.sendControl(.receipt(.read, ids: ["m1"]), chatID: p.chatID)
        }
    }

    @Test func escrowKeyProviderFetchesOnceAndDecodes() async throws {
        let p = try await makePair(serverMode: "server_readable", inner: "server_readable")
        let provider = EscrowKeyProvider(server: p.ana.deps.server)
        let key = try await provider.key()
        let serverKey = await p.server.escrowKey
        #expect(key == serverKey)
        #expect(try await provider.key() == key)
    }

    @Test func failedOutboxReadIsAnError() async throws {
        let db = try Database.inMemory()
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee", database: db)
        try db.execute("DROP TABLE outbox")
        await #expect(throws: (any Error).self) { try await p.outbox.flush() }
    }

    @Test func markSentIsOneTransaction() async throws {
        let db = try Database.inMemory()
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee", database: db)
        let env = p.envelope("atomic")
        try await p.outbox.enqueue(env, chatID: p.chatID)
        try db.execute("CREATE TRIGGER no_status BEFORE UPDATE OF status ON messages BEGIN SELECT RAISE(ABORT, 'boom'); END")
        await #expect(throws: (any Error).self) { try await p.outbox.flush() }
        // The status write failed, so the outbox row must still be there (it is retried, never stuck).
        #expect(try p.outboxRow(env.id) != nil)
        #expect(try p.repo.message(id: env.id)?.status == "sending")
    }

    @Test func headFailsAfterTenAttemptsAndNextMessageSends() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        let head = p.envelope("head"), next = p.envelope("next")
        try await p.outbox.enqueue(head, chatID: p.chatID)
        try await p.outbox.enqueue(next, chatID: p.chatID)
        p.probe.failNext(10)
        for attempt in 1...10 {
            #expect(try p.repo.message(id: head.id)?.status == "sending")
            try await p.outbox.flush()
            p.clock.advance(301)
            // The 10th failure fails the head, so the next message goes in the same flush.
            #expect(p.messagePosts().count == (attempt < 10 ? attempt : 11))
        }
        #expect(try p.repo.message(id: head.id)?.status == "failed")
        #expect(try p.outboxRow(head.id) == nil)
        try await p.outbox.flush()
        #expect(try p.repo.message(id: next.id)?.status == "sent")
        #expect(try await p.bobTexts() == ["next"])
    }

    @Test func headOlderThan24HoursFails() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        let head = p.envelope("stale"), next = p.envelope("fresh")
        try await p.outbox.enqueue(head, chatID: p.chatID)
        p.probe.failNext(1)
        try await p.outbox.flush()
        p.clock.advance(24 * 3600 + 1)
        try await p.outbox.enqueue(next, chatID: p.chatID)
        try await p.outbox.flush()
        #expect(try p.repo.message(id: head.id)?.status == "failed")
        #expect(p.messagePosts().count == 2)               // the old head was not tried again
        #expect(try p.repo.message(id: next.id)?.status == "sent")
    }

    @Test func controlMessageExpiresAfter24Hours() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        try p.outbox.enqueueControl(.delete(target: "m-1"), chatID: p.chatID)
        let before = p.messagePosts().count
        p.clock.advance(24 * 3600 + 0.001)
        try await p.outbox.flush()
        #expect(p.messagePosts().count == before, "an expired control message is not sent")
        #expect(try p.repo.dueOutboxHeads(at: .distantFuture).isEmpty, "and leaves the queue")
    }

    @Test func controlMessageWithin24HoursIsSent() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        try p.outbox.enqueueControl(.delete(target: "m-1"), chatID: p.chatID)
        let before = p.messagePosts().count
        p.clock.advance(24 * 3600)
        try await p.outbox.flush()
        #expect(p.messagePosts().count == before + 1)
        #expect(try p.repo.dueOutboxHeads(at: .distantFuture).isEmpty)
    }

    @Test func manualRetryGoesToEndOfQueue() async throws {
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee")
        let a = p.envelope("a"), b = p.envelope("b")
        try await p.outbox.enqueue(a, chatID: p.chatID)
        p.probe.failNext(1)
        try await p.outbox.flush()
        p.clock.advance(24 * 3600 + 1)
        try await p.outbox.enqueue(b, chatID: p.chatID)
        try await p.outbox.flush()                            // a fails (too old), b sent
        #expect(try p.repo.message(id: a.id)?.status == "failed")
        try await p.outbox.retry(messageID: a.id)
        #expect(try p.repo.message(id: a.id)?.status == "sending")
        try await p.outbox.flush()
        #expect(try p.repo.message(id: a.id)?.status == "sent")
        #expect(try await p.bobTexts() == ["b", "a"])
    }

    @Test func rfc3339FromMilliseconds() {
        #expect(Escrow.rfc3339(milliseconds: 1_700_000_000_000) == "2023-11-14T22:13:20.000Z")
        #expect(Escrow.rfc3339(milliseconds: 1_700_000_000_123) == "2023-11-14T22:13:20.123Z")
        #expect(Escrow.rfc3339(milliseconds: 1_700_000_000_999) == "2023-11-14T22:13:20.999Z")
        #expect(Escrow.rfc3339(milliseconds: 0) == "1970-01-01T00:00:00.000Z")
        #expect(Escrow.rfc3339(milliseconds: 951_782_400_007) == "2000-02-29T00:00:00.007Z")
        #expect(Escrow.rfc3339(milliseconds: -1) == "1969-12-31T23:59:59.999Z")
    }

    @Test func oneRecordingErrorDoesNotStopOtherChats() async throws {
        let db = try Database.inMemory()
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee", database: db)
        // A second chat whose conversation the server does not know: its message is rejected, and recording
        // that result fails (trigger). The first chat must still be sent.
        let carolGroup = try await p.ana.deps.mls.createGroup()
        try p.repo.upsertChat(ChatRecord(id: "acc-carol", isRequest: false, unread: 0, muted: false, lastActivity: p.clock.now))
        try p.repo.upsertConversation(ConversationRecord(
            serverID: "conv-unknown", chatID: "acc-carol", groupID: carolGroup.raw, mode: "e2ee", innerMode: "e2ee",
            modeMismatch: false, epoch: 1, active: true, createdAt: p.clock.now))
        let carol = p.envelope("to carol"), bob = p.envelope("to bob")
        try await p.outbox.enqueue(carol, chatID: "acc-carol")
        try await p.outbox.enqueue(bob, chatID: p.chatID)
        try db.execute("CREATE TRIGGER no_failed BEFORE UPDATE OF status ON messages WHEN new.status = 'failed' BEGIN SELECT RAISE(ABORT, 'boom'); END")
        await #expect(throws: (any Error).self) { try await p.outbox.flush() }
        #expect(try p.repo.message(id: bob.id)?.status == "sent")
    }
}
