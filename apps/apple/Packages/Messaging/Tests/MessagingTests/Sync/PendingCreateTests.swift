import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

/// The conversation manager's `syncOnce` is the inbox processor's, set once both exist.
private final class SyncBox: @unchecked Sendable {
    private let lock = NSLock()
    private var processor: InboxProcessor?
    func set(_ processor: InboxProcessor) { lock.withLock { self.processor = processor } }
    func run() async throws { _ = try await lock.withLock { processor }?.syncOnce() }
}

/// One device with the real inbox processor behind its conversation manager.
private struct Creator {
    let deps: MessagingDeps
    let spy: SpyMLSEngine
    let manager: ConversationManager
    let processor: InboxProcessor

    init(_ device: TestDevice) {
        var deps = device.deps
        spy = SpyMLSEngine(deps.mls)
        deps.mls = spy
        self.deps = deps
        let box = SyncBox()
        manager = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) }, syncOnce: { try await box.run() })
        processor = InboxProcessor(
            deps: deps, conversations: manager, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }))
        box.set(processor)
    }
}

private struct Rig {
    let server: FakeServer
    let ana1: TestDevice, ana2: TestDevice, bob1: TestDevice

    static func make() async throws -> Rig {
        let server = FakeServer()
        let rig = Rig(
            server: server,
            ana1: await makeDevice(server, account: "acc-ana", username: "ana"),
            ana2: await makeDevice(server, account: "acc-ana", username: "ana"),
            bob1: await makeDevice(server, account: "acc-bob", username: "bob"))
        for device in [rig.ana1, rig.ana2, rig.bob1] {
            _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 3))
        }
        return rig
    }

    /// bob1 joins every conversation it was added to.
    func bobJoins() async throws -> InboxProcessor {
        let processor = InboxProcessor(
            deps: bob1.deps, conversations: ConversationManager(deps: bob1.deps),
            handler: IncomingHandler(me: bob1.deps.me, now: bob1.deps.now, log: { _ in }))
        _ = try await processor.syncOnce()
        return processor
    }
}

/// MLS profile "Commits" step 5, exception: the first commit of a new conversation may be resent once its reply
/// was lost, only at epoch 0 while the creator is the only member. A lost reply never leaves an orphaned chat
/// and never makes a second group for the same peer.
@Suite("TestPendingCreate") struct TestPendingCreate {
    @Test func lostReplyIsSettledByTheEcho() async throws {
        let rig = try await Rig.make()
        let ana = Creator(rig.ana1)
        await rig.server.loseNextCommitResponses(1)
        let record = try await ana.manager.preferredConversation(with: "acc-bob")

        #expect(record.active)
        #expect(record.epoch == 1)
        #expect(try PendingCreate.load(serverID: record.serverID, db: ana.deps.repo) == nil)
        #expect(await rig.server.conversationIDs() == [record.serverID])
        #expect(ana.spy.addCount == 1)
        #expect(try ana.deps.repo.preferredConversation(chatID: "acc-bob")?.serverID == record.serverID)
        _ = try await rig.bobJoins()
        #expect(try rig.bob1.deps.repo.conversation(serverID: record.serverID)?.innerMode == "e2ee")
    }

    @Test func unappliedCommitIsResentByteForByte() async throws {
        let rig = try await Rig.make()
        let ana = Creator(rig.ana1)
        await rig.server.failNextRequests("POST /v1/conversations/", count: 1)
        await #expect(throws: (any Error).self) { try await ana.manager.preferredConversation(with: "acc-bob") }

        // Stored before the post: inactive, bound to its group, with the exact commit.
        let ids = await rig.server.conversationIDs()
        #expect(ids.count == 1)
        let serverID = try #require(ids.first)
        let stored = try #require(try ana.deps.repo.conversation(serverID: serverID))
        #expect(!stored.active)
        let pending = try #require(try PendingCreate.load(serverID: serverID, db: ana.deps.repo))
        #expect(await rig.server.epoch(of: serverID) == 0)
        #expect(try ana.deps.repo.preferredConversation(chatID: "acc-bob") == nil)

        let record = try await ana.manager.preferredConversation(with: "acc-bob")
        #expect(record.serverID == serverID)
        #expect(record.active)
        #expect(record.epoch == 1)
        #expect(ana.spy.addCount == 1)  // never a second commit
        #expect(await rig.server.conversationIDs() == [serverID])
        #expect(try PendingCreate.load(serverID: serverID, db: ana.deps.repo) == nil)
        #expect(!pending.commit.isEmpty)
        _ = try await rig.bobJoins()
        #expect(try rig.bob1.deps.repo.conversation(serverID: serverID) != nil)
    }

    @Test func goneConversationIsAbandonedAndANewOneStarted() async throws {
        let rig = try await Rig.make()
        let ana = Creator(rig.ana1)
        await rig.server.failNextRequests("POST /v1/conversations/", count: 1)
        await #expect(throws: (any Error).self) { try await ana.manager.preferredConversation(with: "acc-bob") }
        let old = try #require(await rig.server.conversationIDs().first)
        await rig.server.removeConversation(old)

        let record = try await ana.manager.preferredConversation(with: "acc-bob")
        #expect(record.serverID != old)
        #expect(record.active)
        #expect(try PendingCreate.load(serverID: old, db: ana.deps.repo) == nil)
        #expect(try ana.deps.repo.conversation(serverID: old)?.active == false)
    }

    @Test func staleEpochOnTheResendWithTheServerStillAtZeroKeepsItPending() async throws {
        let rig = try await Rig.make()
        let ana = Creator(rig.ana1)
        await rig.server.failNextRequests("POST /v1/conversations/", count: 1)
        await #expect(throws: (any Error).self) { try await ana.manager.preferredConversation(with: "acc-bob") }
        let serverID = try #require(await rig.server.conversationIDs().first)
        await rig.server.rejectNextCommits(1)
        await #expect(throws: (any Error).self) { try await ana.manager.preferredConversation(with: "acc-bob") }
        #expect(try PendingCreate.load(serverID: serverID, db: ana.deps.repo) != nil)

        let record = try await ana.manager.preferredConversation(with: "acc-bob")
        #expect(record.serverID == serverID)
        #expect(ana.spy.addCount == 1)
    }

    @Test func resumePendingCreatesFinishesWithoutStartingANewOne() async throws {
        let rig = try await Rig.make()
        let ana = Creator(rig.ana1)
        await rig.server.failNextRequests("POST /v1/conversations/", count: 1)
        await #expect(throws: (any Error).self) { try await ana.manager.preferredConversation(with: "acc-bob") }
        let serverID = try #require(await rig.server.conversationIDs().first)

        await ana.manager.resumePendingCreates()
        #expect(try ana.deps.repo.conversation(serverID: serverID)?.active == true)
        #expect(await rig.server.epoch(of: serverID) == 1)

        // Gone on the server: resuming abandons it and does not create another.
        let rig2 = try await Rig.make()
        let ana2 = Creator(rig2.ana1)
        await rig2.server.failNextRequests("POST /v1/conversations/", count: 1)
        await #expect(throws: (any Error).self) { try await ana2.manager.preferredConversation(with: "acc-bob") }
        let gone = try #require(await rig2.server.conversationIDs().first)
        await rig2.server.removeConversation(gone)
        await ana2.manager.resumePendingCreates()
        #expect(await rig2.server.conversationIDs().isEmpty)
        #expect(try PendingCreate.load(serverID: gone, db: ana2.deps.repo) == nil)
    }
}
