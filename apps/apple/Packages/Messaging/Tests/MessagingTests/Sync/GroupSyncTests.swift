import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

private final class Hold: @unchecked Sendable { var processor: InboxProcessor? }

/// A device wired like the app: a rejected commit reads the inbox before it is rebuilt.
private struct Side {
    let device: TestDevice
    let manager: ConversationManager
    let processor: InboxProcessor
    let outbox: Outbox
    var repo: Repository { device.deps.repo }

    init(_ device: TestDevice) {
        self.device = device
        let hold = Hold()
        let deps = device.deps
        manager = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) }, syncOnce: { _ = try await hold.processor?.syncOnce() })
        outbox = Outbox(deps: deps, conversations: manager, readReceiptsEnabled: { true })
        processor = InboxProcessor(deps: deps, conversations: manager, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }), controls: nil)
        hold.processor = processor
    }

    func sync() async throws { _ = try await processor.syncOnce() }

    func lines(_ chatID: String) throws -> [GroupSystemLine] {
        try repo.messages(chatID: chatID, before: nil, limit: 100).compactMap {
            guard case .group(let line)? = try? JSONDecoder().decode(SystemEvent.self, from: $0.envelope) else { return nil }
            return line
        }
    }

    func mlsAccounts(_ chatID: String) async throws -> [String] {
        let record = try #require(try repo.conversations(chatID: chatID).first)
        return Array(Set(try await device.deps.mls.members(of: GroupID(raw: record.groupID)).map(GroupSyncPlan.account(of:)))).sorted()
    }
}

/// A valid group photo (a frame header of 1 x 1).
private let photo = Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x08, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 7, 7])

/// Ana makes "Book club" with bob and cy; both first devices have joined.
private func started(without: Set<String> = []) async throws -> (GroupRig, Side, Side, Side, String) {
    let rig = try await GroupRig.make(without: without)
    let ana = Side(rig.ana), bob = Side(rig.bob[0]), cy = Side(rig.cy[0])
    let made = try await ana.manager.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Book club", photo: nil)
    try await ana.outbox.flush()
    try await bob.sync()
    try await cy.sync()
    return (rig, ana, bob, cy, made.chatID)
}

@Suite("GroupSync") struct GroupSyncTests {
    @Test func theSyncPlanTakesOutGoneAccountsAndAddsListedOnesWithoutADevice() {
        let plan = GroupSyncPlan.plan(listed: ["me", "a", "b"], mlsIdentities: ["me/1", "a/1", "a/2", "c/1", "c/2"], me: "me")
        #expect(plan == GroupSyncPlan.Plan(removeIdentities: ["c/1", "c/2"], addAccounts: ["b"]))
        #expect(GroupSyncPlan.plan(listed: ["me", "a"], mlsIdentities: ["me/1", "a/1"], me: "me") == GroupSyncPlan.Plan(removeIdentities: [], addAccounts: []))
    }

    @Test func aRemovalTakesTheDevicesOutOfTheGroupAndEveryoneWritesOneLine() async throws {
        let (_, ana, bob, cy, chatID) = try await started()
        try await ana.manager.removeGroupMember(chatID: chatID, account: "acc-cy")
        // Ana: the server's list, the line naming her, and no device of cy's left in the group.
        #expect(try ana.repo.groupMembers(chatID: chatID).map(\.accountID) == ["acc-ana", "acc-bob"])
        #expect(try ana.lines(chatID) == [.removed(by: "acc-ana", who: "acc-cy")])
        #expect(try await ana.mlsAccounts(chatID) == ["acc-ana", "acc-bob"])
        // Bob learns from the item: same list, a line naming ana (the version is the next one), and the commit it read.
        try await bob.sync()
        await bob.manager.groupMembersChanged(conversationID: ChatID.conversationID(chatID)!, actor: "acc-ana")
        #expect(try bob.lines(chatID) == [.removed(by: "acc-ana", who: "acc-cy")])
        #expect(try await bob.mlsAccounts(chatID) == ["acc-ana", "acc-bob"])
        // A second item for the same version adds nothing.
        await bob.manager.groupMembersChanged(conversationID: ChatID.conversationID(chatID)!, actor: "acc-ana")
        #expect(try bob.lines(chatID).count == 1)
        // Cy is out: read-only, "You were removed", no conversation row, and a later message is dropped before it is read.
        try await cy.sync()
        await cy.manager.groupMembersChanged(conversationID: ChatID.conversationID(chatID)!, actor: "acc-ana")
        let chat = try #require(try cy.repo.chat(id: chatID))
        #expect(chat.myState == "removed")
        #expect(try cy.repo.conversations(chatID: chatID).isEmpty)
        #expect(try cy.lines(chatID).filter { $0 == .youWereRemoved }.count == 1)
    }

