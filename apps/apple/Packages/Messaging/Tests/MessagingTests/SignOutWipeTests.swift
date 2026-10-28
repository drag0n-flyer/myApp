import AppCore
import Foundation
import LocalStore
import Testing
@testable import Messaging
import MessagingTestKit

@Suite("TestSignOutWipe") @MainActor struct TestSignOutWipe {
    @Test func signOutWipesDatabaseMediaAndMLSStorage() async throws {
        let server = FakeServer()
        let device = await makeDevice(server, account: "acc-ana", username: "ana")
        let container = device.directory
        let mls = MessagingLocations.mlsURL(in: container)
        try FileManager.default.createDirectory(at: mls, withIntermediateDirectories: true)
        try Data([1, 2, 3]).write(to: mls.appendingPathComponent("group.db"))
        try Data([1]).write(to: device.deps.mediaDirectory.appendingPathComponent("a.bin"))

        let manager = ConversationManager(deps: device.deps)
        let outbox = Outbox(deps: device.deps, conversations: manager, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: device.deps, conversations: manager, handler: IncomingHandler(me: device.deps.me, now: device.deps.now, log: { _ in }))
        let store = LiveChatStore(deps: device.deps, outbox: outbox, inbox: inbox, conversations: manager, attachments: nil)
        _ = store.ensureChat(with: "acc-bob")
        try device.deps.repo.set("inbox.cursor", Data("7".utf8))
        #expect(store.chat(id: "acc-bob") != nil)

        // The app's sign-out path: `DeviceSync.wipeLocalData` with the MLS storage removal.
        let sync = DeviceSync(deps: device.deps, conversations: manager, wipeMLS: { try MessagingLocations.removeMLSStorage(in: container) })
        try await sync.wipeLocalData()

        // A new account signing in on the same device starts empty.
        store.refresh()
        #expect(store.chat(id: "acc-bob") == nil)
        #expect(store.messageRequests.isEmpty)
        #expect(store.contacts.isEmpty)
        #expect(try device.deps.repo.get("inbox.cursor") == nil)
        #expect(!FileManager.default.fileExists(atPath: mls.path))
        #expect(!FileManager.default.fileExists(atPath: device.deps.mediaDirectory.path))
    }

    @Test func removingMissingMLSStorageIsNotAnError() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("wipe-\(UUID().uuidString)")
        try MessagingLocations.removeMLSStorage(in: dir)
    }
}

private final class Calls: @unchecked Sendable {
    private let lock = NSLock()
    private var counts: [String: Int] = [:]
    func note(_ name: String) { lock.withLock { counts[name, default: 0] += 1 } }
    func count(_ name: String) -> Int { lock.withLock { counts[name, default: 0] } }
}

private struct Boom: Error {}

@Suite("TestLocalAccountData") @MainActor struct TestLocalAccountData {
    private func device() async throws -> (TestDevice, URL) {
        let server = FakeServer()
        let device = await makeDevice(server, account: "acc-ana", username: "ana")
        return (device, device.directory)
    }

    private func wipeSteps(_ device: TestDevice, _ calls: Calls, failSigningKey: Bool = false) -> @Sendable () async throws -> Void {
        let repo = device.deps.repo, media = device.deps.mediaDirectory, lock = device.deps.lock
        return {
            try await DeviceSync.wipe(
                repo: repo, mediaDirectory: media, lock: lock,
                wipeMLS: { calls.note("mls") },
                deleteSigningKey: { calls.note("key"); if failSigningKey { throw Boom() } })
        }
    }

