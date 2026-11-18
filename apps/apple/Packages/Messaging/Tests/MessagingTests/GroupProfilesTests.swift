import AppCore
import Foundation
import LocalStore
import Testing
@testable import Messaging
import MessagingTestKit

@Suite("GroupProfiles") @MainActor struct GroupProfilesTests {
    @Test func aMembersOwnProfileGoesToTheGroupAndNamesThemForMembersWhoDoNotKnowThem() async throws {
        let g = try await GroupOfThree.make()
        // Ana has no contact row for bob: he shows as "Someone" until his profile arrives.
        #expect(g.ana.store.memberName("acc-bob") == "Someone")
        g.bob.store.setMyDisplayName("Bobby")
        await g.bob.store.flushOutbox()
        await g.ana.store.syncInbox()
        await g.cy.store.syncInbox()
        #expect(g.ana.store.memberName("acc-bob") == "Bobby")
        #expect(g.cy.store.memberName("acc-bob") == "Bobby")
        #expect(g.ana.store.profile(for: "acc-bob")?.name == "Bobby")
        // Never a message, a chat with the member, or an unread count.
        #expect(g.ana.store.chats(in: .all).map(\.id) == [g.chatID])
        #expect(try g.ana.repo.messages(chatID: g.chatID, before: nil, limit: 20).filter { $0.senderAccount != "system" }.isEmpty)
        #expect(try g.ana.repo.chat(id: g.chatID)?.unread == 0)
        // Sent once for this version: nothing more is queued.
        let before = try g.bob.repo.dueOutbox(at: testEpochDate.addingTimeInterval(3600)).count
        g.bob.store.refresh()
        #expect(try g.bob.repo.dueOutbox(at: testEpochDate.addingTimeInterval(3600)).count == before)
    }

    @Test func aProfileFromABlockedMemberOrALowerVersionChangesNothing() async throws {
        let g = try await GroupOfThree.make()
        try g.ana.repo.block("acc-cy")
        g.cy.store.setMyDisplayName("Cyrus")
        await g.cy.store.flushOutbox()
        g.bob.store.setMyDisplayName("Bobby")
        await g.bob.store.flushOutbox()
        await g.ana.store.syncInbox()
        #expect(g.ana.store.memberName("acc-cy") == "Someone")
        #expect(g.ana.store.memberName("acc-bob") == "Bobby")
        #expect(g.ana.store.saveProfile(accountID: "acc-bob", name: "Old", photo: nil, ver: 1) == false)
        #expect(g.ana.store.memberName("acc-bob") == "Bobby")
    }

    @Test func aGroupMembersProfileNamesThemInTheGroupOnlyNotInAMessageRequest() async throws {
        let g = try await GroupOfThree.make()
        // Bob also wrote to ana one-to-one: that chat is a message request, shown by the lookup name only.
        try g.ana.repo.upsertContact(ContactRecord(accountID: "acc-bob", username: "bob", displayName: "bob", isContact: false, addedAt: testEpochDate))
        try g.ana.repo.upsertChat(ChatRecord(id: "acc-bob", isRequest: true, unread: 0, muted: false, lastActivity: testEpochDate))
        g.ana.store.refresh()
        #expect(g.ana.store.saveProfile(accountID: "acc-bob", name: "Bobby", photo: nil, ver: 1))
        #expect(g.ana.store.memberName("acc-bob") == "Bobby")
        #expect(g.ana.store.contact(id: "acc-bob")?.displayName != "Bobby")
        #expect(g.ana.store.messageRequests.first { $0.id == "acc-bob" }?.title != "Bobby")
    }
}
