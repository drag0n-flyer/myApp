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

private let escrowKey = Data(repeating: 7, count: 32)

private final class ProcessorBox: @unchecked Sendable {
    private let lock = NSLock()
    private var processor: InboxProcessor?
    func set(_ p: InboxProcessor) { lock.withLock { processor = p } }
    func syncOnce() async throws -> Int {
        guard let p = lock.withLock({ processor }) else { return 0 }
        return try await p.syncOnce()
    }
}

private struct Node: Sendable {
    let device: TestDevice
    let processor: InboxProcessor
    let conversations: ConversationManager
    let sync: DeviceSync

    var repo: Repository { device.deps.repo }
    func syncInbox() async throws { _ = try await processor.syncOnce() }

    static func make(_ device: TestDevice) -> Node {
        let box = ProcessorBox()
        let manager = ConversationManager(deps: device.deps, escrowKey: { escrowKey }, syncOnce: { _ = try await box.syncOnce() })
        let processor = InboxProcessor(
            deps: device.deps, conversations: manager,
            handler: IncomingHandler(me: device.deps.me, now: device.deps.now, log: { _ in }))
        box.set(processor)
        return Node(device: device, processor: processor, conversations: manager,
                    sync: DeviceSync(deps: device.deps, conversations: manager, wipeMLS: {}))
    }

    /// The system lines in the chat with `peer`, oldest first.
    func lines(with peer: String) throws -> [SystemEvent] {
        try repo.messages(chatID: peer, before: nil, limit: 100)
            .filter { $0.senderAccount == "system" }
            .sorted { ($0.sentAt, $0.id) < ($1.sentAt, $1.id) }
            .compactMap { try? JSONDecoder().decode(SystemEvent.self, from: $0.envelope) }
    }

    func safety(with peer: String) throws -> String? { try repo.safety(chatID: peer)?.current }
}

private func keyPackages(_ device: TestDevice, count: Int = 5) async throws {
    _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: count))
}

/// ana1 + ana2 (account "acc-ana"), bob1 + bob2 ("acc-bob"), carol1. ana1 has created the chat with bob,
/// and with carol when `withCarol`. Everyone else has synced.
private struct Rig {
    let server: FakeServer
    let ana1: Node, ana2: Node, bob1: Node, bob2: Node
    let carol1: Node
    let conversationIDs: [String]

    /// - Parameter bob1Now: bob1's clock.
    static func make(withCarol: Bool = false, bob1Now: @escaping @Sendable () -> Date = { testEpochDate }) async throws -> Rig {
        let server = FakeServer()
        let a1 = await makeDevice(server, account: "acc-ana", username: "ana")
        let a2 = await makeDevice(server, account: "acc-ana", username: "ana")
        let b1 = await makeDevice(server, account: "acc-bob", username: "bob", now: bob1Now)
        let b2 = await makeDevice(server, account: "acc-bob", username: "bob")
        let c1 = await makeDevice(server, account: "acc-carol", username: "carol")
        for d in [a1, a2, b1, b2, c1] { try await keyPackages(d) }
        let rig = (Node.make(a1), Node.make(a2), Node.make(b1), Node.make(b2), Node.make(c1))
        var ids = [try await rig.0.conversations.create(with: "acc-bob").serverID]
        if withCarol { ids.append(try await rig.0.conversations.create(with: "acc-carol").serverID) }
        for node in [rig.1, rig.2, rig.3, rig.4] { try await node.syncInbox() }
        return Rig(server: server, ana1: rig.0, ana2: rig.1, bob1: rig.2, bob2: rig.3, carol1: rig.4, conversationIDs: ids)
    }

    func addAna3() async throws -> Node {
        let a3 = await makeDevice(server, account: "acc-ana", username: "ana")
        try await keyPackages(a3)
        return Node.make(a3)
    }
}

@Suite("TestMembership") struct TestMembership {
    @Test func deviceAddedLine() async throws {
        let rig = try await Rig.make()
        _ = try await rig.addAna3()
        try await rig.ana1.sync.reconcileOwnDevices()
        for node in [rig.bob1, rig.bob2, rig.ana2] { try await node.syncInbox() }

        // Others see the account (resolved to a display name when shown); my own devices see "You".
        #expect(try rig.bob1.lines(with: "acc-ana") == [.deviceAdded(name: "acc-ana"), .safetyNumberChanged])
        #expect(try rig.bob2.lines(with: "acc-ana") == [.deviceAdded(name: "acc-ana"), .safetyNumberChanged])
        #expect(try rig.ana2.lines(with: "acc-bob") == [.deviceAdded(name: nil), .safetyNumberChanged])
        #expect(try rig.ana1.lines(with: "acc-bob") == [.deviceAdded(name: nil), .safetyNumberChanged])
        // Syncing again changes nothing.
        try await rig.bob1.syncInbox()
        #expect(try rig.bob1.lines(with: "acc-ana").count == 2)
    }

