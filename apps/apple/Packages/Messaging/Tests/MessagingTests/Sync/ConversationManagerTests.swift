#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

private let escrowKey = Data(repeating: 7, count: 32)

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

/// A manager whose stale-epoch sync is the test sync below.
private actor Counter {
    private(set) var value = 0
    func bump() { value += 1 }
}

private func manager(_ device: TestDevice, syncs: Counter = Counter()) -> ConversationManager {
    ConversationManager(deps: device.deps, escrowKey: { escrowKey }, syncOnce: {
        await syncs.bump()
        try await syncInbox(device)
    })
}

/// A minimal stand-in for the inbox processor: welcomes go to `joined`, messages are decoded and
/// `mode` envelopes pinned, commits are applied.
private func syncInbox(_ device: TestDevice, joinWith joiner: ConversationManager? = nil) async throws {
    let deps = device.deps
    let page = try await deps.server.inbox(after: nil)
    for item in page.items.sorted(by: { $0.cursor < $1.cursor }) {
        switch item.kind {
        case "welcome":
            if case .joined(let group, _) = try await deps.mls.process(item.payload) {
                try await (joiner ?? ConversationManager(deps: deps)).joined(group: group, serverConversationID: item.conversationID)
            }
        case "commit":
            _ = try? await deps.mls.process(item.payload)
        default:
            if case .application(_, _, let plaintext, _) = try await deps.mls.process(item.payload),
               case .mode(let m) = ContentEnvelope.decode(plaintext).body {
                _ = try deps.repo.pinInnerMode(serverID: item.conversationID, mode: m)
            }
        }
    }
    if let last = page.items.map(\.cursor).max() { try await deps.server.ackInbox(upTo: last) }
}

@Suite("TestConversationManager") struct TestConversationManager {
    @Test func createAddsAllDevices() async throws {
        let rig = try await Rig.make()
        let record = try await manager(rig.ana1).create(with: "acc-bob")
        let members = await rig.server.memberDevices(of: record.serverID)
        #expect(members.count == 4)
        for device in [rig.bob1, rig.bob2, rig.ana2] {
            let items = try await device.deps.server.inbox(after: nil).items
            #expect(items.map(\.kind) == ["welcome", "message"])
            #expect(items[0].epoch == 1)
        }
        #expect(record.chatID == "acc-bob" && record.epoch == 1 && record.active)
        #expect(try rig.ana1.deps.repo.chat(id: "acc-bob")?.isRequest == false)
        #expect(try rig.ana1.deps.repo.conversation(serverID: record.serverID) == record)
    }

    @Test func createUsesServerMode() async throws {
        let rig = try await Rig.make(mode: "server_readable")
        let record = try await manager(rig.ana1).create(with: "acc-bob")
        #expect(record.mode == "server_readable" && record.innerMode == "server_readable")
        let stored = try #require(try rig.ana1.deps.repo.conversation(serverID: record.serverID))
        #expect(stored.mode == "server_readable" && stored.innerMode == "server_readable")
        // The server demands an escrow copy of every message in this mode, the mode envelope included.
        let messages = await rig.server.storedMessages(in: record.serverID)
        #expect(messages.count == 1 && messages[0].escrow != nil)
    }

    @Test func createSendsModeEnvelope() async throws {
        let rig = try await Rig.make()
        let record = try await manager(rig.ana1).create(with: "acc-bob")
        let messages = await rig.server.storedMessages(in: record.serverID)
        #expect(messages.count == 1 && messages[0].epoch == 1 && messages[0].escrow == nil)

        let welcome = try #require(try await rig.ana2.deps.server.inbox(after: nil).items.first { $0.kind == "welcome" })
        _ = try await rig.ana2.deps.mls.process(welcome.payload)
        guard case .application(_, _, let plaintext, _) = try await rig.ana2.deps.mls.process(messages[0].ciphertext) else {
            Issue.record("expected an application message")
            return
        }
        #expect(ContentEnvelope.decode(plaintext).body == .mode("e2ee"))

        try await syncInbox(rig.bob1)
        let bobSide = try #require(try rig.bob1.deps.repo.conversation(serverID: record.serverID))
        #expect(bobSide.innerMode == "e2ee" && bobSide.mode == "e2ee")
    }

