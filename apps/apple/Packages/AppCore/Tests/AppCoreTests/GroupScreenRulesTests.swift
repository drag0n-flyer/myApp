import Foundation
import Testing
@testable import AppCore

private func member(_ id: String, _ name: String, _ role: GroupRole, me: Bool = false, contact: Bool = true) -> GroupMemberRow {
    GroupMemberRow(accountID: id, name: name, role: role, isMe: me, isContact: contact)
}

@Test func membersAreSortedOwnerThenAdminsThenMembersByName() {
    let list = [
        member("5", "zed", .member), member("1", "Bea", .member), member("2", "Ann", .admin),
        member("3", "owner", .owner), member("4", "Cy", .admin), member("6", "ann", .member),
    ]
    let sorted = GroupScreenRules.sorted(list).map(\.accountID)
    #expect(sorted == ["3", "2", "4", "6", "1", "5"])
}

@Test func equalNamesSortByAccountIDSoTheOrderIsStable() {
    let a = member("b", "Sam", .member), b = member("a", "Sam", .member)
    #expect(GroupScreenRules.sorted([a, b]).map(\.accountID) == ["a", "b"])
    #expect(GroupScreenRules.sorted([b, a]).map(\.accountID) == ["a", "b"])
}

@Test func whoMayAddEditAndSetTheTimer() {
    for state in [GroupMyState.removed, .left] {
        #expect(!GroupScreenRules.canAdd(myRole: .owner, myState: state))
        #expect(!GroupScreenRules.canEditInfo(myRole: .owner, myState: state))
        #expect(!GroupScreenRules.canSetTimer(myRole: .owner, myState: state))
        #expect(!GroupScreenRules.canLeave(myState: state))
        #expect(GroupScreenRules.canDeleteChat(myState: state))
    }
    #expect(GroupScreenRules.canAdd(myRole: .owner, myState: .member))
    #expect(GroupScreenRules.canAdd(myRole: .admin, myState: .member))
    #expect(!GroupScreenRules.canAdd(myRole: .member, myState: .member))
    #expect(!GroupScreenRules.canAdd(myRole: nil, myState: .member))
    #expect(GroupScreenRules.canEditInfo(myRole: .admin, myState: .member))
    #expect(!GroupScreenRules.canEditInfo(myRole: .member, myState: .member))
    #expect(GroupScreenRules.canSetTimer(myRole: .admin, myState: .member))
    #expect(!GroupScreenRules.canSetTimer(myRole: .member, myState: .member))
    #expect(GroupScreenRules.canLeave(myState: .member))
    #expect(!GroupScreenRules.canDeleteChat(myState: .member))
}

@Test func ownerMenuOnEachKindOfMember() {
    let target = { (role: GroupRole) in member("x", "X", role) }
    #expect(GroupScreenRules.actions(myRole: .owner, myState: .member, target: target(.member))
            == [.message, .viewSafetyNumber, .makeAdmin, .makeOwner, .remove])
    #expect(GroupScreenRules.actions(myRole: .owner, myState: .member, target: target(.admin))
            == [.message, .viewSafetyNumber, .removeAdmin, .makeOwner, .remove])
}

@Test func adminMenuRemovesOnlyPlainMembersAndSetsNoRoles() {
    let target = { (role: GroupRole) in member("x", "X", role) }
    #expect(GroupScreenRules.actions(myRole: .admin, myState: .member, target: target(.member)) == [.message, .viewSafetyNumber, .remove])
    #expect(GroupScreenRules.actions(myRole: .admin, myState: .member, target: target(.admin)) == [.message, .viewSafetyNumber])
    #expect(GroupScreenRules.actions(myRole: .admin, myState: .member, target: target(.owner)) == [.message, .viewSafetyNumber])
}

@Test func aPlainMemberOnlyMessagesAndComparesNumbers() {
    let target = member("x", "X", .member)
    #expect(GroupScreenRules.actions(myRole: .member, myState: .member, target: target) == [.message, .viewSafetyNumber])
}