    @Test func deviceRemovedLine() async throws {
        let clock = StepClock()
        let rig = try await Rig.make(bob1Now: { clock.now })
        // The revoked event only runs reconcile: two lists ten minutes apart without bob2 remove it.
        await rig.server.revokeDevice(account: "acc-bob", deviceID: rig.bob2.device.deviceID)
        try await rig.bob1.sync.handleRevoked(deviceID: rig.bob2.device.deviceID)
        clock.advance(10 * 60)
        try await rig.bob1.sync.handleRevoked(deviceID: rig.bob2.device.deviceID)
        try await rig.ana1.syncInbox()
        try await rig.ana2.syncInbox()
        #expect(try rig.ana1.lines(with: "acc-bob") == [.deviceRemoved(name: "acc-bob"), .safetyNumberChanged])
        #expect(try rig.ana2.lines(with: "acc-bob") == [.deviceRemoved(name: "acc-bob"), .safetyNumberChanged])
        #expect(try rig.bob1.lines(with: "acc-ana") == [.deviceRemoved(name: nil), .safetyNumberChanged])
    }

    @Test func noLinesForChatCreation() async throws {
        let rig = try await Rig.make()
        for (node, peer) in [(rig.ana1, "acc-bob"), (rig.ana2, "acc-bob"), (rig.bob1, "acc-ana"), (rig.bob2, "acc-ana")] {
            #expect(try node.lines(with: peer).isEmpty)
            // The number is set at creation, without a line.
            #expect(try node.safety(with: peer)?.count == 60)
        }
    }

    @Test func ownDeviceAddedNotifiesOnce() async throws {
        let rig = try await Rig.make(withCarol: true)
        let ana3 = try await rig.addAna3()
        try await rig.ana1.sync.reconcileOwnDevices()  // two commits: bob's and carol's conversation
        try await rig.ana2.syncInbox()

        let id = ana3.device.deviceID
        #expect(await rig.ana2.device.notifier.added.map(\.deviceID) == [id])
        #expect(await rig.ana2.device.notifier.added.map(\.name) == ["Test device \(id)"])
        #expect(await rig.bob1.device.notifier.added.isEmpty)
        try await rig.bob1.syncInbox()
        #expect(await rig.bob1.device.notifier.added.isEmpty)

        try await rig.ana2.syncInbox()
        #expect(await rig.ana2.device.notifier.added.count == 1)
        #expect(try rig.ana2.repo.get("notified.device.\(id)") != nil)
    }

    @Test func safetyNumberChangesWhenDeviceAdded() async throws {
        let rig = try await Rig.make()
        let before = try #require(try rig.ana1.safety(with: "acc-bob"))
        #expect(try rig.bob1.safety(with: "acc-ana") == before)
        #expect(try rig.ana2.safety(with: "acc-bob") == before)

        _ = try await rig.addAna3()
        try await rig.ana1.sync.reconcileOwnDevices()
        for node in [rig.bob1, rig.bob2, rig.ana2] { try await node.syncInbox() }

        let after = try #require(try rig.ana1.safety(with: "acc-bob"))
        #expect(after != before)
        #expect(try rig.bob1.safety(with: "acc-ana") == after)
        #expect(try rig.bob2.safety(with: "acc-ana") == after)
        #expect(try rig.ana2.safety(with: "acc-bob") == after)
        #expect(try rig.bob1.lines(with: "acc-ana").filter { $0 == .safetyNumberChanged }.count == 1)
        // The two sides compute the same number from their own stored members.
        let members = try rig.bob1.repo.members(conversationID: rig.conversationIDs[0])
            .map { MemberKey(identity: $0.identity, signatureKey: $0.signatureKey) }
        #expect(try SafetyNumberCalc.compute(accountA: "acc-bob", accountB: "acc-ana", members: members).joined() == after)
    }

