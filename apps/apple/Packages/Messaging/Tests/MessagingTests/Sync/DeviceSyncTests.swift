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

private actor Flag {
    private(set) var value = false
    func set() { value = true }
}

/// One device with a real inbox processor and a `DeviceSync`.
private struct Node: Sendable {
    let device: TestDevice
    let processor: InboxProcessor
    let conversations: ConversationManager
    let sync: DeviceSync
    let mlsWiped: Flag

    func syncInbox() async throws { _ = try await processor.syncOnce() }

    static func make(_ device: TestDevice) -> Node {
        let box = ProcessorBox()
        let manager = ConversationManager(deps: device.deps, escrowKey: { escrowKey }, syncOnce: { _ = try await box.syncOnce() })
        let processor = InboxProcessor(
            deps: device.deps, conversations: manager,
            handler: IncomingHandler(me: device.deps.me, now: device.deps.now, log: { _ in }))
        box.set(processor)
        let flag = Flag()
        return Node(device: device, processor: processor, conversations: manager,
                    sync: DeviceSync(deps: device.deps, conversations: manager, wipeMLS: { await flag.set() }), mlsWiped: flag)
    }
}

private final class ProcessorBox: @unchecked Sendable {
    private let lock = NSLock()
    private var processor: InboxProcessor?
    func set(_ p: InboxProcessor) { lock.withLock { processor = p } }
    func syncOnce() async throws -> Int {
        guard let p = lock.withLock({ processor }) else { return 0 }
        return try await p.syncOnce()
    }
}

/// Records where the process lock is (not) held. MLS state changes must be under it; network calls must not.
private final class LockProbe: @unchecked Sendable {
    private let guardLock = NSLock()
    private var found: [String] = []
    var problems: [String] { guardLock.withLock { found } }
    private var file: ProcessLock?
    private var armed = false
    /// Test setup talks to the engine and server directly; checks start here.
    func arm() { guardLock.withLock { armed = true } }

    private func held() -> Bool { (try? file?.withLock(timeout: .milliseconds(30)) { false }) ?? true }
    func expectHeld(_ what: String) { if guardLock.withLock({ armed }), !held() { guardLock.withLock { found.append("\(what) without the lock") } } }
    func expectFree(_ what: String) { if guardLock.withLock({ armed }), held() { guardLock.withLock { found.append("\(what) under the lock") } } }

    func wrap(_ device: TestDevice, server: FakeServer) async -> TestDevice {
        file = ProcessLock(url: device.directory.appendingPathComponent("messaging.lock"))
        var deps = device.deps
        deps.mls = ProbeEngine(FakeMLSEngine(identity: "\(device.accountID)/\(device.deviceID)"), probe: self)
        deps.server = ServerClient(
            host: FakeServer.host,
            session: SessionStore(storage: MemorySessionStorage(Session(
                host: FakeServer.host, accountID: device.accountID, deviceID: device.deviceID,
                token: "test.\(device.accountID).\(device.deviceID)"))),
            transport: SyncProbeTransport(inner: await server.transport(for: device), probe: self))
        return TestDevice(accountID: device.accountID, deviceID: device.deviceID, deps: deps, notifier: device.notifier, directory: device.directory)
    }
}

private struct SyncProbeTransport: HTTPTransport {
    let inner: HTTPTransport
    let probe: LockProbe
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        probe.expectFree("\(request.httpMethod ?? "") \(request.url?.path ?? "")")
        return try await inner.send(request)
    }
}