    /// A create's add commit is never rebuilt (MLS profile, "Commits" step 5, exception), so the stale-epoch
    /// retry is checked on a later commit.
    @Test func staleEpochRetries() async throws {
        let rig = try await Rig.make()
        let syncs = Counter()
        let mgr = manager(rig.ana1, syncs: syncs)
        let record = try await mgr.create(with: "acc-bob")
        let group = GroupID(raw: record.groupID)
        await rig.server.rejectNextCommits(1)
        let epoch = try await mgr.postCommitWithRetry(
            serverID: record.serverID, group: group, addDevices: [], removeDevices: [rig.ana2.deviceID]
        ) { try await rig.ana1.deps.mls.remove(members: ["acc-ana/\(rig.ana2.deviceID)"], from: group) }
        #expect(await syncs.value == 1)
        #expect(epoch == 2)
        #expect(try await rig.ana1.deps.mls.epoch(of: group) == 2)
        #expect(await rig.server.memberDevices(of: record.serverID).count == 3)
    }

    @Test func staleEpochGivesUp() async throws {
        let rig = try await Rig.make()
        let mgr = manager(rig.ana1)
        let record = try await mgr.create(with: "acc-bob")
        let group = GroupID(raw: record.groupID)
        await rig.server.rejectNextCommits(4)
        await #expect(throws: SyncError.epochConflict) {
            try await mgr.postCommitWithRetry(
                serverID: record.serverID, group: group, addDevices: [], removeDevices: [rig.ana2.deviceID]
            ) { try await rig.ana1.deps.mls.remove(members: ["acc-ana/\(rig.ana2.deviceID)"], from: group) }
        }
        #expect(try await rig.ana1.deps.mls.epoch(of: group) == 1)
        #expect(await rig.server.memberDevices(of: record.serverID).count == 4)
    }

    @Test func simultaneousCreate() async throws {
        let rig = try await Rig.make()
        let ana = manager(rig.ana1), bob = manager(rig.bob1)
        async let a = ana.create(with: "acc-bob")
        async let b = bob.create(with: "acc-ana")
        _ = try await (a, b)
        #expect(await rig.server.conversationIDs().count == 2)

        try await syncInbox(rig.ana1, joinWith: ana)
        try await syncInbox(rig.bob1, joinWith: bob)
        let first = try await ana.preferredConversation(with: "acc-bob")
        let second = try await bob.preferredConversation(with: "acc-ana")
        #expect(first.serverID == second.serverID)
        let smallest = await rig.server.conversationIDs().min()
        #expect(first.serverID == smallest)
        #expect(try rig.ana1.deps.repo.chats().count == 1 && rig.bob1.deps.repo.chats().count == 1)
        #expect(try rig.ana1.deps.repo.conversations(chatID: "acc-bob").count == 2)
    }

    @Test func joinedFromStrangerIsRequest() async throws {
        let server = FakeServer()
        let carol = await makeDevice(server, account: "acc-carol", username: "carol")
        let bob = await makeDevice(server, account: "acc-bob", username: "bob")
        _ = try await bob.deps.server.uploadKeyPackages(try await bob.deps.mls.makeKeyPackages(count: 2))
        _ = try await carol.deps.server.uploadKeyPackages(try await carol.deps.mls.makeKeyPackages(count: 2))
        _ = try await manager(carol).create(with: "acc-bob")
        try await syncInbox(bob)
        #expect(try bob.deps.repo.chat(id: "acc-carol")?.isRequest == true)
        let conversation = try #require(try bob.deps.repo.conversations(chatID: "acc-carol").first)
        #expect(conversation.mode == "e2ee")

        // A known contact is not a request.
        let dave = await makeDevice(server, account: "acc-dave", username: "dave")
        _ = try await dave.deps.server.uploadKeyPackages(try await dave.deps.mls.makeKeyPackages(count: 2))
        try bob.deps.repo.upsertContact(ContactRecord(
            accountID: "acc-dave", username: "dave", displayName: "Dave", isContact: true, addedAt: testEpochDate))
        _ = try await manager(dave).create(with: "acc-bob")
        try await syncInbox(bob)
        #expect(try bob.deps.repo.chat(id: "acc-dave")?.isRequest == false)
    }

    @Test func joinedLeavesInnerModeNull() async throws {
        let rig = try await Rig.make()
        let record = try await manager(rig.ana1).create(with: "acc-bob")
        // Join without reading the mode envelope.
        let items = try await rig.bob1.deps.server.inbox(after: nil).items
        let welcome = try #require(items.first { $0.kind == "welcome" })
        guard case .joined(let group, _) = try await rig.bob1.deps.mls.process(welcome.payload) else { return }
        try await ConversationManager(deps: rig.bob1.deps).joined(group: group, serverConversationID: record.serverID)
        let stored = try #require(try rig.bob1.deps.repo.conversation(serverID: record.serverID))
        #expect(stored.innerMode == nil && stored.mode == "e2ee" && stored.epoch == 1)
    }

    @Test func announceModeNeedsInnerMode() async throws {
        let rig = try await Rig.make()
        let mgr = manager(rig.ana1)
        var record = try await mgr.create(with: "acc-bob")
        try await mgr.announceMode(record)
        #expect(await rig.server.storedMessages(in: record.serverID).count == 2)
        record.innerMode = nil
        await #expect(throws: SyncError.modeUnknown) { try await mgr.announceMode(record) }
    }

    /// A real race: bob1 commits first, so ana1's commit is rejected for real; the sync moves her
    /// group forward and the rebuilt commit lands at the post-sync epoch.
    @Test func staleEpochRebuildsAfterSync() async throws {
        let rig = try await Rig.make()
        let syncs = Counter()
        let record = try await manager(rig.ana1, syncs: syncs).create(with: "acc-bob")
        try await syncInbox(rig.bob1)
        let bobGroup = GroupID(raw: try #require(try rig.bob1.deps.repo.conversation(serverID: record.serverID)).groupID)
        let bobRemove = try await rig.bob1.deps.mls.remove(members: ["acc-bob/\(rig.bob2.deviceID)"], from: bobGroup)
        #expect(try await rig.bob1.deps.server.postCommit(
            conversationID: record.serverID, epoch: 1, commit: bobRemove.commit, welcome: nil,
            addDevices: [], removeDevices: [rig.bob2.deviceID]) == 2)
        try await rig.bob1.deps.mls.commitAccepted(bobGroup)

        let group = GroupID(raw: record.groupID)
        let builds = Counter()
        let newEpoch = try await manager(rig.ana1, syncs: syncs).postCommitWithRetry(
            serverID: record.serverID, group: group, addDevices: [], removeDevices: [rig.ana2.deviceID]
        ) {
            await builds.bump()
            return try await rig.ana1.deps.mls.remove(members: ["acc-ana/\(rig.ana2.deviceID)"], from: group)
        }
        #expect(await builds.value == 2 && newEpoch == 3)
        #expect(await syncs.value == 1)
        #expect(try await rig.ana1.deps.mls.epoch(of: group) == 3)
        #expect(await rig.server.memberDevices(of: record.serverID) == [rig.ana1.deviceID, rig.bob1.deviceID].sorted())
    }

    /// The server applied the commit but the reply was lost: the pending commit must survive so the
    /// echo (Design 17) merges it.
    @Test func lostCommitResponseKeepsPendingCommit() async throws {
        let rig = try await Rig.make()
        let mgr = manager(rig.ana1)
        let theirs = try await rig.ana1.deps.server.claimKeyPackages(accountID: "acc-bob")
        let created = try await rig.ana1.deps.server.createConversation(with: "acc-bob")
        let group = try await rig.ana1.deps.mls.createGroup()
        await rig.server.loseNextCommitResponses(1)
        await #expect(throws: (any Error).self) {
            try await mgr.postCommitWithRetry(
                serverID: created.conversationID, group: group, addDevices: theirs.map(\.deviceID), removeDevices: []
            ) { try await rig.ana1.deps.mls.add(keyPackages: theirs.map(\.keyPackage), to: group) }
        }
        let echo = try #require(try await rig.ana1.deps.server.inbox(after: nil).items.first { $0.kind == "commit" })
        #expect(try await rig.ana1.deps.mls.process(echo.payload) == .commit(group: group, newEpoch: 1, removedMe: false))
        #expect(try await rig.ana1.deps.mls.epoch(of: group) == 1)
    }

    @Test func failedModePostIsSentBeforeAnythingElse() async throws {
        let rig = try await Rig.make()
        let mgr = manager(rig.ana1)
        await rig.server.failNextMessages(statuses: [503])
        await #expect(throws: (any Error).self) { try await mgr.create(with: "acc-bob") }
        let allIDs = await rig.server.conversationIDs()
        let serverID = try #require(allIDs.first)
        #expect(await rig.server.storedMessages(in: serverID).isEmpty)

        // The outbox path: ask for the conversation, then send a text.
        let record = try await mgr.preferredConversation(with: "acc-bob")
        let group = GroupID(raw: record.groupID)
        let text = ContentEnvelope(id: UUID().uuidString.lowercased(), at: 1, body: .text("hi", replyTo: nil))
        let (cipher, epoch) = try await rig.ana1.deps.mls.encrypt(text.encoded(), in: group)
        _ = try await rig.ana1.deps.server.postMessage(
            conversationID: serverID, epoch: epoch, ciphertext: cipher, escrow: nil, clientMsgID: text.id)

        let stored = await rig.server.storedMessages(in: serverID)
        #expect(stored.count == 2)
        try await syncInbox(rig.bob1)
        let bob = try #require(try rig.bob1.deps.repo.conversation(serverID: serverID))
        #expect(bob.innerMode == "e2ee")   // the mode envelope was processed before the text
        // A second ask does not announce again.
        _ = try await mgr.preferredConversation(with: "acc-bob")
        #expect(await rig.server.storedMessages(in: serverID).count == 2)
    }

    @Test func escrowSentAtIsRFC3339Milliseconds() async throws {
        let rig = try await Rig.make(mode: "server_readable")
        let record = try await manager(rig.ana1).create(with: "acc-bob")
        let storedFirst = await rig.server.storedMessages(in: record.serverID).first
        let escrow = try #require(storedFirst?.escrow)
        let json = try #require(try JSONSerialization.jsonObject(with: escrow.dropFirst("ESCROW:".count)) as? [String: Any])
        #expect(json["sentAt"] as? String == "2023-11-14T22:13:20.000Z")
        #expect(json["conversationID"] as? String == record.serverID && json["senderAccountID"] as? String == "acc-ana")
        #expect((json["body"] as? [String: Any])?["k"] as? String == "mode")
    }
}