    @Test func verifiedLapsesWhenNumberChanges() async throws {
        let rig = try await Rig.make()
        let before = try #require(try rig.bob1.safety(with: "acc-ana"))
        try rig.bob1.repo.setVerified(chatID: "acc-ana", before)
        _ = try await rig.addAna3()
        try await rig.ana1.sync.reconcileOwnDevices()
        try await rig.bob1.syncInbox()
        let row = try #require(try rig.bob1.repo.safety(chatID: "acc-ana"))
        #expect(row.verified == before)
        #expect(row.current != before)
    }

    /// A second conversation with the same devices gives the same number and no line.
    @Test func secondConversationKeepsNumber() async throws {
        let rig = try await Rig.make()
        let first = rig.conversationIDs[0]
        let before = try rig.ana1.safety(with: "acc-bob")
        // bob2 starts a second conversation with ana.
        let second = try await rig.bob2.conversations.create(with: "acc-ana")
        try await rig.ana1.syncInbox()
        #expect(try rig.ana1.repo.conversations(chatID: "acc-bob").count == 2)
        let preferred = try #require(try rig.ana1.repo.preferredConversation(chatID: "acc-bob"))
        #expect(preferred.serverID == min(first, second.serverID))
        #expect(try rig.ana1.safety(with: "acc-bob") == before)
        #expect(try rig.ana1.lines(with: "acc-bob").isEmpty)
    }

