import Testing
@testable import AppCore

@Test func groupChatIDsAreTheConversationIDWithAPrefix() {
    #expect(ChatID.group("c1") == "g:c1")
    #expect(ChatID.isGroup("g:c1"))
    #expect(ChatID.conversationID("g:c1") == "c1")
    #expect(ChatID.conversationID(ChatID.group("a:b")) == "a:b")
}

@Test func aOneToOneChatIDIsNeverAGroup() {
    for id in ["bob", "", "g", "g:", "G:c1", " g:c1", "chat-g:c1", "gg:c1"] {
        #expect(!ChatID.isGroup(id), "\(id)")
        #expect(ChatID.conversationID(id) == nil, "\(id)")
    }
}

@Test func everyRolePairForRemoval() {
    let table: [(GroupRole, GroupRole, Bool)] = [
        (.owner, .owner, false), (.owner, .admin, true), (.owner, .member, true),
        (.admin, .owner, false), (.admin, .admin, false), (.admin, .member, true),
        (.member, .owner, false), (.member, .admin, false), (.member, .member, false),
    ]
    for (actor, target, want) in table {
        #expect(actor.canRemove(target: target) == want, "\(actor) removing \(target)")
    }
}

@Test func rolePermissions() {
    #expect(GroupRole.owner.canAdd && GroupRole.admin.canAdd && !GroupRole.member.canAdd)
    #expect(GroupRole.owner.canSetRoles && !GroupRole.admin.canSetRoles && !GroupRole.member.canSetRoles)
    #expect(GroupRole.owner.canEditInfo && GroupRole.admin.canEditInfo && !GroupRole.member.canEditInfo)
    #expect(GroupRole.owner.canSetTimer && GroupRole.admin.canSetTimer && !GroupRole.member.canSetTimer)
}

@Test func aRoleFromTheServerThatIsNotKnownIsAPlainMember() {
    #expect(GroupRole(wire: "owner") == .owner)
    #expect(GroupRole(wire: "admin") == .admin)
    #expect(GroupRole(wire: "member") == .member)
    #expect(GroupRole(wire: "superuser") == .member)
    #expect(GroupRole(wire: "") == .member)
    #expect(GroupRole(wire: "OWNER") == .member)
}

private func m(_ id: String, _ role: GroupRole = .member) -> GroupMember { GroupMember(accountID: id, role: role) }

@Test func anEmptyDiffHasNoLines() {
    let list = [m("a", .owner), m("b")]
    #expect(MemberDiff.lines(old: list, new: list, actor: "a", me: "a").isEmpty)
    #expect(MemberDiff.lines(old: [], new: [], actor: nil, me: "a").isEmpty)
    // The same people in another order is no change.
    #expect(MemberDiff.lines(old: list, new: list.reversed(), actor: nil, me: "a").isEmpty)
}

@Test func anAddAnAddRemoveAndARoleChangeInOneStepComeOutByAccountID() {
    let old = [m("a", .owner), m("b"), m("c"), m("d", .admin)]
    let new = [m("a", .owner), m("b", .admin), m("d"), m("e"), m("f")]   // b admin, c removed, d demoted, e and f added
    let lines = MemberDiff.lines(old: old, new: new, actor: "a", me: "z")
    #expect(lines == [
        .madeAdmin(who: "b"), .removed(by: "a", who: "c"), .noLongerAdmin(who: "d"),
        .added(by: "a", who: "e"), .added(by: "a", who: "f"),
    ])
}

@Test func theLinesAreTheSameWhateverTheInputOrder() {
    let old = [m("c"), m("a", .owner), m("b")]
    let new = [m("f"), m("a", .owner), m("e"), m("c")]
    let one = MemberDiff.lines(old: old, new: new, actor: "a", me: "z")
    let two = MemberDiff.lines(old: old.reversed(), new: new.reversed(), actor: "a", me: "z")
    #expect(one == two)
    #expect(one == [.removed(by: "a", who: "b"), .added(by: "a", who: "e"), .added(by: "a", who: "f")])
}

