import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

/// A queued message is encrypted once per epoch; a retry posts the same bytes. An MLS encrypt advances the
/// sender's generation, and a receiver can skip only 1,024 of them.
@Suite("OutboxReuse") struct OutboxReuseTests {
    /// A pair whose sender engine counts encrypts, with an outbox over it.
    struct Rig {
        let p: Pair
        let spy: SpyMLSEngine
        let outbox: Outbox
        let database: Database
        var repo: Repository { p.repo }
    }

    func makeRig() async throws -> Rig {
        let database = try Database.inMemory()
        let p = try await makePair(serverMode: "e2ee", inner: "e2ee", database: database)
        let spy = SpyMLSEngine(p.ana.deps.mls)
        var deps = p.ana.deps
        deps.mls = spy
        let outbox = Outbox(deps: deps, conversations: p.conversations, readReceiptsEnabled: { true })
        return Rig(p: p, spy: spy, outbox: outbox, database: database)
    }

    func postedCiphertexts(_ rig: Rig) -> [String] { rig.p.messagePosts().compactMap { $0["ciphertext"] as? String } }

    /// One flush, then past the backoff that follows `attempt` failed attempts.
    func flushAndWait(_ rig: Rig, _ outbox: Outbox, attempt: Int) async throws {
        try await outbox.flush()
        rig.p.clock.advance(min(pow(2, Double(attempt)), 300))
    }

    @Test func fiveFailedPostsEncryptOnce() async throws {
        let rig = try await makeRig()
        let env = rig.p.envelope("queued offline")
        try rig.outbox.enqueue(env, chatID: rig.p.chatID)
        rig.p.probe.failNext(5)
        for attempt in 0...5 { try await flushAndWait(rig, rig.outbox, attempt: attempt) }
        #expect(try rig.repo.message(id: env.id)?.status == "sent")
        let posted = postedCiphertexts(rig)
        #expect(posted.count == 6)
        #expect(Set(posted).count == 1)
        #expect(rig.spy.encryptCount == 1)
        #expect(try await rig.p.bobTexts() == ["queued offline"])
    }

    @Test func epochChangeEncryptsOnceMore() async throws {
        let rig = try await makeRig()
        let env = rig.p.envelope("across an epoch")
        try rig.outbox.enqueue(env, chatID: rig.p.chatID)
        rig.p.probe.failNext(3)
        for attempt in 0..<3 { try await flushAndWait(rig, rig.outbox, attempt: attempt) }
        #expect(rig.spy.encryptCount == 1)
        try await advanceEpoch(rig)
        try await rig.outbox.flush()
        #expect(rig.spy.encryptCount == 2)
        let posted = postedCiphertexts(rig)
        #expect(posted.count == 4)
        #expect(Set(posted.prefix(3)).count == 1)
        #expect(posted[3] != posted[0])
        #expect(posted[3] == Base64URLData(rig.spy.encrypted[1]).encoded)
        #expect(try rig.repo.message(id: env.id)?.status == "sent")
    }

    @Test func restartDoesNotEncryptAgain() async throws {
        let rig = try await makeRig()
        let env = rig.p.envelope("survives restart")
        try rig.outbox.enqueue(env, chatID: rig.p.chatID)
        rig.p.probe.failNext(2)
        try await flushAndWait(rig, rig.outbox, attempt: 0)
        // A new store over the same database, as after a restart.
        let clock = rig.p.clock
        var deps = rig.p.ana.deps
        deps.mls = rig.spy
        deps.repo = Repository(db: rig.database, now: { clock.now })
        let fresh = Outbox(deps: deps, conversations: RepoConversations(repo: deps.repo), readReceiptsEnabled: { true })
        try await flushAndWait(rig, fresh, attempt: 1)
        try await flushAndWait(rig, fresh, attempt: 2)
        #expect(rig.spy.encryptCount == 1)
        #expect(Set(postedCiphertexts(rig)).count == 1)
        #expect(postedCiphertexts(rig).count == 3)
        #expect(try rig.repo.message(id: env.id)?.status == "sent")
    }

    @Test func fiveHundredFailedAttemptsEncryptOncePerMessage() async throws {
        let rig = try await makeRig()
        let count = 50
        let ids = (0..<count).map { "m\($0)" }
        for id in ids { try rig.outbox.enqueue(rig.p.envelope("text \(id)", id: id), chatID: rig.p.chatID) }
        // Nothing gets through: every message uses all 10 attempts and then fails, so 500 posts fail in total.
        rig.p.probe.failNext(10_000)
        for _ in ids {
            for attempt in 0..<10 { try await flushAndWait(rig, rig.outbox, attempt: attempt) }
        }
        #expect(rig.p.messagePosts().count == 500)
        #expect(try ids.allSatisfy { try rig.repo.message(id: $0)?.status == "failed" })
        #expect(rig.spy.encryptCount == count)
    }

    func storedCiphertexts(_ rig: Rig) throws -> [Data] {
        try rig.repo.dueOutbox(at: Date.distantFuture).compactMap(\.ciphertext)
    }

