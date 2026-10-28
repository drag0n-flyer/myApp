import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

@MainActor
private struct Peer {
    let device: TestDevice
    let inbox: InboxProcessor
    let store: LiveChatStore
    let manager: ConversationManager

    init(_ device: TestDevice, mls: MLSEngine? = nil) {
        self.device = device
        var deps = device.deps
        if let mls { deps.mls = mls }
        let conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        manager = conversations
        let outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { true })
        inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }),
            controls: outbox)
        store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil)
    }

    static func make(_ server: FakeServer, _ name: String, spy: Bool = false) async throws -> Peer {
        let device = await makeDevice(server, account: "acc-\(name)", username: name)
        _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 3))
        return Peer(device, mls: spy ? SpyMLSEngine(device.deps.mls) : nil)
    }
}

private final class CommitBox: @unchecked Sendable { var commit = Data() }

private func postAddCommit(
    _ manager: ConversationManager, serverID: String, group: GroupID, mls: MLSEngine, keyPackage: [Data],
    addDevice: String, box: CommitBox
) async throws -> UInt64 {
    try await manager.postCommitWithRetry(serverID: serverID, group: group, addDevices: [addDevice], removeDevices: []) {
        let bundle = try await mls.add(keyPackages: keyPackage, to: group)
        box.commit = bundle.commit
        return bundle
    }
}

private let offer = CallSignalPayload(kind: .offer, callID: "c1", sdp: "v=0…", dtlsFingerprint: "sha-256 AB:CD")

@Suite("TestCallSignalSealing") @MainActor struct TestCallSignalSealing {
    @Test func callSignalRoundTrip() async throws {
        let server = FakeServer()
        let ana = try await Peer.make(server, "ana")
        let bob = try await Peer.make(server, "bob")
        let sealed = try await ana.store.seal(offer, to: "acc-bob")
        _ = try await bob.inbox.syncOnce()
        let opened = try await bob.store.open(sealed, from: "acc-ana")
        #expect(opened == offer)
        // The sender comes out of the decrypted message.
        let again = try await ana.store.seal(offer, to: "acc-bob")
        let recovered = try await bob.store.openRecoveringSender(again)
        #expect(recovered.senderAccountID == "acc-ana")
        #expect(recovered.payload == offer)
        // Opening the same bytes twice fails: the hash is marked processed.
        await #expect(throws: CallSealError.undecryptable) { try await bob.store.openRecoveringSender(again) }
    }

    @Test func openWrongSender() async throws {
        let server = FakeServer()
        let bob = try await Peer.make(server, "bob")
        let carol = try await Peer.make(server, "carol")
        let sealed = try await carol.store.seal(offer, to: "acc-bob")
        _ = try await bob.inbox.syncOnce()
        await #expect(throws: CallSealError.wrongSender) { try await bob.store.open(sealed, from: "acc-ana") }
    }

    @Test func openGarbage() async throws {
        let server = FakeServer()
        let bob = try await Peer.make(server, "bob")
        let garbage = Data((0..<64).map { _ in UInt8.random(in: 0...255) })
        await #expect(throws: CallSealError.undecryptable) { try await bob.store.open(garbage, from: "acc-ana") }
    }

    @Test func openNonCallContent() async throws {
        let server = FakeServer()
        let ana = try await Peer.make(server, "ana")
        let bob = try await Peer.make(server, "bob")
        let conversation = try await ana.store.conversations.preferredConversation(with: "acc-bob")
        let text = ContentEnvelope(id: UUID().uuidString.lowercased(), at: 1, body: .text("hello", replyTo: nil))
        let (sealed, _) = try await ana.device.deps.mls.encrypt(text.encoded(), in: GroupID(raw: conversation.groupID))
        _ = try await bob.inbox.syncOnce()
        await #expect(throws: CallSealError.undecryptable) { try await bob.store.open(sealed, from: "acc-ana") }
    }

    @Test func sealCreatesConversation() async throws {
        let server = FakeServer()
        let ana = try await Peer.make(server, "ana")
        let bob = try await Peer.make(server, "bob")
        #expect(try ana.device.deps.repo.preferredConversation(chatID: "acc-bob") == nil)
        let sealed = try await ana.store.seal(offer, to: "acc-bob")
        #expect(try ana.device.deps.repo.preferredConversation(chatID: "acc-bob") != nil)
        _ = try await bob.inbox.syncOnce()   // the welcome
        #expect(try await bob.store.open(sealed, from: "acc-ana") == offer)
        // Nothing was stored as a message in either chat.
        #expect(try ana.device.deps.repo.messages(chatID: "acc-bob", before: nil, limit: 10).isEmpty)
    }