    @Test func updateStoresMembersWithoutChangeFirst() async throws {
        let rig = try await Rig.make()
        let conversation = try #require(try rig.ana1.repo.conversation(serverID: rig.conversationIDs[0]))
        let stored = try rig.ana1.repo.members(conversationID: conversation.serverID).map(\.identity)
        #expect(stored.sorted() == ["acc-ana/\(rig.ana1.device.deviceID)", "acc-ana/\(rig.ana2.device.deviceID)",
                                    "acc-bob/\(rig.bob1.device.deviceID)", "acc-bob/\(rig.bob2.device.deviceID)"].sorted())
        let membership = Membership(deps: rig.ana1.device.deps)
        let change = try await membership.update(conversation: conversation, db: rig.ana1.repo)
        #expect(change == MembershipChange(added: [], removed: [], ownDevicesAdded: []))
    }

    /// ana1's membership over an engine that reports real signature keys (32 bytes each) for the chat's members.
    private func realKeys(_ rig: Rig) async throws -> (membership: Membership, spy: SpyMLSEngine, conversation: ConversationRecord) {
        let conversation = try #require(try rig.ana1.repo.conversation(serverID: rig.conversationIDs[0]))
        var deps = rig.ana1.device.deps
        let spy = SpyMLSEngine(deps.mls)
        deps.mls = spy
        let identities = try rig.ana1.repo.members(conversationID: conversation.serverID).map(\.identity).sorted()
        for (index, identity) in identities.enumerated() { spy.setSignatureKey(Data(repeating: UInt8(index + 1), count: 32), for: identity) }
        return (Membership(deps: deps), spy, conversation)
    }

    @Test func keysAreTheEnginesRealKeys() async throws {
        let rig = try await Rig.make()
        let (membership, spy, conversation) = try await realKeys(rig)
        let group = GroupID(raw: conversation.groupID)
        let keys = try await membership.keys(of: group)
        #expect(keys == (try await spy.memberKeys(of: group)))
        #expect(!keys.contains { $0.signatureKey.starts(with: Data("fake-sig:".utf8)) })
    }

    /// Rows stored under the old placeholder keys are replaced by the real keys once, silently: no line, and a
    /// verification of the placeholder number is cleared.
    @Test func placeholderKeysAreRebaselinedSilently() async throws {
        let rig = try await Rig.make()
        let before = try #require(try rig.ana1.safety(with: "acc-bob"))
        try rig.ana1.repo.setVerified(chatID: "acc-bob", before)
        let (membership, spy, conversation) = try await realKeys(rig)
        _ = try await membership.update(conversation: conversation, db: rig.ana1.repo)

        let stored = try rig.ana1.repo.members(conversationID: conversation.serverID)
        #expect(stored.map(\.signatureKey) == (try await spy.memberKeys(of: GroupID(raw: conversation.groupID))).map(\.signatureKey))
        let row = try #require(try rig.ana1.repo.safety(chatID: "acc-bob"))
        #expect(row.current != before)
        #expect(row.verified == nil)
        #expect(try rig.ana1.lines(with: "acc-bob").isEmpty)
    }

    /// The re-baseline is silent only when the devices are unchanged: a device leaving at the same time is flagged.
    @Test func rebaselineWithDeviceChangeIsFlagged() async throws {
        let rig = try await Rig.make()
        let (membership, spy, conversation) = try await realKeys(rig)
        let bob2 = "acc-bob/\(rig.bob2.device.deviceID)"
        let keys = try await spy.memberKeys(of: GroupID(raw: conversation.groupID)).filter { $0.identity != bob2 }
        _ = try rig.ana1.repo.transaction { try membership.apply(keys: keys, conversation: conversation, db: rig.ana1.repo) }
        #expect(try rig.ana1.lines(with: "acc-bob") == [.deviceRemoved(name: "acc-bob"), .safetyNumberChanged])
    }

    /// The attack case: a member's signature key changes and the identity stays. The number changes and is flagged.
    @Test func realKeyChangeChangesNumber() async throws {
        let rig = try await Rig.make()
        let (membership, spy, conversation) = try await realKeys(rig)
        _ = try await membership.update(conversation: conversation, db: rig.ana1.repo)
        let before = try #require(try rig.ana1.safety(with: "acc-bob"))

        spy.setSignatureKey(Data(repeating: 0xEE, count: 32), for: "acc-bob/\(rig.bob1.device.deviceID)")
        let change = try await membership.update(conversation: conversation, db: rig.ana1.repo)
        #expect(change == MembershipChange())
        #expect(try rig.ana1.safety(with: "acc-bob") != before)
        #expect(try rig.ana1.lines(with: "acc-bob") == [.safetyNumberChanged])
    }

    /// The notice is persisted with the members. If the device list cannot be read, nothing is lost: the next
    /// run posts it, once.
    @Test func ownDeviceNoticeSurvivesFailedNameLookup() async throws {
        let server = FakeServer()
        let a1 = await makeDevice(server, account: "acc-ana", username: "ana")
        let a2 = await makeDevice(server, account: "acc-ana", username: "ana")
        let b1 = await makeDevice(server, account: "acc-bob", username: "bob")
        for d in [a1, a2, b1] { try await keyPackages(d) }
        let gate = FailGate()
        var deps2 = a2.deps
        deps2.server = ServerClient(
            host: FakeServer.host,
            session: SessionStore(storage: MemorySessionStorage(Session(
                host: FakeServer.host, accountID: "acc-ana", deviceID: a2.deviceID, token: "test.acc-ana.\(a2.deviceID)"))),
            transport: GateTransport(inner: await server.transport(for: a2), gate: gate))
        let flaky = TestDevice(accountID: a2.accountID, deviceID: a2.deviceID, deps: deps2, notifier: a2.notifier, directory: a2.directory)
        let n1 = Node.make(a1), n2 = Node.make(flaky)
        _ = try await n1.conversations.create(with: "acc-bob")
        try await n2.syncInbox()
        let ana3 = try await {
            let d = await makeDevice(server, account: "acc-ana", username: "ana")
            try await keyPackages(d)
            return d
        }()
        try await n1.sync.reconcileOwnDevices()

        gate.set(true)  // the process "dies" between saving the members and showing the notice
        try await n2.syncInbox()
        #expect(await n2.device.notifier.added.isEmpty)
        #expect(try n2.repo.get("notice.pending.\(ana3.deviceID)") == Data([1]))
        #expect(try n2.repo.members(conversationID: try #require(try n2.repo.conversations(chatID: "acc-bob").first).serverID)
            .contains { $0.identity == "acc-ana/\(ana3.deviceID)" })

        gate.set(false)
        try await n2.syncInbox()  // nothing new in the inbox; the pending marker is enough
        #expect(await n2.device.notifier.added.map(\.deviceID) == [ana3.deviceID])
        try await n2.syncInbox()
        #expect(await n2.device.notifier.added.count == 1)
    }
}

private final class FailGate: @unchecked Sendable {
    private let lock = NSLock()
    private var on = false
    var failing: Bool { lock.withLock { on } }
    func set(_ value: Bool) { lock.withLock { on = value } }
}

private struct GateTransport: HTTPTransport {
    let inner: HTTPTransport
    let gate: FailGate
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        if gate.failing, request.url?.path == "/v1/devices" { throw URLError(.notConnectedToInternet) }
        return try await inner.send(request)
    }
}

private final class StepClock: @unchecked Sendable {
    private let lock = NSLock()
    private var date = testEpochDate
    var now: Date { lock.withLock { date } }
    func advance(_ seconds: TimeInterval) { lock.withLock { date = date.addingTimeInterval(seconds) } }
}