    @Test func replacedEnvelopeIsEncryptedAgain() async throws {
        let rig = try await makeRig()
        let first = rig.p.envelope("placeholder text", id: "u1")
        try rig.outbox.enqueue(first, chatID: rig.p.chatID)
        rig.p.probe.failNext(2)
        try await flushAndWait(rig, rig.outbox, attempt: 0)
        #expect(rig.spy.encryptCount == 1)
        #expect(try storedCiphertexts(rig).count == 1)
        // The way an upload completes: the unsent message gets its real envelope and the outbox row is replaced.
        let second = rig.p.envelope("real text", id: "u1")
        let me = rig.p.ana.accountID
        try rig.repo.transaction {
            _ = try rig.repo.replaceUnsentEnvelope(id: "u1", sender: me, envelope: second.encoded(), folded: "")
            try rig.repo.enqueue(OutboxRecord(
                messageID: "u1", chatID: rig.p.chatID, envelope: second.encoded(), attempts: 0, nextAttemptAt: rig.p.clock.now))
        }
        #expect(try storedCiphertexts(rig).isEmpty)
        try await flushAndWait(rig, rig.outbox, attempt: 1)     // fails again
        try await flushAndWait(rig, rig.outbox, attempt: 2)     // goes out
        #expect(rig.spy.encryptCount == 2)
        #expect(try await rig.p.bobTexts() == ["real text"])
    }

    @Test func replacingOnlyTheMessageEnvelopeAlsoDropsTheKeptCiphertext() async throws {
        let rig = try await makeRig()
        let env = rig.p.envelope("one", id: "u2")
        try rig.outbox.enqueue(env, chatID: rig.p.chatID)
        rig.p.probe.failNext(1)
        try await flushAndWait(rig, rig.outbox, attempt: 0)
        #expect(try storedCiphertexts(rig).count == 1)
        _ = try rig.repo.replaceUnsentEnvelope(id: "u2", sender: rig.p.ana.accountID, envelope: env.encoded(), folded: "")
        #expect(try storedCiphertexts(rig).isEmpty)
    }

    @Test func deliveredMessageLeavesNoCiphertext() async throws {
        let rig = try await makeRig()
        let env = rig.p.envelope("goes out")
        try rig.outbox.enqueue(env, chatID: rig.p.chatID)
        rig.p.probe.failNext(1)
        try await flushAndWait(rig, rig.outbox, attempt: 0)
        #expect(try storedCiphertexts(rig).count == 1)
        try await flushAndWait(rig, rig.outbox, attempt: 1)
        #expect(try rig.repo.message(id: env.id)?.status == "sent")
        #expect(try rig.repo.dueOutbox(at: Date.distantFuture).isEmpty)
        let rows = try rig.database.query("SELECT COUNT(*) FROM outbox WHERE ciphertext IS NOT NULL") { $0.int(0) }
        #expect(rows == [0])
    }

    @Test func droppedAndExpiredMessagesLeaveNoCiphertext() async throws {
        let rig = try await makeRig()
        try rig.outbox.enqueue(rig.p.envelope("will expire", id: "x1"), chatID: rig.p.chatID)
        rig.p.probe.failNext(1)
        try await flushAndWait(rig, rig.outbox, attempt: 0)
        #expect(try storedCiphertexts(rig).count == 1)
        rig.p.clock.advance(Outbox.maxAge + 1)
        try await rig.outbox.flush()
        #expect(try rig.repo.message(id: "x1")?.status == "failed")
        #expect(try storedCiphertexts(rig).isEmpty)
        #expect(rig.spy.encryptCount == 1)
    }

    @Test func manualRetryEncryptsAgainFromTheStart() async throws {
        let rig = try await makeRig()
        try rig.outbox.enqueue(rig.p.envelope("fails", id: "r1"), chatID: rig.p.chatID)
        rig.p.probe.failNext(Outbox.maxAttempts)
        for attempt in 0..<Outbox.maxAttempts { try await flushAndWait(rig, rig.outbox, attempt: attempt) }
        #expect(try rig.repo.message(id: "r1")?.status == "failed")
        #expect(rig.spy.encryptCount == 1)
        try rig.outbox.retry(messageID: "r1")
        #expect(try storedCiphertexts(rig).isEmpty)
        try await rig.outbox.flush()
        #expect(try rig.repo.message(id: "r1")?.status == "sent")
        #expect(rig.spy.encryptCount == 2)
    }

    func advanceEpoch(_ rig: Rig) async throws {
        let cat = await makeDevice(rig.p.server, account: "acc-ana", username: "ana")
        let packages = try await cat.deps.mls.makeKeyPackages(count: 1)
        let group = GroupID(raw: rig.p.conversation.groupID)
        let bundle = try await rig.p.ana.deps.mls.add(keyPackages: packages, to: group)
        _ = try await rig.p.ana.deps.server.postCommit(
            conversationID: rig.p.conversation.serverID, epoch: 1, commit: bundle.commit, welcome: bundle.welcome,
            addDevices: [cat.deviceID], removeDevices: [])
        try await rig.p.ana.deps.mls.commitAccepted(group)
    }
}
