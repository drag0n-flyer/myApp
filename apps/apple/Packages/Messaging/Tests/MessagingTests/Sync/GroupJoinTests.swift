import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

/// A device with the inbox pipeline wired the way the app does it.
struct GroupSide {
    let device: TestDevice
    let manager: ConversationManager
    let processor: InboxProcessor
    let outbox: Outbox

    init(_ device: TestDevice) {
        self.device = device
        let deps = device.deps
        manager = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        outbox = Outbox(deps: deps, conversations: manager, readReceiptsEnabled: { true })
        processor = InboxProcessor(
            deps: deps, conversations: manager, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }), controls: nil)
    }

    var repo: Repository { device.deps.repo }
    func sync() async throws { _ = try await processor.syncOnce() }

    /// This device posts `body` into the group, as a member would.
    func post(_ body: ContentEnvelope.Body, in chatID: String, at: Int64 = 1_700_000_000_000) async throws {
        let record = try #require(try repo.preferredConversation(chatID: chatID))
        try await outbox.send(ContentEnvelope(id: UUID().uuidString.lowercased(), at: at, body: body), in: record)
    }
}

private let jpeg4 = Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00]) // a 16x16 frame header: passes the profile photo rule

@Suite("GroupJoin") struct GroupJoinTests {
    /// Ana creates "Book club" with bob and cy and sends everything queued.
    private func started() async throws -> (GroupRig, GroupSide, GroupCreation) {
        let rig = try await GroupRig.make()
        let ana = GroupSide(rig.ana)
        let made = try await ana.manager.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Book club", photo: jpeg4)
        try await ana.outbox.flush()
        return (rig, ana, made)
    }

