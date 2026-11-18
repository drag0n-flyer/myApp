import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

/// A `g:` chat ID reaching code that assumes a contact: no crash, nothing created, nothing sent (group plan, Review Focus 5).
@MainActor
private struct Rig {
    let server: FakeServer
    let device: TestDevice
    let conversations: ConversationManager
    let inbox: InboxProcessor
    let store: LiveChatStore
    var repo: Repository { device.deps.repo }

    static func make() async throws -> Rig {
        let server = FakeServer()
        let device = await makeDevice(server, account: "acc-ana", username: "ana")
        let deps = device.deps
        let conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        let outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }),
            controls: outbox)
        let store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil)
        let rig = Rig(server: server, device: device, conversations: conversations, inbox: inbox, store: store)
        try rig.repo.upsertGroup(conversationID: "c-1", title: "Club", photo: nil, at: testEpochDate)
        try rig.repo.setGroupMembers(chatID: "g:c-1", [GroupMemberRecord(accountID: "acc-ana", role: "owner"),
                                                       GroupMemberRecord(accountID: "acc-bob", role: "member")], version: 1)
        store.refresh()
        return rig
    }
}

@Suite("TestGroupChatSafety") @MainActor struct TestGroupChatSafety {
    @Test func aGroupChatListsWithoutAContact() async throws {
        let rig = try await Rig.make()
        let chat = try #require(rig.store.chat(id: "g:c-1"))
        #expect(chat.contactID == nil)
        #expect(rig.store.chats(in: .all).map(\.id) == ["g:c-1"])
        #expect(rig.store.contact(id: "g:c-1") == nil)
    }

    @Test func aOneToOneChatKeepsItsPeerAsContact() async throws {
        let rig = try await Rig.make()
        _ = rig.store.ensureChat(with: "acc-bob")
        #expect(rig.store.chat(id: "acc-bob")?.contactID == "acc-bob")
    }

    @Test func blockAndUnblockOfAGroupIDDoNothing() async throws {
        let rig = try await Rig.make()
        rig.store.block(contactID: "g:c-1")
        rig.store.unblock(contactID: "g:c-1")
        await rig.store.settle()
        #expect(rig.store.blockedIDs.isEmpty)
        #expect(rig.store.chat(id: "g:c-1") != nil)
        #expect(try rig.repo.entries(prefix: PendingActions.blockPrefix).isEmpty)
        #expect(try rig.repo.entries(prefix: PendingActions.unblockPrefix).isEmpty)
        let sent = await rig.server.requests()
        #expect(!sent.contains { $0.contains("/v1/blocks/") })
    }

    @Test func ensureChatWithAGroupIDMakesNoChatAndNoContact() async throws {
        let rig = try await Rig.make()
        #expect(rig.store.ensureChat(with: "g:c-1") == "g:c-1")
        #expect(rig.store.ensureChat(with: "g:nothing") == "g:nothing")
        #expect(try rig.repo.chats().map(\.id) == ["g:c-1"])
        #expect(try rig.repo.contacts().isEmpty)
    }

    @Test func sendingToAGroupChatThatIsNotThereMakesNothing() async throws {
        let rig = try await Rig.make()
        rig.store.send(.text("hi"), in: "g:nothing", replyTo: nil)
        await rig.store.settle()
        #expect(try rig.repo.chat(id: "g:nothing") == nil)
        #expect(try rig.repo.contacts().isEmpty)
        #expect(try rig.repo.dueOutbox(at: .distantFuture).isEmpty)
    }

    @Test func aCallIsNeverRecordedInAGroupChat() async throws {
        let rig = try await Rig.make()
        rig.store.recordCall(.missedAudio, contactID: "g:c-1", outgoing: false, at: testEpochDate)
        #expect(try rig.repo.messages(chatID: "g:c-1", before: nil, limit: 10).isEmpty)
        #expect(try rig.repo.chats().map(\.id) == ["g:c-1"])
    }

    @Test func callSignalsAreNeverSealedForOrOpenedFromAGroupID() async throws {
        let rig = try await Rig.make()
        let payload = CallSignalPayload(kind: .offer, callID: "call-1", sdp: "x")
        await #expect(throws: CallSealError.wrongSender) { _ = try await rig.store.seal(payload, to: "g:c-1") }
        await #expect(throws: CallSealError.wrongSender) { _ = try await rig.store.open(Data([1, 2, 3]), from: "g:c-1") }
        let sent = await rig.server.requests()
        #expect(!sent.contains { $0 == "POST /v1/conversations" })
    }

    @Test func aGroupChatIsNotAPeerForAOneToOneConversation() async throws {
        let rig = try await Rig.make()
        await #expect(throws: SyncError.noPeer) { _ = try await rig.conversations.preferredConversation(with: "g:c-1") }
        await #expect(throws: SyncError.noPeer) { _ = try await rig.conversations.preferredConversation(with: "g:other") }
        let sent = await rig.server.requests()
        #expect(!sent.contains { $0 == "POST /v1/conversations" })
        #expect(try rig.repo.chats().map(\.id) == ["g:c-1"])
    }

    @Test func aReportOfAGroupIDIsNeverQueued() async throws {
        let rig = try await Rig.make()
        rig.store.report(chatID: "g:c-1")
        #expect(try rig.repo.entries(prefix: PendingActions.reportPrefix).isEmpty)
    }

    @Test func noSafetyNumberForAGroupChat() async throws {
        let rig = try await Rig.make()
        #expect(rig.store.safetyNumber(for: "g:c-1") == nil)
    }

    @Test func membersItemsAreDroppedWithoutTouchingTheMessageStore() async throws {
        let rig = try await Rig.make()
        func item(_ cursor: Int64) -> RawInboxRecord {
            RawInboxRecord(cursor: cursor, kind: "members", conversationID: "c-1", seq: nil, epoch: 2, senderAccount: "acc-bob",
                           senderDevice: "d", payload: Data(), sentAt: testEpochDate)
        }
        try rig.repo.insertRaw([item(1), item(2)])
        await rig.inbox.drainRaw()
        #expect(try rig.repo.rawItems(limit: 10).isEmpty)
        #expect(try rig.repo.messages(chatID: "g:c-1", before: nil, limit: 10).isEmpty)
        // Nothing was recorded as processed: a members item has no payload to remember.
        #expect(try !rig.repo.isProcessed(hash: PortableSHA256.hash(Data())))
        #expect(try rig.repo.chat(id: "g:c-1")?.unread == 0)
    }
}