@Test func meBeingRemovedIsOneLineWithoutNames() {
    let old = [m("a", .owner), m("me"), m("b")]
    let new = [m("a", .owner), m("b")]
    #expect(MemberDiff.lines(old: old, new: new, actor: "a", me: "me") == [.youWereRemoved])
    #expect(MemberDiff.lines(old: old, new: new, actor: nil, me: "me") == [.youWereRemoved])
}

@Test func anAccountThatRemovedItselfLeft() {
    let old = [m("a", .owner), m("b")]
    let new = [m("a", .owner)]
    #expect(MemberDiff.lines(old: old, new: new, actor: "b", me: "a") == [.left(who: "b")])
}

@Test func withoutAnActorAddsAndRemovesHaveNoActor() {
    let old = [m("a", .owner), m("b")]
    let new = [m("a", .owner), m("c")]
    #expect(MemberDiff.lines(old: old, new: new, actor: nil, me: "a") == [.removed(by: nil, who: "b"), .added(by: nil, who: "c")])
}

@Test func meBeingAddedIsAnAddedLineForMe() {
    let old = [m("a", .owner)]
    let new = [m("a", .owner), m("me")]
    #expect(MemberDiff.lines(old: old, new: new, actor: "a", me: "me") == [.added(by: "a", who: "me")])
}

@Test func ownershipChangesMakeNoLines() {
    let old = [m("a", .owner), m("b", .admin)]
    let new = [m("a", .admin), m("b", .owner)]
    #expect(MemberDiff.lines(old: old, new: new, actor: "a", me: "a").isEmpty)
}

@Test func aDuplicatedAccountInAListCountsOnce() {
    let old = [m("a", .owner), m("b"), m("b")]
    let new = [m("a", .owner), m("b"), m("b"), m("c"), m("c")]
    #expect(MemberDiff.lines(old: old, new: new, actor: "a", me: "z") == [.added(by: "a", who: "c")])
}

@Test func groupNamesAreOneToFiftyCodePointsUnderTheDisplayNameRules() {
    #expect(GroupNameRule.clean("Book club") == "Book club")
    #expect(GroupNameRule.clean("  Book club  ") == "Book club")
    #expect(GroupNameRule.clean("A") == "A")
    #expect(GroupNameRule.clean(String(repeating: "a", count: 50)) == String(repeating: "a", count: 50))
    #expect(GroupNameRule.clean(String(repeating: "a", count: 51)) == nil)
    #expect(GroupNameRule.clean(String(repeating: "\u{1F355}", count: 50)) != nil)
    #expect(GroupNameRule.clean(String(repeating: "\u{1F355}", count: 51)) == nil)
    #expect(GroupNameRule.clean("") == nil)
    #expect(GroupNameRule.clean("   ") == nil)
    #expect(GroupNameRule.clean("Bo\u{202E}ok") == nil)
    #expect(GroupNameRule.clean("Book\nclub") == nil)
    // A display name still stops at 40.
    #expect(DisplayNameRule.clean(String(repeating: "a", count: 41)) == nil)
}

@Test func aGroupNameChangeIsAcceptedOnlyFromAnOwnerOrAdminWithAHigherVersion() {
    #expect(GroupMerge.accept(currentVer: nil, incomingVer: 1, senderRole: .owner))
    #expect(GroupMerge.accept(currentVer: 1, incomingVer: 2, senderRole: .admin))
    #expect(!GroupMerge.accept(currentVer: 1, incomingVer: 2, senderRole: .member))
    #expect(!GroupMerge.accept(currentVer: 1, incomingVer: 2, senderRole: nil))   // not in the member list
    #expect(!GroupMerge.accept(currentVer: 2, incomingVer: 2, senderRole: .owner))
    #expect(!GroupMerge.accept(currentVer: 5, incomingVer: 4, senderRole: .owner))
    #expect(!GroupMerge.accept(currentVer: nil, incomingVer: 0, senderRole: .owner))
    #expect(!GroupMerge.accept(currentVer: nil, incomingVer: -3, senderRole: .owner))
    #expect(GroupMerge.accept(currentVer: 1, incomingVer: Int.max, senderRole: .admin))
}