    @Test func aWelcomeForAGroupMakesTheGroupChatWithItsMembersAndOneAddedYouLine() async throws {
        let (rig, _, made) = try await started()
        let bob = GroupSide(rig.bob[0])
        try await bob.sync()
        let chat = try #require(try bob.repo.chat(id: made.chatID))
        #expect(chat.kind == "group" && chat.conversationID == made.conversationID && chat.myState == "member")
        #expect(chat.isRequest == false)
        #expect(try bob.repo.groupMembers(chatID: made.chatID).map { "\($0.accountID):\($0.role)" } ==
                ["acc-ana:owner", "acc-bob:member", "acc-cy:member"])
        #expect(chat.membersVersion == 0)
        let conversation = try #require(try bob.repo.conversation(serverID: made.conversationID))
        #expect(conversation.chatID == made.chatID && conversation.active)
        // No chat with the adder as a person, no safety number, one line "added you" naming the adder.
        #expect(try bob.repo.chat(id: "acc-ana") == nil)
        #expect(try bob.repo.safety(chatID: made.chatID) == nil)
        let lines = try bob.repo.messages(chatID: made.chatID, before: nil, limit: 20)
        #expect(lines.count == 1)
        #expect(try JSONDecoder().decode(SystemEvent.self, from: lines[0].envelope) == .addedYou(by: "acc-ana"))
        // A second sync changes nothing.
        try await bob.sync()
        #expect(try bob.repo.messages(chatID: made.chatID, before: nil, limit: 20).count == 1)
    }

    @Test func theGroupEnvelopeFromTheOwnerSetsNameAndPhotoAndTheModeIsPinned() async throws {
        let (rig, _, made) = try await started()
        let bob = GroupSide(rig.bob[0])
        try await bob.sync()
        let chat = try #require(try bob.repo.chat(id: made.chatID))
        #expect(chat.title == "Book club" && chat.photo == jpeg4 && chat.groupVer == 1)
        #expect(try bob.repo.conversation(serverID: made.conversationID)?.innerMode == "e2ee")
        // The group envelope is never a message row.
        #expect(try bob.repo.messages(chatID: made.chatID, before: nil, limit: 20).count == 1)
    }

    @Test func aGroupEnvelopeFromAPlainMemberOrWithAStaleVersionOrASmallPhotoChangesNothing() async throws {
        let (rig, ana, made) = try await started()
        let bob = GroupSide(rig.bob[0]), cy = GroupSide(rig.cy[0])
        try await bob.sync()
        try await cy.sync()
        // cy is a plain member: refused.
        try await cy.post(.group(name: "Cy's club", photo: nil, ver: 5), in: made.chatID)
        // The owner with the same version and a lesser name (a greater one would win the tie), then with a photo under 4 bytes: refused.
        try await ana.post(.group(name: "Aardvark", photo: nil, ver: 1), in: made.chatID)
        try await ana.post(.group(name: "Tiny photo", photo: Data([0xFF, 0xD8, 1]), ver: 2), in: made.chatID)
        try await bob.sync()
        var chat = try #require(try bob.repo.chat(id: made.chatID))
        #expect(chat.title == "Book club" && chat.groupVer == 1 && chat.photo == jpeg4)
        // The owner with a higher version wins; a photo-less one clears the photo.
        try await ana.post(.group(name: "Reading club", photo: nil, ver: 2), in: made.chatID)
        try await bob.sync()
        chat = try #require(try bob.repo.chat(id: made.chatID))
        #expect(chat.title == "Reading club" && chat.groupVer == 2 && chat.photo == nil)
    }

    @Test func aWelcomeWhenTheServerListLeavesMeOutIsDroppedWithoutAChatOrAPlaceholder() async throws {
        let (rig, _, made) = try await started()
        await rig.server.hideAccountInList("acc-bob", of: made.conversationID)
        let bob = GroupSide(rig.bob[0])
        try await bob.sync()
        #expect(try bob.repo.chats().isEmpty)
        #expect(try bob.repo.conversation(serverID: made.conversationID) == nil)
        #expect(try bob.repo.rawItems(limit: 10).isEmpty)
        // The Welcome was parked, not lost (the later items of the unknown group are parked too).
        let parked = try bob.repo.entries(prefix: ParkedItem.prefix).values.compactMap { try? JSONDecoder().decode(ParkedItem.self, from: $0) }
        #expect(parked.filter { $0.kind == "welcome" }.map(\.reason) == ["group_not_member"])
    }

    @Test func aWelcomeForAGroupThatIsGoneIsLeftToTheOneToOneRule() async throws {
        let (rig, _, made) = try await started()
        await rig.server.removeConversation(made.conversationID)
        let bob = GroupSide(rig.bob[0])
        try await bob.sync()
        #expect(try bob.repo.chat(id: made.chatID) == nil)
        #expect(try bob.repo.conversation(serverID: made.conversationID) == nil)
        // Parked only: no one-to-one chat with the adder, and no placeholder.
        #expect(try bob.repo.chat(id: "acc-ana") == nil)
    }

    @Test func aRejoinAfterBeingRemovedSetsMyStateBackToMember() async throws {
        let (rig, _, made) = try await started()
        let bob = GroupSide(rig.bob[0])
        try await bob.sync()
        try bob.repo.setMyState(chatID: made.chatID, "removed")
        // A fresh Welcome for the same conversation on another device of bob's account is not needed: store the plan again.
        let record = try #require(try bob.repo.conversation(serverID: made.conversationID))
        let join = ConversationManager.GroupJoin(
            conversationID: made.conversationID, members: [GroupMemberRecord(accountID: "acc-bob", role: "member")], version: 5,
            addedBy: "acc-ana", at: testEpochDate)
        try bob.repo.transaction {
            try join.store(chat: try #require(try bob.repo.chat(id: made.chatID)), conversation: record, in: bob.repo)
        }
        #expect(try bob.repo.chat(id: made.chatID)?.myState == "member")
    }

    @Test func theAdderIsNamedOnlyWhenTheServerListHasThem() async throws {
        let (rig, _, made) = try await started()
        await rig.server.dropGroupAccount("acc-ana", from: made.conversationID)
        let bob = GroupSide(rig.bob[0])
        try await bob.sync()
        let lines = try bob.repo.messages(chatID: made.chatID, before: nil, limit: 20)
        #expect(try JSONDecoder().decode(SystemEvent.self, from: lines[0].envelope) == .addedYou(by: nil))
    }

    @Test func myOtherDeviceJoinsTooAndNoLineIsMadeForMyOwnAdd() async throws {
        let rig = try await GroupRig.make()
        let ana2 = await makeDevice(rig.server, account: "acc-ana", username: "ana")
        _ = try await ana2.deps.server.uploadKeyPackages(try await ana2.deps.mls.makeKeyPackages(count: 2))
        let ana = GroupSide(rig.ana)
        let made = try await ana.manager.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Club", photo: nil)
        let other = GroupSide(ana2)
        try await other.sync()
        #expect(try other.repo.chat(id: made.chatID)?.kind == "group")
        #expect(try other.repo.messages(chatID: made.chatID, before: nil, limit: 10).isEmpty)
    }

    @Test func aMembersNewDeviceIsAddedByThatMemberOnly() async throws {
        let (rig, ana, made) = try await started()
        let bob = GroupSide(rig.bob[0])
        try await bob.sync()
        let bob3 = await makeDevice(rig.server, account: "acc-bob", username: "bob")
        _ = try await bob3.deps.server.uploadKeyPackages(try await bob3.deps.mls.makeKeyPackages(count: 2))
        let before = await rig.server.memberDevices(of: made.conversationID)
        // Ana's own reconcile sees only her own devices: nothing is added for bob.
        try await DeviceSync(deps: rig.ana.deps, conversations: ana.manager, wipeMLS: {}).reconcileOwnDevices()
        #expect(await rig.server.memberDevices(of: made.conversationID) == before)
        // Bob's device adds it.
        try await DeviceSync(deps: rig.bob[0].deps, conversations: bob.manager, wipeMLS: {}).reconcileOwnDevices()
        let after = await rig.server.memberDevices(of: made.conversationID)
        #expect(after.count == before.count + 1 && after.contains(bob3.deviceID))
        // The new device joins the group chat from its welcome.
        let joined = GroupSide(bob3)
        try await joined.sync()
        #expect(try joined.repo.chat(id: made.chatID)?.kind == "group")
    }

    @Test func theJoinRefusalRuleIsPure() {
        func info(kind: String = "group", id: String = "conv-1", members: [GroupMemberInfo], version: Int64? = 0) -> NewConversation {
            NewConversation(conversationID: id, mode: "e2ee", epoch: 1, kind: kind, members: members, membersVersion: version)
        }
        let me = GroupMemberInfo(account: "acc-me", role: .member)
        let owner = GroupMemberInfo(account: "acc-o", role: .owner)
        #expect(ConversationManager.groupJoinRefusal(info(members: [owner, me]), me: "acc-me") == nil)
        #expect(ConversationManager.groupJoinRefusal(info(members: [owner]), me: "acc-me") == "group_not_member")
        #expect(ConversationManager.groupJoinRefusal(info(members: [owner, me], version: nil), me: "acc-me") == "group_bad_list")
        #expect(ConversationManager.groupJoinRefusal(info(members: [owner, me], version: -1), me: "acc-me") == "group_bad_list")
        #expect(ConversationManager.groupJoinRefusal(info(id: "bad id/..", members: [owner, me]), me: "acc-me") == "group_bad_list")
        let many = (0..<101).map { GroupMemberInfo(account: "acc-\($0)", role: .member) }
        #expect(ConversationManager.groupJoinRefusal(info(members: many + [me]), me: "acc-me") == "group_bad_list")
        // A list that names an account twice is not a list.
        #expect(ConversationManager.groupJoinRefusal(info(members: [owner, me, me]), me: "acc-me") == "group_bad_list")
    }
}