private final class ProbeEngine: MLSEngine, @unchecked Sendable {
    let inner: MLSEngine
    let probe: LockProbe
    init(_ inner: MLSEngine, probe: LockProbe) { self.inner = inner; self.probe = probe }
    func makeKeyPackages(count: Int) async throws -> [Data] { probe.expectHeld("makeKeyPackages"); return try await inner.makeKeyPackages(count: count) }
    func createGroup() async throws -> GroupID { probe.expectHeld("createGroup"); return try await inner.createGroup() }
    func add(keyPackages: [Data], to group: GroupID) async throws -> CommitBundle { probe.expectHeld("add"); return try await inner.add(keyPackages: keyPackages, to: group) }
    func remove(members: [String], from group: GroupID) async throws -> CommitBundle { probe.expectHeld("remove"); return try await inner.remove(members: members, from: group) }
    func commitAccepted(_ group: GroupID) async throws { probe.expectHeld("commitAccepted"); try await inner.commitAccepted(group) }
    func commitRejected(_ group: GroupID) async throws { probe.expectHeld("commitRejected"); try await inner.commitRejected(group) }
    func encrypt(_ plaintext: Data, in group: GroupID) async throws -> (ciphertext: Data, epoch: UInt64) { probe.expectHeld("encrypt"); return try await inner.encrypt(plaintext, in: group) }
    func process(_ message: Data) async throws -> Incoming { probe.expectHeld("process"); return try await inner.process(message) }
    func epoch(of group: GroupID) async throws -> UInt64 { try await inner.epoch(of: group) }
    func exportSecret(group: GroupID, label: String, length: Int) async throws -> Data { try await inner.exportSecret(group: group, label: label, length: length) }
    func members(of group: GroupID) async throws -> [String] { try await inner.members(of: group) }
    func memberKeys(of group: GroupID) async throws -> [MemberKey] { try await inner.memberKeys(of: group) }
    func stageProcess(_ group: GroupID, _ message: Data) async throws -> StagedProcess { try await inner.stageProcess(group, message) }
    func confirm(_ staged: StagedProcess) async throws { probe.expectHeld("confirm"); try await inner.confirm(staged) }
    func discard(_ staged: StagedProcess) { inner.discard(staged) }
    func decryptApplication(_ group: GroupID, _ message: Data) async throws -> ApplicationMessage { try await inner.decryptApplication(group, message) }
}

private func keyPackages(_ device: TestDevice, count: Int = 5) async throws {
    _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: count))
}

private struct Rig {
    let server: FakeServer
    let ana1: Node, ana2: Node
    let conversationIDs: [String]

    /// ana1 has one conversation with bob and one with carol; ana2 has joined both.
    /// - Parameter clock: ana1's clock (the default is the fixed test time).
    static func make(mode: String = "e2ee", probe: LockProbe? = nil, clock: MutableClock? = nil) async throws -> Rig {
        let server = FakeServer()
        await server.setDefaultMode(mode)
        let now: @Sendable () -> Date = if let clock { { clock.now } } else { { testEpochDate } }
        var a1 = await makeDevice(server, account: "acc-ana", username: "ana", now: now)
        if let probe { a1 = await probe.wrap(a1, server: server) }
        let a2 = await makeDevice(server, account: "acc-ana", username: "ana")
        let b1 = await makeDevice(server, account: "acc-bob", username: "bob")
        let c1 = await makeDevice(server, account: "acc-carol", username: "carol")
        for d in [a1, a2, b1, c1] { try await keyPackages(d) }
        let n1 = Node.make(a1), n2 = Node.make(a2)
        let first = try await n1.conversations.create(with: "acc-bob")
        let second = try await n1.conversations.create(with: "acc-carol")
        try await n2.syncInbox()
        return Rig(server: server, ana1: n1, ana2: n2, conversationIDs: [first.serverID, second.serverID])
    }

    func addAna3() async throws -> Node {
        let a3 = await makeDevice(server, account: "acc-ana", username: "ana")
        try await keyPackages(a3)
        return Node.make(a3)
    }
}

private final class MutableClock: @unchecked Sendable {
    private let lock = NSLock()
    private var date = testEpochDate
    var now: Date { lock.withLock { date } }
    func advance(_ seconds: TimeInterval) { lock.withLock { date = date.addingTimeInterval(seconds) } }
    func set(_ new: Date) { lock.withLock { date = new } }
}

private final class CountingTransport: HTTPTransport, @unchecked Sendable {
    let inner: HTTPTransport
    private let lock = NSLock()
    private var log: [String] = []
    init(_ inner: HTTPTransport) { self.inner = inner }
    var requests: [String] { lock.withLock { log } }
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        lock.withLock { log.append("\(request.httpMethod ?? "") \(request.url?.path ?? "")") }
        return try await inner.send(request)
    }
}

