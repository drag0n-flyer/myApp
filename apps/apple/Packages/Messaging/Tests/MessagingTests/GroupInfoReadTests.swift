import AppCore
import Foundation
import LocalStore
import Testing
@testable import Messaging
import MessagingTestKit

@Suite("GroupInfoRead") @MainActor struct GroupInfoReadTests {
    private func make() async throws -> (LiveChatStore, Repository) {
        let rig = try await GroupRig.make()
        let deps = rig.ana.deps
        let conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        let outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }), controls: outbox)
        return (LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil), deps.repo)
    }

    @Test func aStoredGroupIsReadWithMembersRolesAndMyPlace() async throws {
        let (store, repo) = try await make()
        let me = store.me.id
        let id = try #require(try repo.upsertGroup(conversationID: "c1", title: "Book club", photo: groupJpeg, at: Date()))
        try repo.upsertContact(ContactRecord(accountID: "acc-bob", username: "bob", displayName: "Bob", isContact: true, addedAt: Date()))
        _ = try repo.setGroupMembers(chatID: id, [GroupMemberRecord(accountID: me, role: "owner"), GroupMemberRecord(accountID: "acc-bob", role: "admin"),
                                                  GroupMemberRecord(accountID: "acc-zed", role: "member")], version: 1)
        store.refresh()
        let info = try #require(store.groupInfo(chatID: id))
        #expect(info.name == "Book club" && info.myRole == .owner && info.myState == .member)
        #expect(info.photo == groupJpeg)
        #expect(info.members.count == 3)
        let bob = try #require(info.members.first { $0.accountID == "acc-bob" })
        #expect(bob.name == "Bob" && bob.role == .admin && bob.isContact && !bob.isMe)
        let zed = try #require(info.members.first { $0.accountID == "acc-zed" })
        #expect(zed.role == .member && !zed.isContact && zed.name.isEmpty)
        #expect(info.members.first { $0.accountID == me }?.isMe == true)
    }

    @Test func aPhotoThatBreaksTheProfileRuleIsNeverHandedOut() async throws {
        let (store, repo) = try await make()
        let id = try #require(try repo.upsertGroup(conversationID: "c2", title: "Club", photo: Data([1, 2, 3, 4, 5, 6]), at: Date()))
        store.refresh()
        #expect(store.groupInfo(chatID: id)?.photo == nil)
    }

    @Test func aRemovedOrLeftGroupSaysSoAndAnUnknownOrDirectChatHasNoInfo() async throws {
        let (store, repo) = try await make()
        let id = try #require(try repo.upsertGroup(conversationID: "c3", title: "Club", photo: nil, at: Date()))
        _ = try repo.setGroupMembers(chatID: id, [GroupMemberRecord(accountID: "acc-bob", role: "owner")], version: 1)
        _ = try repo.setMyState(chatID: id, "removed")
        store.refresh()
        let info = try #require(store.groupInfo(chatID: id))
        #expect(info.myState == .removed && info.myRole == nil)
        #expect(store.groupInfo(chatID: "g:nothing") == nil)
        #expect(store.groupInfo(chatID: "acc-bob") == nil)
    }
}

private let groupJpeg = Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x01, 0x00, 0x01, 0x00, 0x01, 0x01, 0x11, 0x00]) + Data(count: 100)