/// Holds the process lock for a while after the commit POST is answered, as the notification extension would.
private struct BusyLockAfterCommit: HTTPTransport {
    let inner: HTTPTransport
    let lockURL: URL
    let hold: Duration
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let reply = try await inner.send(request)
        guard request.httpMethod == "POST", request.url?.path.hasSuffix("/commits") == true else { return reply }
        holdLockOnAnotherThread()
        return reply
    }

    /// Returns once the other thread holds the lock. Synchronous: Darwin forbids a semaphore wait in async code.
    private func holdLockOnAnotherThread() {
        let ready = DispatchSemaphore(value: 0)
        let seconds = Double(hold.components.seconds) + Double(hold.components.attoseconds) / 1e18
        Thread {
            try? ProcessLock(url: lockURL).withLock(timeout: .seconds(1)) {
                ready.signal()
                Thread.sleep(forTimeInterval: seconds)
            }
        }.start()
        ready.wait()
    }
}

@Suite("TestRejectedCommit") struct TestRejectedCommit {
    private func post(hold: Duration) async throws -> (spy: SpyMLSEngine, result: Result<UInt64, Error>) {
        let rig = try await Rig.make()
        let device = rig.ana1
        let spy = SpyMLSEngine(device.deps.mls)
        var deps = device.deps
        deps.mls = spy
        deps.server = ServerClient(
            host: FakeServer.host,
            session: SessionStore(storage: MemorySessionStorage(Session(
                host: FakeServer.host, accountID: device.accountID, deviceID: device.deviceID,
                token: "test.\(device.accountID).\(device.deviceID)"))),
            transport: BusyLockAfterCommit(
                inner: await rig.server.transport(for: device),
                lockURL: device.directory.appendingPathComponent("messaging.lock"), hold: hold))
        let created = try await deps.server.createConversation(with: "acc-bob")
        let group = try await deps.mls.createGroup()
        let packages = try await rig.bob1.deps.mls.makeKeyPackages(count: 1)
        let manager = ConversationManager(deps: deps, lockTimeout: .milliseconds(100))
        let mls = deps.mls
        do {
            // The server answers 400: "dev-nope" is not a device.
            let epoch = try await manager.postCommitWithRetry(
                serverID: created.conversationID, group: group, addDevices: ["dev-nope"], removeDevices: []
            ) { try await mls.add(keyPackages: packages, to: group) }
            return (spy, .success(epoch))
        } catch {
            return (spy, .failure(error))
        }
    }

    @Test func discardIsRetriedWhenTheLockIsBusyAtFirst() async throws {
        let (spy, result) = try await post(hold: .milliseconds(150))
        #expect(spy.commitRejectedCount == 1)   // the second attempt got the lock
        guard case .failure(let error) = result, let server = error as? ServerError else {
            Issue.record("expected the server's rejection, got \(result)"); return
        }
        #expect(server.status == 400)
    }

    @Test func discardThatNeverGetsTheLockIsNotSwallowed() async throws {
        let (spy, result) = try await post(hold: .seconds(2))
        #expect(spy.commitRejectedCount == 0)
        guard case .failure(let error) = result else { Issue.record("expected a failure"); return }
        #expect(error is LockTimeout)   // the pending commit is still there: the caller must know
    }
}