@Suite("TestDeviceSync") struct TestDeviceSync {
    @Test func newOwnDeviceAdded() async throws {
        let rig = try await Rig.make()
        let ana3 = try await rig.addAna3()
        try await rig.ana1.sync.reconcileOwnDevices()

        for id in rig.conversationIDs {
            let members = await rig.server.memberDevices(of: id)
            #expect(members.contains(ana3.device.deviceID))
            #expect(try await rig.ana1.device.deps.mls.members(of: GroupID(raw: try #require(
                try rig.ana1.device.deps.repo.conversation(serverID: id)).groupID)).contains("acc-ana/\(ana3.device.deviceID)"))
        }
        // ana3 received a welcome for each conversation.
        let items = try await ana3.device.deps.server.inbox(after: nil).items
        #expect(items.filter { $0.kind == "welcome" }.count == 2)

        // ana2 reconciles from a stale state: its commit loses, then it sees ana3 is already there.
        try await rig.ana2.sync.reconcileOwnDevices()
        for id in rig.conversationIDs {
            let epoch = try #require(try rig.ana1.device.deps.repo.conversation(serverID: id)).epoch
            // create: epoch 1; the one add: epoch 2. ana2 added nothing.
            #expect(epoch == 2)
            #expect(try await ana3.device.deps.server.conversation(id: id).epoch == 2)
        }
        let ana3Welcomes = try await ana3.device.deps.server.inbox(after: nil).items.filter { $0.kind == "welcome" }
        #expect(ana3Welcomes.count == 2)
    }

    @Test func revokedEventOnlyRunsReconcile() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        let ana2ID = rig.ana2.device.deviceID
        // The event is unsigned: while the server still lists ana2 it removes nothing.
        try await rig.ana1.sync.handleRevoked(deviceID: ana2ID)
        clock.advance(10 * 60)
        try await rig.ana1.sync.handleRevoked(deviceID: ana2ID)
        for id in rig.conversationIDs {
            #expect(await rig.server.memberDevices(of: id).contains(ana2ID))
            #expect(try rig.ana1.device.deps.repo.conversation(serverID: id)?.epoch == 1)
        }
        // Once the server has dropped it, the two-lists-ten-minutes rule applies: first a mark, then the Remove.
        await rig.server.revokeDevice(account: "acc-ana", deviceID: ana2ID)
        try await rig.ana1.sync.handleRevoked(deviceID: ana2ID)
        #expect(try rig.ana1.device.deps.repo.conversation(serverID: rig.conversationIDs[0])?.epoch == 1)
        clock.advance(10 * 60)
        try await rig.ana1.sync.handleRevoked(deviceID: ana2ID)
        for id in rig.conversationIDs {
            let members = try await rig.ana1.device.deps.mls.members(of: GroupID(raw: try #require(
                try rig.ana1.device.deps.repo.conversation(serverID: id)).groupID))
            #expect(!members.contains("acc-ana/\(ana2ID)"))
            #expect(try rig.ana1.device.deps.repo.conversation(serverID: id)?.epoch == 2)
        }
        // A further event has nothing left to do.
        try await rig.ana1.sync.handleRevoked(deviceID: ana2ID)
        #expect(try rig.ana1.device.deps.repo.conversation(serverID: rig.conversationIDs[0])?.epoch == 2)
    }

    @Test func revokedEventNamingThisDeviceIsIgnored() async throws {
        let rig = try await Rig.make()
        let deps = rig.ana1.device.deps
        let commits = await rig.server.requests().count
        // Unsigned, and never sent to the revoked device itself: forged or buggy. It must not wipe.
        try await rig.ana1.sync.handleRevoked(deviceID: deps.me.deviceID)
        #expect(try !deps.repo.chats().isEmpty)
        #expect(!(await rig.ana1.mlsWiped.value))
        #expect(await rig.server.requests().count == commits, "no request at all")
    }

    @Test func replenish() async throws {
        let server = FakeServer()
        let clock = MutableClock()
        let device = await makeDevice(server, account: "acc-ana", username: "ana", now: { clock.now })
        var deps = device.deps
        let counting = CountingTransport(await server.transport(for: device))
        deps.server = ServerClient(
            host: FakeServer.host,
            session: SessionStore(storage: MemorySessionStorage(Session(
                host: FakeServer.host, accountID: "acc-ana", deviceID: device.deviceID, token: "test.acc-ana.\(device.deviceID)"))),
            transport: counting)
        let sync = DeviceSync(deps: deps, conversations: ConversationManager(deps: deps), wipeMLS: {})

        try await keyPackages(device, count: 4)
        let setup = counting.requests.count
        try await sync.replenishKeyPackages()
        #expect(counting.requests.dropFirst(setup).contains("POST /v1/keypackages"))
        #expect(try await deps.server.keyPackageCount() == 54)

        // Within 6 hours: no request at all.
        let after = counting.requests.count
        clock.advance(5 * 3600)
        try await sync.replenishKeyPackages()
        #expect(counting.requests.count == after)

        // After 6 hours the count is checked; 54 is enough, so nothing is uploaded.
        clock.advance(2 * 3600)
        let uploads = counting.requests.filter { $0 == "POST /v1/keypackages" }.count
        try await sync.replenishKeyPackages()
        #expect(counting.requests.last == "GET /v1/keypackages/count")
        #expect(counting.requests.filter { $0 == "POST /v1/keypackages" }.count == uploads)
    }

    @Test func replenishAtTwelveUploadsNothing() async throws {
        let server = FakeServer()
        let device = await makeDevice(server, account: "acc-ana", username: "ana")
        try await keyPackages(device, count: 12)
        try await DeviceSync(deps: device.deps, conversations: ConversationManager(deps: device.deps), wipeMLS: {}).replenishKeyPackages()
        #expect(try await device.deps.server.keyPackageCount() == 12)
    }

    @Test func snapshotRoundTrip() async throws {
        let server = FakeServer()
        let a1 = await makeDevice(server, account: "acc-ana", username: "ana")
        let a4 = await makeDevice(server, account: "acc-ana", username: "ana")
        let repo = a1.deps.repo
        try repo.upsertContact(ContactRecord(accountID: "acc-bob", username: "bob", displayName: "Bob", isContact: true, addedAt: testEpochDate))
        try repo.upsertContact(ContactRecord(accountID: "acc-eve", username: "eve", displayName: "Eve", isContact: false, addedAt: testEpochDate))
        try repo.upsertChat(ChatRecord(id: "acc-bob", isRequest: false, unread: 3, muted: true, lastActivity: testEpochDate))
        try repo.upsertChat(ChatRecord(id: "acc-eve", isRequest: true, unread: 1, muted: false, lastActivity: testEpochDate))
        try repo.addFolder(FolderRecord(id: "f1", name: "Family", position: 0))
        try repo.setFolderChats(folderID: "f1", chatIDs: ["acc-bob"])
        try repo.block("acc-spam")
        try repo.set("setting.callMode", Data("auto".utf8))
        try repo.set("inbox.cursor", Data("42".utf8))  // internal state never travels
        _ = try repo.insertMessage(MessageRecord(
            id: "m1", chatID: "acc-bob", conversationID: nil, senderAccount: "acc-bob", senderDevice: nil, sentAt: testEpochDate,
            receivedAt: testEpochDate, envelope: Data("{}".utf8), status: "delivered", replyTo: nil, editedAt: nil,
            deleted: false, undecryptable: false, folded: "hi"))

        let data = try await DeviceSync(deps: a1.deps, conversations: ConversationManager(deps: a1.deps), wipeMLS: {}).exportSnapshot()
        let fresh = DeviceSync(deps: a4.deps, conversations: ConversationManager(deps: a4.deps), wipeMLS: {})
        try await fresh.importSnapshot(data)

        let r2 = a4.deps.repo
        #expect(try r2.contacts() == repo.contacts())
        #expect(try r2.chats().map { [$0.id, "\($0.isRequest)", "\($0.muted)"] } == repo.chats().map { [$0.id, "\($0.isRequest)", "\($0.muted)"] })
        #expect(try r2.folders() == repo.folders())
        #expect(try r2.folderChats(folderID: "f1") == ["acc-bob"])
        #expect(try r2.blockedIDs() == ["acc-spam"])
        #expect(try r2.get("setting.callMode") == Data("auto".utf8))
        #expect(try r2.get("inbox.cursor") == nil)
        #expect(try r2.messages(chatID: "acc-bob", before: nil, limit: 10).isEmpty)
        // Importing twice changes nothing.
        try await fresh.importSnapshot(data)
        #expect(try r2.folders().count == 1)

        await #expect(throws: DeviceSyncError.invalidSnapshot) { try await fresh.importSnapshot(Data("{\"v\":2}".utf8)) }
    }

    @Test func adderResendsMode() async throws {
        let rig = try await Rig.make(mode: "server_readable")
        let ana3 = try await rig.addAna3()
        try await rig.ana1.sync.reconcileOwnDevices()
        try await ana3.syncInbox()
        for id in rig.conversationIDs {
            let theirs = try #require(try ana3.device.deps.repo.conversation(serverID: id))
            let mine = try #require(try rig.ana1.device.deps.repo.conversation(serverID: id))
            #expect(theirs.innerMode == "server_readable")
            #expect(theirs.innerMode == mine.innerMode)
        }
    }

    @Test func claimsOnlyMissingDevices() async throws {
        let rig = try await Rig.make()
        let ana3 = try await rig.addAna3()
        let c1 = try await rig.ana1.device.deps.server.keyPackageCount()
        let c2 = try await rig.ana2.device.deps.server.keyPackageCount()
        let c3 = try await ana3.device.deps.server.keyPackageCount()
        try await rig.ana1.sync.reconcileOwnDevices()
        // Neither this device nor ana2 (already in both groups) lost a KeyPackage; ana3 gave one per conversation.
        #expect(try await rig.ana1.device.deps.server.keyPackageCount() == c1)
        #expect(try await rig.ana2.device.deps.server.keyPackageCount() == c2)
        #expect(try await ana3.device.deps.server.keyPackageCount() == c3 - 2)
    }

    @Test func lockOnMLSChangesNeverAcrossNetwork() async throws {
        let probe = LockProbe()
        let rig = try await Rig.make(probe: probe)
        let ana3 = try await rig.addAna3()
        probe.arm()
        try await rig.ana1.sync.reconcileOwnDevices()
        try await rig.ana1.sync.handleRevoked(deviceID: ana3.device.deviceID)
        try await rig.ana1.sync.replenishKeyPackages()
        try await rig.ana1.conversations.handleStaleEpoch(try #require(try rig.ana1.device.deps.repo.conversation(serverID: rig.conversationIDs[0])))
        #expect(probe.problems == [], "\(probe.problems)")
    }

    @Test func lockedProcessBlocksChangesAndWipe() async throws {
        let rig = try await Rig.make()
        let device = rig.ana1.device
        let manager = ConversationManager(deps: device.deps, lockTimeout: .milliseconds(100))
        let sync = DeviceSync(deps: device.deps, conversations: manager, wipeMLS: {}, lockTimeout: .milliseconds(100))
        let record = try #require(try device.deps.repo.conversation(serverID: rig.conversationIDs[0]))
        try await device.deps.lock.withAsyncLock(timeout: .seconds(2)) {
            await #expect(throws: LockTimeout.self) { try await sync.wipeLocalData() }
            await #expect(throws: LockTimeout.self) { try await sync.replenishKeyPackages() }
            await #expect(throws: LockTimeout.self) { try await manager.handleStaleEpoch(record) }
            await #expect(throws: LockTimeout.self) { _ = try await manager.create(with: "acc-bob") }
        }
        #expect(try !device.deps.repo.chats().isEmpty)
    }
}

