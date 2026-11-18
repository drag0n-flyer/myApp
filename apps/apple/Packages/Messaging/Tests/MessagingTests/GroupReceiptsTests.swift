import AppCore
import Foundation
import LocalStore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

@MainActor
struct GroupMember3 {
    let device: TestDevice
    let outbox: Outbox
    let inbox: InboxProcessor
    let store: LiveChatStore
    var repo: Repository { device.deps.repo }

    init(_ device: TestDevice) {
        self.device = device
        let deps = device.deps
        let conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { true })
        inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }), controls: outbox)
        store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil)
    }

    func know(_ account: String, _ name: String, contact: Bool = true) throws {
        try repo.upsertContact(ContactRecord(accountID: account, username: name.lowercased(), displayName: name, isContact: contact, addedAt: testEpochDate))
        store.refresh()
    }
}

/// Ana creates a group with bob and cy; each device runs the app's own store and inbox.
@MainActor
struct GroupOfThree {
    let rig: GroupRig
    let ana: GroupMember3, bob: GroupMember3, cy: GroupMember3
    let chatID: String

    static func make() async throws -> GroupOfThree {
        let rig = try await GroupRig.make()
        let ana = GroupMember3(rig.ana), bob = GroupMember3(rig.bob[0]), cy = GroupMember3(rig.cy[0])
        guard case .created(let chatID, _) = await ana.store.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Book club", photo: nil) else {
            throw GroupOfThreeError.notCreated
        }
        await ana.store.flushOutbox()
        await bob.store.syncInbox()
        await cy.store.syncInbox()
        return GroupOfThree(rig: rig, ana: ana, bob: bob, cy: cy, chatID: chatID)
    }

    /// Ana's newest message in the group, as the screen would show it.
    func newestOfAna() -> Message? { ana.store.messages(in: chatID, before: nil, limit: 20).last { $0.senderID == "acc-ana" } }
}

enum GroupOfThreeError: Error { case notCreated }

@Suite("GroupReceipts") @MainActor struct GroupReceiptsTests {
    @Test func theTickWaitsForEveryOtherMemberAndReadsLikeTheyDo() async throws {
        let g = try await GroupOfThree.make()
        g.ana.store.send(.text("hi all"), in: g.chatID, replyTo: nil)
        await g.ana.store.flushOutbox()
        let id = try #require(g.newestOfAna()).id
        #expect(g.newestOfAna()?.status == .sent)

        // Bob has it: one of two is not enough. His delivered receipt is stored, per member.
        await g.bob.store.syncInbox()
        await g.ana.store.syncInbox()
        #expect(g.newestOfAna()?.status == .sent)
        #expect(try g.ana.repo.receipts(messageID: id).map { "\($0.accountID):\($0.kind)" } == ["acc-bob:delivered"])
        // Cy has it too: delivered.
        await g.cy.store.syncInbox()
        await g.ana.store.syncInbox()
        #expect(g.newestOfAna()?.status == .delivered)

        // Bob reads: still delivered. Cy reads: read.
        g.bob.store.markRead(chatID: g.chatID)
        await g.bob.store.settle()
        await g.ana.store.syncInbox()
        #expect(g.newestOfAna()?.status == .delivered)
        g.cy.store.markRead(chatID: g.chatID)
        await g.cy.store.settle()
        await g.ana.store.syncInbox()
        #expect(g.newestOfAna()?.status == .read)
        // A repeat adds nothing: one row per member and kind.
        #expect(try g.ana.repo.receipts(messageID: id).count == 4)
    }

    @Test func aMemberWhoLeftDoesNotHoldTheTickBack() async throws {
        let g = try await GroupOfThree.make()
        g.ana.store.send(.text("hello"), in: g.chatID, replyTo: nil)
        await g.ana.store.flushOutbox()
        await g.bob.store.syncInbox()
        await g.ana.store.syncInbox()
        #expect(g.newestOfAna()?.status == .sent)
        // Cy leaves: the list is ana and bob only.
        try g.ana.repo.setGroupMembers(chatID: g.chatID, [GroupMemberRecord(accountID: "acc-ana", role: "owner"), GroupMemberRecord(accountID: "acc-bob", role: "member")], version: 3)
        g.ana.store.refresh()
        #expect(g.newestOfAna()?.status == .delivered)
    }

    @Test func aReceiptFromAnAccountThatIsNotInTheListOrForSomeoneElsesMessageIsNotStored() async throws {
        let g = try await GroupOfThree.make()
        g.ana.store.send(.text("mine"), in: g.chatID, replyTo: nil)
        await g.ana.store.flushOutbox()
        await g.bob.store.syncInbox()
        await g.cy.store.syncInbox()
        let mine = try #require(g.newestOfAna()).id
        // Bob posts a message; cy's receipt for bob's message reaches ana, who did not send it: nothing stored.
        g.bob.store.send(.text("bobs"), in: g.chatID, replyTo: nil)
        await g.bob.store.flushOutbox()
        await g.cy.store.syncInbox()
        await g.ana.store.syncInbox()
        let bobs = try #require(g.ana.store.messages(in: g.chatID, before: nil, limit: 20).last { $0.senderID == "acc-bob" }).id
        #expect(try g.ana.repo.receipts(messageID: bobs).isEmpty)
        #expect(try g.ana.repo.receipts(messageID: mine).count == 2)
    }

    @Test func messageInfoListsWhoReadAndWhoHasItWithNamesAndTimes() async throws {
        let g = try await GroupOfThree.make()
        try g.ana.know("acc-bob", "Bob")
        g.ana.store.send(.text("hey"), in: g.chatID, replyTo: nil)
        await g.ana.store.flushOutbox()
        await g.bob.store.syncInbox()
        await g.cy.store.syncInbox()
        g.cy.store.markRead(chatID: g.chatID)
        await g.cy.store.settle()
        await g.ana.store.syncInbox()
        let id = try #require(g.newestOfAna()).id
        let lists = g.ana.store.receiptLists(messageID: id, in: g.chatID)
        #expect(lists.readBy.map(\.accountID) == ["acc-cy"])
        #expect(lists.deliveredTo.map(\.accountID) == ["acc-bob"])
        #expect(lists.deliveredTo.first?.name == "Bob")
        #expect(lists.readBy.first?.name == "Someone")
        // A one-to-one chat, or a message that is not mine, has no lists.
        #expect(g.ana.store.receiptLists(messageID: id, in: "acc-bob") == MessageReceiptLists())
    }
}
