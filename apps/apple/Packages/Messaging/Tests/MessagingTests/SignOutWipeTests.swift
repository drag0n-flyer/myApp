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
        let outbox = Outbox(deps: device.deps, conversations: manager)
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