/// Own-device reconcile safety (mls-profile.md "Removing a device"; decision log 2026-10-28): a device is removed
/// only when missing from two lists at least ten minutes apart, and only when the list contains this device.
/// Port of Android's `DeviceSyncTest` safety tests.
@Suite("TestOwnDeviceReconcile") struct TestOwnDeviceReconcile {
    private let tenMinutes: TimeInterval = 10 * 60

    private func members(_ node: Node, _ serverID: String) async throws -> [String] {
        let record = try #require(try node.device.deps.repo.conversation(serverID: serverID))
        return try await node.device.deps.mls.members(of: GroupID(raw: record.groupID))
    }

    /// True when every conversation of ana1 has `"acc-ana/<deviceID>"`.
    private func inAll(_ rig: Rig, _ deviceID: String) async throws -> Bool {
        for id in rig.conversationIDs where !(try await members(rig.ana1, id)).contains("acc-ana/\(deviceID)") { return false }
        return true
    }

    /// True when no conversation of ana1 has `"acc-ana/<deviceID>"`.
    private func inNone(_ rig: Rig, _ deviceID: String) async throws -> Bool {
        for id in rig.conversationIDs where (try await members(rig.ana1, id)).contains("acc-ana/\(deviceID)") { return false }
        return true
    }

