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

    init(_ device: TestDevice) {
        self.device = device
        let deps = device.deps
        let conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        let outbox = Outbox(deps: deps, conversations: conversations)
        inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }),
            controls: outbox)
        store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil)
    }

    static func make(_ server: FakeServer, _ name: String) async throws -> Peer {
        let device = await makeDevice(server, account: "acc-\(name)", username: name)
        _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 3))
        return Peer(device)
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