    @Test func aLateMessageForAGroupIAmOutOfIsDroppedUnread() async throws {
        let (rig, ana, _, cy, chatID) = try await started()
        // Ana sends something while cy is still in; cy has not read it yet.
        let record = try #require(try ana.repo.preferredConversation(chatID: chatID))
        try await ana.outbox.send(ContentEnvelope(id: "late-1", at: 1_700_000_000_000, body: .text("before", replyTo: nil)), in: record)
        await cy.manager.groupMembersChanged(conversationID: ChatID.conversationID(chatID)!, actor: nil)
        try await ana.manager.removeGroupMember(chatID: chatID, account: "acc-cy")
        await cy.manager.groupMembersChanged(conversationID: ChatID.conversationID(chatID)!, actor: nil)
        #expect(try cy.repo.chat(id: chatID)?.myState == "removed")
        let before = try cy.repo.messages(chatID: chatID, before: nil, limit: 50).count
        try await cy.sync()
        #expect(try cy.repo.messages(chatID: chatID, before: nil, limit: 50).count == before)
        #expect(try cy.repo.rawItems(limit: 10).isEmpty)
        _ = rig
    }

    @Test func missedItemsGiveOneFetchAndLinesWithoutAnActor() async throws {
        let (rig, ana, bob, _, chatID) = try await started()
        let conversationID = ChatID.conversationID(chatID)!
        // Three changes while bob is not looking: version 0 -> 3.
        let dan = try #require(rig.dan.first)
        #expect(try await ana.manager.addGroupMembers(chatID: chatID, accounts: [dan.accountID]).isEmpty)
        try await ana.manager.setGroupRole(chatID: chatID, account: "acc-cy", role: .admin)
        try await ana.manager.removeGroupMember(chatID: chatID, account: "acc-cy")
        let before = await rig.server.requests().filter { $0 == "GET /v1/conversations/\(conversationID)" }.count
        await bob.manager.groupMembersChanged(conversationID: conversationID, actor: "acc-ana")
        let after = await rig.server.requests().filter { $0 == "GET /v1/conversations/\(conversationID)" }.count
        #expect(after - before == 1)
        // Dan was added and cy is gone (the admin step is between two states bob never saw): lines without a name for the actor.
        #expect(try bob.lines(chatID) == [.removed(by: nil, who: "acc-cy"), .added(by: nil, who: "acc-dan")] ||
                bob.lines(chatID) == [.added(by: nil, who: "acc-dan"), .removed(by: nil, who: "acc-cy")])
        #expect(try bob.repo.groupMembers(chatID: chatID).map(\.accountID) == ["acc-ana", "acc-bob", "acc-dan"])
    }

    @Test func twoMembersRacingToTakeOutTheSameDevicesLandOneCommitAndNoSecondLine() async throws {
        let (rig, ana, bob, _, chatID) = try await started()
        let conversationID = ChatID.conversationID(chatID)!
        // The server removes cy on ana's call alone (no MLS step yet), then ana and bob both react.
        _ = try await ana.device.deps.server.removeAccount(conversationID: conversationID, account: "acc-cy")
        let epochBefore = try #require(await rig.server.epoch(of: conversationID))
        try await ana.manager.reconcileGroup(conversationID: conversationID, actor: "acc-ana")
        try await bob.manager.reconcileGroup(conversationID: conversationID, actor: "acc-ana")
        // One commit in all: the loser found nothing left to do after reading the inbox.
        #expect(await rig.server.epoch(of: conversationID) == epochBefore + 1)
        #expect(try await bob.mlsAccounts(chatID) == ["acc-ana", "acc-bob"])
        #expect(try bob.lines(chatID).count == 1)
    }

    @Test func addingPeopleMakesThemMembersAtOnceAndCommitsTheirDevicesWithWelcomes() async throws {
        let (rig, ana, bob, _, chatID) = try await started()
        let notAdded = try await ana.manager.addGroupMembers(chatID: chatID, accounts: ["acc-dan"])
        #expect(notAdded.isEmpty)
        #expect(try await ana.mlsAccounts(chatID) == ["acc-ana", "acc-bob", "acc-cy", "acc-dan"])
        #expect(try ana.lines(chatID) == [.added(by: "acc-ana", who: "acc-dan")])
        try await ana.outbox.flush()
        // Dan's device joins by Welcome and then learns the name; the line says he was added.
        let dan = Side(rig.dan[0])
        try await dan.sync()
        let chat = try #require(try dan.repo.chat(id: chatID))
        #expect(chat.myState == "member" && chat.title == "Book club")
        #expect(try dan.repo.groupMembers(chatID: chatID).count == 4)
        // Bob reads the commit and the item: his copy and MLS follow.
        try await bob.sync()
        await bob.manager.groupMembersChanged(conversationID: ChatID.conversationID(chatID)!, actor: "acc-ana")
        #expect(try await bob.mlsAccounts(chatID).contains("acc-dan"))
        #expect(try bob.lines(chatID) == [.added(by: "acc-ana", who: "acc-dan")])
    }

    @Test func aPlainMemberCannotAddAndTheRoleRulesAreChecked() async throws {
        let (_, ana, bob, _, chatID) = try await started()
        await #expect(throws: GroupAdminError.forbidden) { try await bob.manager.addGroupMembers(chatID: chatID, accounts: ["acc-dan"]) }
        await #expect(throws: GroupAdminError.forbidden) { try await bob.manager.removeGroupMember(chatID: chatID, account: "acc-cy") }
        await #expect(throws: GroupAdminError.forbidden) { try await bob.manager.setGroupRole(chatID: chatID, account: "acc-cy", role: .admin) }
        await #expect(throws: GroupAdminError.forbidden) { try await bob.manager.changeGroupInfo(chatID: chatID, name: "Mine", photo: nil, setPhoto: false) }
        // The owner may not leave before handing over.
        await #expect(throws: GroupAdminError.ownerMustTransfer) { try await ana.manager.leaveGroup(chatID: chatID) }
        // A person who is not in the list.
        await #expect(throws: GroupAdminError.unknownMember) { try await ana.manager.setGroupRole(chatID: chatID, account: "acc-zed", role: .admin) }
    }

    @Test func aPersonWithoutKeyPackagesIsListedAndTheNextOwnerOrAdminThatSeesThemMissingAddsThem() async throws {
        let (rig, ana, bob, _, chatID) = try await started(without: ["acc-dan"])
        let conversationID = ChatID.conversationID(chatID)!
        let notAdded = try await ana.manager.addGroupMembers(chatID: chatID, accounts: ["acc-dan"])
        #expect(notAdded == ["acc-dan"])
        #expect(try ana.repo.groupMembers(chatID: chatID).map(\.accountID).contains("acc-dan"))
        #expect(try await ana.mlsAccounts(chatID) == ["acc-ana", "acc-bob", "acc-cy"])
        // Dan's devices get packages. Bob is a plain member: he sees dan missing but claims nothing and adds nothing.
        for device in rig.dan { _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 2)) }
        try await bob.sync()
        await bob.manager.groupMembersChanged(conversationID: conversationID, actor: nil)
        let waiting = try await bob.manager.reconcileGroup(conversationID: conversationID, actor: nil)
        #expect(waiting == ["acc-dan"])
        #expect(try await bob.mlsAccounts(chatID) == ["acc-ana", "acc-bob", "acc-cy"])
        // Once bob is an admin, he does it.
        try await ana.manager.setGroupRole(chatID: chatID, account: "acc-bob", role: .admin)
        let unreached = try await bob.manager.reconcileGroup(conversationID: conversationID, actor: "acc-ana")
        #expect(unreached.isEmpty)
        #expect(try await bob.mlsAccounts(chatID).contains("acc-dan"))
    }

    @Test func aPlainMemberStillCommitsTheRemovalOfDepartedDevices() async throws {
        let (_, ana, bob, _, chatID) = try await started()
        try await ana.manager.removeGroupMember(chatID: chatID, account: "acc-cy")
        try await bob.sync()
        await bob.manager.groupMembersChanged(conversationID: ChatID.conversationID(chatID)!, actor: "acc-ana")
        #expect(try await bob.mlsAccounts(chatID) == ["acc-ana", "acc-bob"])
    }

    @Test func twoAdminsRenamingAtOnceEndOnTheSameGroupWhicheverArrivesFirst() async throws {
        let (_, ana, bob, cy, chatID) = try await started()
        let conversationID = ChatID.conversationID(chatID)!
        try await ana.manager.setGroupRole(chatID: chatID, account: "acc-bob", role: .admin)
        try await ana.outbox.flush()
        await bob.manager.groupMembersChanged(conversationID: conversationID, actor: "acc-ana")
        await cy.manager.groupMembersChanged(conversationID: conversationID, actor: "acc-ana")
        try await ana.manager.changeGroupInfo(chatID: chatID, name: "Alpha", photo: nil, setPhoto: false)
        try await bob.manager.changeGroupInfo(chatID: chatID, name: "Zulu", photo: nil, setPhoto: false)
        try await ana.outbox.flush()
        try await bob.outbox.flush()
        try await ana.sync()
        try await bob.sync()
        try await cy.sync()
        for side in [ana, bob, cy] {
            let chat = try #require(try side.repo.chat(id: chatID))
            #expect(chat.title == "Zulu", "\(side.device.deps.me.accountID) has \(chat.title ?? "nil")")
            #expect(chat.groupVer == 2)
        }
        // The winner's line is not lost behind the loser's on the device that had the loser first.
        #expect(try ana.lines(chatID).contains(.nameChanged(by: "acc-bob", name: "Zulu")))
    }

    @Test func leavingMakesTheChatReadOnlyAndLaterItemsAreNotRead() async throws {
        let (_, ana, bob, _, chatID) = try await started()
        try await bob.manager.leaveGroup(chatID: chatID)
        let chat = try #require(try bob.repo.chat(id: chatID))
        #expect(chat.myState == "left")
        #expect(try bob.repo.conversations(chatID: chatID).isEmpty)
        #expect(try bob.lines(chatID).isEmpty)
        // Ana learns, removes the devices from MLS, and writes "bob left" (the actor is bob).
        await ana.manager.groupMembersChanged(conversationID: ChatID.conversationID(chatID)!, actor: "acc-bob")
        #expect(try ana.lines(chatID) == [.left(who: "acc-bob")])
        #expect(try await ana.mlsAccounts(chatID) == ["acc-ana", "acc-cy"])
        // Nothing can be changed from a group I left.
        await #expect(throws: GroupAdminError.notGroup) { try await bob.manager.changeGroupInfo(chatID: chatID, name: "X", photo: nil, setPhoto: false) }
    }

    @Test func handingOverOwnershipLetsTheOldOwnerLeave() async throws {
        let (_, ana, bob, _, chatID) = try await started()
        try await ana.manager.setGroupRole(chatID: chatID, account: "acc-bob", role: .owner)
        #expect(try ana.repo.groupMembers(chatID: chatID).first { $0.accountID == "acc-ana" }?.role == "admin")
        try await ana.manager.leaveGroup(chatID: chatID)
        #expect(try ana.repo.chat(id: chatID)?.myState == "left")
        await bob.manager.groupMembersChanged(conversationID: ChatID.conversationID(chatID)!, actor: "acc-ana")
        #expect(try bob.repo.groupMembers(chatID: chatID).map(\.accountID) == ["acc-bob", "acc-cy"])
    }

    @Test func renamingSendsGroupWithTheNextVersionAndReceiversWriteOneLine() async throws {
        let (_, ana, bob, _, chatID) = try await started()
        try await ana.manager.changeGroupInfo(chatID: chatID, name: "Reading club", photo: photo, setPhoto: true)
        #expect(try ana.repo.chat(id: chatID).map { ($0.title, $0.groupVer, $0.photo) }! == ("Reading club", 2, photo))
        #expect(try ana.lines(chatID) == [.nameChanged(by: "acc-ana", name: "Reading club"), .photoChanged(by: "acc-ana")])
        // Same name and photo again: nothing changes, nothing is queued.
        try await ana.manager.changeGroupInfo(chatID: chatID, name: "Reading club", photo: nil, setPhoto: false)
        #expect(try ana.repo.chat(id: chatID)?.groupVer == 2)
        try await ana.outbox.flush()
        try await bob.sync()
        let chat = try #require(try bob.repo.chat(id: chatID))
        #expect(chat.title == "Reading club" && chat.groupVer == 2)
        #expect(try bob.lines(chatID) == [.nameChanged(by: "acc-ana", name: "Reading club"), .photoChanged(by: "acc-ana")])
        // A name that fails the rule or a photo that is not a real JPEG changes nothing.
        await #expect(throws: GroupAdminError.invalidName) { try await ana.manager.changeGroupInfo(chatID: chatID, name: "  ", photo: nil, setPhoto: false) }
        await #expect(throws: GroupAdminError.invalidPhoto) {
            try await ana.manager.changeGroupInfo(chatID: chatID, name: nil, photo: Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 2]), setPhoto: true)
        }
        #expect(try ana.repo.chat(id: chatID)?.groupVer == 2)
    }

    @Test func aMembersItemForAnUnknownConversationIsIgnoredWithoutAnyRequest() async throws {
        let rig = try await GroupRig.make()
        let bob = Side(rig.bob[0])
        await bob.manager.groupMembersChanged(conversationID: "conv-nope", actor: "acc-ana")
        await bob.manager.reconcileGroups()
        #expect(await rig.server.requests().filter { $0.contains("conv-nope") }.isEmpty)
        #expect(try bob.repo.chats().isEmpty)
    }

    @Test func theInboxReportsMembersItemsAfterTheDrainWithTheirSender() async throws {
        let (_, ana, bob, _, chatID) = try await started()
        actor Seen { var list: [String] = []; func add(_ s: String) { list.append(s) } }
        let seen = Seen()
        await bob.processor.setOnMembers { id, actor in await seen.add("\(id)|\(actor ?? "-")") }
        try await ana.manager.setGroupRole(chatID: chatID, account: "acc-cy", role: .admin)
        try await ana.manager.removeGroupMember(chatID: chatID, account: "acc-cy")
        try await bob.sync()
        // Two items for one conversation: one report.
        #expect(await seen.list == ["\(ChatID.conversationID(chatID)!)|acc-ana"])
        #expect(try bob.repo.rawItems(limit: 10).isEmpty)
    }
}
