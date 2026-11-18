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
