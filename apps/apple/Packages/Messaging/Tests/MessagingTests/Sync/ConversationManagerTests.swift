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
private func manager(_ device: TestDevice) -> ConversationManager {
    ConversationManager(deps: device.deps, escrowKey: { escrowKey }, syncOnce: { try await syncInbox(device) })
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

    @Test func staleEpochRetries() async throws {
        let rig = try await Rig.make()
        await rig.server.rejectNextCommits(1)
        let record = try await manager(rig.ana1).create(with: "acc-bob")
        #expect(record.epoch == 1)
        #expect(try await rig.ana1.deps.mls.epoch(of: GroupID(raw: record.groupID)) == 1)
        #expect(await rig.server.memberDevices(of: record.serverID).count == 4)
    }

    @Test func staleEpochGivesUp() async throws {
        let rig = try await Rig.make()
        await rig.server.rejectNextCommits(4)
        await #expect(throws: SyncError.epochConflict) { try await manager(rig.ana1).create(with: "acc-bob") }
        #expect(try rig.ana1.deps.repo.conversations(chatID: "acc-bob").isEmpty)
        #expect(await rig.server.memberDevices(of: "conv-5").count == 1)
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
        #expect(first.serverID == (await rig.server.conversationIDs()).min())
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
}