    @Test func ownerMismatchWipesBeforeUse() async throws {
        let (device, container) = try await device()
        let repo = device.deps.repo
        try repo.set(LocalAccountData.ownerKey, Data("acc-ana".utf8))
        try repo.upsertChat(ChatRecord(id: "acc-x", isRequest: false, unread: 0, muted: false, lastActivity: testEpochDate))
        let calls = Calls()

        // The same account keeps its data.
        #expect(try await LocalAccountData.prepare(accountID: "acc-ana", container: container, repo: repo, wipe: wipeSteps(device, calls)) == false)
        #expect(try repo.chat(id: "acc-x") != nil)

        // Another account signs in on this device: wipe first (rows, MLS storage and the signing key), then own it.
        #expect(try await LocalAccountData.prepare(accountID: "acc-bob", container: container, repo: repo, wipe: wipeSteps(device, calls)) == true)
        #expect(try repo.chat(id: "acc-x") == nil)
        #expect(calls.count("mls") == 1 && calls.count("key") == 1)
        #expect(try repo.get(LocalAccountData.ownerKey).flatMap { String(data: $0, encoding: .utf8) } == "acc-bob")
        #expect(!LocalAccountData.isWipePending(in: container))
    }

    @Test func pendingWipeRunsEvenForTheSameAccount() async throws {
        let (device, container) = try await device()
        let repo = device.deps.repo
        try repo.set(LocalAccountData.ownerKey, Data("acc-ana".utf8))
        try repo.upsertChat(ChatRecord(id: "acc-x", isRequest: false, unread: 0, muted: false, lastActivity: testEpochDate))
        try LocalAccountData.markWipePending(in: container)
        let calls = Calls()
        #expect(try await LocalAccountData.prepare(accountID: "acc-ana", container: container, repo: repo, wipe: wipeSteps(device, calls)) == true)
        #expect(try repo.chat(id: "acc-x") == nil)
        #expect(!LocalAccountData.isWipePending(in: container))
    }

    @Test func failedWipeThrowsAndStaysPending() async throws {
        let (device, container) = try await device()
        let repo = device.deps.repo
        try repo.set(LocalAccountData.ownerKey, Data("acc-ana".utf8))
        let calls = Calls()
        await #expect(throws: Boom.self) {
            try await LocalAccountData.prepare(
                accountID: "acc-bob", container: container, repo: repo, wipe: wipeSteps(device, calls, failSigningKey: true))
        }
        // The other steps still ran, the marker stays for the next launch, and bob does not own the data.
        #expect(calls.count("mls") == 1)
        #expect(LocalAccountData.isWipePending(in: container))
        #expect(try repo.get(LocalAccountData.ownerKey).flatMap { String(data: $0, encoding: .utf8) } != "acc-bob")
        // The next launch retries and succeeds.
        #expect(try await LocalAccountData.prepare(accountID: "acc-bob", container: container, repo: repo, wipe: wipeSteps(device, Calls())) == true)
        #expect(!LocalAccountData.isWipePending(in: container))
    }

    @Test func freshDatabaseGetsOwnerWithoutError() async throws {
        let (device, container) = try await device()
        #expect(try await LocalAccountData.prepare(
            accountID: "acc-ana", container: container, repo: device.deps.repo, wipe: wipeSteps(device, Calls())) == true)
        #expect(try await LocalAccountData.prepare(
            accountID: "acc-ana", container: container, repo: device.deps.repo, wipe: wipeSteps(device, Calls())) == false)
    }

    @Test func shutdownStopsLaterWork() async throws {
        let server = FakeServer()
        let bob = await makeDevice(server, account: "acc-bob", username: "bob")
        _ = try await bob.deps.server.uploadKeyPackages(try await bob.deps.mls.makeKeyPackages(count: 1))
        let device = await makeDevice(server, account: "acc-ana", username: "ana")
        _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 1))
        let manager = ConversationManager(deps: device.deps)
        let outbox = Outbox(deps: device.deps, conversations: manager, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: device.deps, conversations: manager, handler: IncomingHandler(me: device.deps.me, now: device.deps.now, log: { _ in }))
        let store = LiveChatStore(deps: device.deps, outbox: outbox, inbox: inbox, conversations: manager, attachments: nil)
        _ = store.ensureChat(with: "acc-bob")
        await store.shutdown()
        store.send(.text("late"), in: "acc-bob")   // would start a background send
        await store.settle()
        await store.syncInbox()
        // Nothing reached the server: no conversation was created for the late message.
        #expect(await server.conversationIDs().isEmpty)
        #expect(try device.deps.repo.get("inbox.cursor") == nil)
    }
}