    @Test func commitThroughCallChannelIsRejectedAndReconciledByInbox() async throws {
        let server = FakeServer()
        let ana = try await Peer.make(server, "ana")
        let bob = try await Peer.make(server, "bob")
        _ = try await ana.store.seal(offer, to: "acc-bob")   // creates the conversation
        _ = try await bob.inbox.syncOnce()
        // Ana gets a second device only now, so the commit that adds it is new to bob.
        let anaPhone = try await Peer.make(server, "ana")   // a second device of ana's account
        let conversation = try #require(try ana.device.deps.repo.preferredConversation(chatID: "acc-bob"))
        let group = GroupID(raw: conversation.groupID)
        let keyPackage = try await anaPhone.device.deps.mls.makeKeyPackages(count: 1)
        let box = CommitBox()
        let epoch = try await postAddCommit(
            ana.manager, serverID: conversation.serverID, group: group, mls: ana.device.deps.mls,
            keyPackage: keyPackage, addDevice: anaPhone.device.deviceID, box: box)
        let sent = box.commit
        // A commit is not a call payload: the sealer refuses it and writes nothing to the database.
        await #expect(throws: CallSealError.undecryptable) { try await bob.store.open(sent, from: "acc-ana") }
        // The normal inbox path reconciles it: stored epoch and group agree.
        _ = try await bob.inbox.syncOnce()
        let after = try #require(try bob.device.deps.repo.conversation(serverID: conversation.serverID))
        #expect(after.epoch == Int64(epoch))
        #expect(try await bob.device.deps.mls.epoch(of: GroupID(raw: after.groupID)) == epoch)
        let members = try await bob.device.deps.mls.members(of: GroupID(raw: after.groupID))
        #expect(members.contains("acc-ana/\(anaPhone.device.deviceID)"))
    }

    @Test func drainWaitingOnTheLockUsesTheAdoptedResult() async throws {
        let server = FakeServer()
        let ana = try await Peer.make(server, "ana")
        let bob = try await Peer.make(server, "bob", spy: true)
        let spy = try #require(bob.store.deps.mls as? SpyMLSEngine)
        _ = try await ana.store.seal(offer, to: "acc-bob")
        _ = try await bob.inbox.syncOnce()
        let anaPhone = try await Peer.make(server, "ana")
        let conversation = try #require(try ana.device.deps.repo.preferredConversation(chatID: "acc-bob"))
        let group = GroupID(raw: conversation.groupID)
        let keyPackage = try await anaPhone.device.deps.mls.makeKeyPackages(count: 1)
        let box = CommitBox()
        let epoch = try await postAddCommit(
            ana.manager, serverID: conversation.serverID, group: group, mls: ana.device.deps.mls,
            keyPackage: keyPackage, addDevice: anaPhone.device.deviceID, box: box)
        let commit = box.commit

        // The call path takes the lock and stops inside `process`; the inbox drain queues up behind it on the lock.
        let hold = spy.hold(commit)
        let call = Task { @MainActor in try? await bob.store.open(commit, from: "acc-ana") }
        for await _ in hold.entered { break }
        let drain = Task { @MainActor in try? await bob.inbox.syncOnce() }
        try await Task.sleep(for: .milliseconds(300))
        hold.release()
        _ = await call.value
        _ = await drain.value

        // The drain used the adopted result: no second `process` for the payload, and the epoch moved.
        #expect(spy.processed.filter { $0 == commit }.count == 1)
        let stored = try #require(try bob.device.deps.repo.conversation(serverID: conversation.serverID))
        #expect(stored.epoch == Int64(epoch))
        #expect(try bob.device.deps.repo.messages(chatID: "acc-ana", before: nil, limit: 20).allSatisfy { !$0.undecryptable })
    }

    @Test func textThroughCallChannelIsRejectedButStillStoredByInbox() async throws {
        let server = FakeServer()
        let ana = try await Peer.make(server, "ana")
        let bob = try await Peer.make(server, "bob")
        _ = try await ana.store.seal(offer, to: "acc-bob")
        _ = try await bob.inbox.syncOnce()
        _ = await ana.store.addContact(username: "bob")
        ana.store.send(.text("hello"), in: "acc-bob")
        await ana.store.settle()
        // Bob gets the text bytes through the call channel by mistake: rejected, but not lost.
        let item = try #require(try await bob.device.deps.server.inbox(after: nil, limit: 50).items.last)
        await #expect(throws: CallSealError.undecryptable) { try await bob.store.open(item.payload, from: "acc-ana") }
        _ = try await bob.inbox.syncOnce()
        #expect(try bob.device.deps.repo.messages(chatID: "acc-ana", before: nil, limit: 10).count == 1)
    }

    @Test func recordCallLocalOnly() async throws {
        let server = FakeServer()
        let ana = try await Peer.make(server, "ana")
        _ = try await Peer.make(server, "bob")
        _ = await ana.store.addContact(username: "bob")
        _ = ana.store.ensureChat(with: "acc-bob")
        let date = Date(timeIntervalSince1970: 1_700_000_500)
        let before = await server.requests().count
        ana.store.recordCall(.audio(duration: 125), contactID: "acc-bob", outgoing: true, at: date)
        let last = try #require(ana.store.chat(id: "acc-bob")?.messages.last)
        #expect(last.content == .call(.audio(duration: 125)))
        #expect(last.senderID == "acc-ana")
        #expect(last.sentAt == date)
        #expect(try ana.device.deps.repo.calls(limit: 10).count == 1)
        #expect(await server.requests().count == before)
        for id in await server.conversationIDs() { #expect(await server.storedMessages(in: id).isEmpty) }
    }
}