@Test func noMenuOnOneselfOrAfterLeavingOrForAnUnknownRole() {
    let me = member("me", "Me", .owner, me: true)
    #expect(GroupScreenRules.actions(myRole: .owner, myState: .member, target: me).isEmpty)
    #expect(GroupScreenRules.actions(myRole: .owner, myState: .removed, target: member("x", "X", .member)).isEmpty)
    #expect(GroupScreenRules.actions(myRole: .owner, myState: .left, target: member("x", "X", .member)).isEmpty)
    #expect(GroupScreenRules.actions(myRole: nil, myState: .member, target: member("x", "X", .member)) == [.message, .viewSafetyNumber])
}

@Test func messageAndSafetyNumberNeedAContactPage() {
    let stranger = member("x", "X", .member, contact: false)
    #expect(GroupScreenRules.actions(myRole: .owner, myState: .member, target: stranger) == [.makeAdmin, .makeOwner, .remove])
}

@Test func theOwnerCannotBeRemovedOrMadeOwnerAgain() {
    let owner = member("o", "O", .owner)
    let actions = GroupScreenRules.actions(myRole: .owner, myState: .member, target: owner)
    #expect(!actions.contains(.remove) && !actions.contains(.makeOwner) && !actions.contains(.makeAdmin))
}

@Test func peopleToAddAreContactsNotAlreadyInTheGroup() {
    let contacts = ["a", "b", "c", "d"]
    let members = [member("b", "B", .member), member("me", "Me", .owner, me: true)]
    #expect(GroupScreenRules.addCandidates(contactIDs: contacts, members: members) == ["a", "c", "d"])
    #expect(GroupScreenRules.addCandidates(contactIDs: ["a", "a"], members: []) == ["a"])
}

@Test func theGroupHasRoomForTheLimit() {
    #expect(GroupScreenRules.room(memberCount: 98) == 2)
    #expect(GroupScreenRules.room(memberCount: 100) == 0)
    #expect(GroupScreenRules.room(memberCount: 120) == 0)
}

@Test func aGroupPhotoIsDrawnOnlyWhenTheProfilePhotoRuleAllowsIt() {
    #expect(GroupScreenRules.displayablePhoto(nil) == nil)
    #expect(GroupScreenRules.displayablePhoto(Data([1, 2, 3, 4, 5])) == nil)
    #expect(GroupScreenRules.displayablePhoto(SamplePhoto.jpeg) == SamplePhoto.jpeg)
    #expect(GroupScreenRules.displayablePhoto(Data(count: ProfilePhotoRule.maxBytes + 1)) == nil)
}

@Test func theNameTheFieldKeepsIsTheRuleOrNothing() {
    #expect(GroupScreenRules.editedName("  Club  ", current: "Old") == "Club")
    #expect(GroupScreenRules.editedName("Club", current: "Club") == nil)   // unchanged: nothing to send
    #expect(GroupScreenRules.editedName("   ", current: "Old") == nil)
    #expect(GroupScreenRules.editedName(String(repeating: "x", count: 51), current: "Old") == nil)
}

@Test func eachOutcomeHasPlainWords() {
    #expect(GroupChangeOutcome.done.message == nil)
    #expect(GroupChangeOutcome.failed(.ownerMustTransfer).message == "Choose a new owner before you leave.")
    #expect(GroupChangeOutcome.failed(.forbidden).message == "You can't do that in this group.")
    #expect(GroupChangeOutcome.failed(.full).message == "This group is full.")
    #expect(GroupChangeOutcome.failed(.offline).message != nil)
    #expect(GroupChangeOutcome.failed(.invalidName).message != nil)
    #expect(GroupChangeOutcome.failed(.invalidPhoto).message != nil)
    #expect(GroupChangeOutcome.failed(.other).message != nil)
}