    private func commitPosts(_ rig: Rig) async -> Int {
        await rig.server.requests().filter { $0.hasPrefix("POST /v1/conversations/") && $0.hasSuffix("/commits") }.count
    }

    private static func without(_ id: String) -> @Sendable ([String]) -> [String] { { $0.filter { $0 != id } } }
    private static func only(_ id: String) -> @Sendable ([String]) -> [String] { { $0.filter { $0 == id } } }

    @Test func deviceThatMissedTheRevocationCatchesUpWhenItComesOnline() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        let ana2 = rig.ana2.device.deviceID
        // ana1 was offline for the event; now it compares the group's leaves with GET /v1/devices.
        await rig.server.revokeDevice(account: "acc-ana", deviceID: ana2)
        try await rig.ana1.sync.reconcileOwnDevices()
        clock.advance(tenMinutes)
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inNone(rig, ana2))
        #expect(try await inAll(rig, rig.ana1.device.deviceID))
        // Nothing left to do: another pass posts no commit.
        let commits = await commitPosts(rig)
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(await commitPosts(rig) == commits)
    }

    @Test func removalNeedsTwoListsTenMinutesApart() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        let ana2 = rig.ana2.device.deviceID
        await rig.server.revokeDevice(account: "acc-ana", deviceID: ana2)
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inAll(rig, ana2), "the first sighting only records the time")
        clock.advance(tenMinutes - 0.001)
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inAll(rig, ana2), "not yet ten minutes")
        clock.advance(0.001)
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inNone(rig, ana2))
    }

    @Test func missingTimeSurvivesARestart() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        let ana2 = rig.ana2.device.deviceID
        await rig.server.revokeDevice(account: "acc-ana", deviceID: ana2)
        try await rig.ana1.sync.reconcileOwnDevices()
        let restarted = Node.make(rig.ana1.device)
        clock.advance(tenMinutes)
        try await restarted.sync.reconcileOwnDevices()
        #expect(try await inNone(rig, ana2))
    }

    @Test func deviceThatReappearsInTheListStartsOverAfterwards() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        let ana2 = rig.ana2.device.deviceID
        let base = clock.now
        await rig.server.setDeviceListFilter(Self.without(ana2))
        try await rig.ana1.sync.reconcileOwnDevices()                    // missing since base
        clock.set(base.addingTimeInterval(tenMinutes))
        await rig.server.setDeviceListFilter(nil)
        try await rig.ana1.sync.reconcileOwnDevices()                    // listed again: the mark is cleared
        await rig.server.setDeviceListFilter(Self.without(ana2))
        clock.set(base.addingTimeInterval(2 * tenMinutes))
        try await rig.ana1.sync.reconcileOwnDevices()                    // missing since base + 20 min
        // Last listed 15 minutes ago, so only the cleared mark keeps it: missing for 5 minutes, not 25.
        clock.set(base.addingTimeInterval(2.5 * tenMinutes))
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inAll(rig, ana2), "the earlier sighting was cleared")
        // Ten minutes after the new mark it goes.
        clock.set(base.addingTimeInterval(3 * tenMinutes))
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inNone(rig, ana2))
    }


    @Test func emptyListRemovesNothing() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        await rig.server.setDeviceListFilter { _ in [] }
        let commits = await commitPosts(rig)
        try await rig.ana1.sync.reconcileOwnDevices()
        clock.advance(2 * tenMinutes)
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(await commitPosts(rig) == commits)
        #expect(try await inAll(rig, rig.ana2.device.deviceID))
    }

    @Test func listWithoutThisDeviceRemovesNothing() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        let ana2 = rig.ana2.device.deviceID
        // Not empty, but without this device (and without ana2, which a trusted list would make stale). The listed
        // device has no KeyPackages, so adding it fails; the removal step still runs.
        await rig.server.setDeviceListFilter { _ in ["dev-unknown"] }
        let commits = await commitPosts(rig)
        try? await rig.ana1.sync.reconcileOwnDevices()
        clock.advance(2 * tenMinutes)
        try? await rig.ana1.sync.reconcileOwnDevices()
        #expect(await commitPosts(rig) == commits)
        #expect(try await inAll(rig, ana2))
        #expect(try await inAll(rig, rig.ana1.device.deviceID))
        // The untrusted lists left no mark: a trusted list lacking ana2 starts the ten minutes now.
        await rig.server.setDeviceListFilter(Self.without(ana2))
        try await rig.ana1.sync.reconcileOwnDevices()
        clock.advance(tenMinutes)
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inNone(rig, ana2))
    }


    @Test func listWithoutThisDeviceButWithAForeignOneRemovesNothing() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        // Not empty, but neither ana1 (this device) nor ana2: the answer cannot be trusted. ana3 has KeyPackages, so
        // adding it works and the removal step is reached.
        let ana3 = try await rig.addAna3().device.deviceID
        await rig.server.setDeviceListFilter(Self.only(ana3))
        try? await rig.ana1.sync.reconcileOwnDevices()
        clock.advance(2 * tenMinutes)
        try? await rig.ana1.sync.reconcileOwnDevices()
        clock.advance(2 * tenMinutes)
        try? await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inAll(rig, ana3), "the add path ran, so the removal step was reached")
        #expect(try await inAll(rig, rig.ana2.device.deviceID))
        #expect(try await inAll(rig, rig.ana1.device.deviceID))
    }

    @Test func partialListRemovesOnlyTheDevicesMissingFromTwoLists() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        let ana1 = rig.ana1.device.deviceID, ana2 = rig.ana2.device.deviceID
        let ana3 = try await rig.addAna3().device.deviceID
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inAll(rig, ana3))
        // A partial answer lacks ana2 and ana3; ana3 shows up again in the second list, ana2 does not.
        await rig.server.setDeviceListFilter(Self.only(ana1))
        try await rig.ana1.sync.reconcileOwnDevices()
        await rig.server.setDeviceListFilter(Self.without(ana2))
        clock.advance(tenMinutes)
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inNone(rig, ana2))
        #expect(try await inAll(rig, ana3))
        #expect(try await inAll(rig, ana1))
    }

    @Test func deviceLinkedDuringTheReconcileIsNeverRemoved() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        let ana2 = rig.ana2.device.deviceID
        // The list was fetched before ana2 was linked far enough to appear; another device already added it.
        let first = clock.now
        await rig.server.setDeviceListFilter(Self.without(ana2))
        try await rig.ana1.sync.reconcileOwnDevices()
        clock.set(first.addingTimeInterval(tenMinutes - 0.001))
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inAll(rig, ana2), "one list, or two less than ten minutes apart, removes nothing")
        // Still missing ten minutes after the first list: now it goes.
        clock.set(first.addingTimeInterval(tenMinutes))
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inNone(rig, ana2))
    }

    @Test func failedAddDoesNotBlockRemoval() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        let ana2 = rig.ana2.device.deviceID
        // ana3 is linked but has no KeyPackages: adding it fails in every conversation.
        let ana3 = await rig.server.registerDevice(account: "acc-ana", username: "ana")
        await rig.server.revokeDevice(account: "acc-ana", deviceID: ana2)
        await #expect(throws: SyncError.noKeyPackages) { try await rig.ana1.sync.reconcileOwnDevices() }
        clock.advance(tenMinutes)
        await #expect(throws: SyncError.noKeyPackages) { try await rig.ana1.sync.reconcileOwnDevices() }
        #expect(try await inNone(rig, ana2), "removal ran although the add failed")
        #expect(try await inNone(rig, ana3))
    }


    @Test func deviceListedAfterTheMissingMarkWithTheClockBehindIsNotRemoved() async throws {
        let clock = MutableClock()
        let rig = try await Rig.make(clock: clock)
        let ana2 = rig.ana2.device.deviceID
        let base = clock.now
        // ana2 is listed at base + 30 min ...
        clock.set(base.addingTimeInterval(3 * tenMinutes))
        try await rig.ana1.sync.reconcileOwnDevices()
        // ... then the wall clock is back at base: the missing mark (base) is ten minutes old at base + 10 min, but
        // ana2 was seen "in the future", so the seen check keeps it.
        clock.set(base)
        await rig.server.setDeviceListFilter(Self.without(ana2))
        try await rig.ana1.sync.reconcileOwnDevices()
        clock.set(base.addingTimeInterval(tenMinutes))
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inAll(rig, ana2))
    }

    @Test func removalKeepsTheLockOffTheNetwork() async throws {
        let probe = LockProbe()
        let clock = MutableClock()
        let rig = try await Rig.make(probe: probe, clock: clock)
        let ana2 = rig.ana2.device.deviceID
        await rig.server.revokeDevice(account: "acc-ana", deviceID: ana2)
        probe.arm()
        try await rig.ana1.sync.reconcileOwnDevices()
        clock.advance(tenMinutes)
        try await rig.ana1.sync.reconcileOwnDevices()
        #expect(try await inNone(rig, ana2))
        #expect(probe.problems == [], "\(probe.problems)")
    }
}
