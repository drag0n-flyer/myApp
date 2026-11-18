import Foundation
import Testing
@testable import AppCore

@Test func atTheSameVersionTheGreaterNameThenThePhotoHashWins() {
    func accept(_ current: GroupInfoKey, _ incoming: GroupInfoKey) -> Bool {
        GroupMerge.accept(currentVer: 3, incomingVer: 3, senderRole: .admin, current: current, incoming: incoming)
    }
    func key(_ name: String, _ hash: String? = nil) -> GroupInfoKey { GroupInfoKey(name: name, photoHash: hash) }
    // Both orders of arrival end on the same info.
    #expect(accept(key("Alpha"), key("Zulu")))
    #expect(!accept(key("Zulu"), key("Alpha")))
    #expect(!accept(key("Alpha"), key("Alpha")))
    // Unicode scalars, not a locale: "Z" (U+005A) is before "a" (U+0061), and "é" (U+00E9) after both.
    #expect(accept(key("Z"), key("a")))
    #expect(!accept(key("a"), key("Z")))
    #expect(accept(key("a"), key("é")))
    #expect(!accept(key("é"), key("a")))
    // The same name: the photo's hash decides, and no photo is the least.
    #expect(accept(key("Club"), key("Club", "0a")))
    #expect(!accept(key("Club", "0a"), key("Club")))
    #expect(accept(key("Club", "0a"), key("Club", "ff")))
    #expect(!accept(key("Club", "ff"), key("Club", "0a")))
    #expect(!accept(key("Club", "ff"), key("Club", "ff")))
}

@Test func aTieIsDecidedOnlyForAnOwnerOrAdminAndOnlyAtTheSameVersion() {
    let a = GroupInfoKey(name: "Alpha", photoHash: nil), z = GroupInfoKey(name: "Zulu", photoHash: nil)
    #expect(!GroupMerge.accept(currentVer: 3, incomingVer: 3, senderRole: .member, current: a, incoming: z))
    #expect(!GroupMerge.accept(currentVer: 3, incomingVer: 3, senderRole: nil, current: a, incoming: z))
    #expect(!GroupMerge.accept(currentVer: 3, incomingVer: 3, senderRole: .admin))
    #expect(!GroupMerge.accept(currentVer: 4, incomingVer: 3, senderRole: .admin, current: a, incoming: z))
    #expect(GroupMerge.accept(currentVer: 3, incomingVer: 4, senderRole: .admin, current: z, incoming: a))
    #expect(GroupMerge.accept(currentVer: 3, incomingVer: 3, senderRole: .owner, current: a, incoming: z))
}

@Test func linesWhereIAmTheActorReadAsYou() {
    let me = "acc-me"
    let name: (String) -> String = { $0 == "acc-bob" ? "Bob" : $0 }
    #expect(GroupSystemLine.added(by: me, who: "acc-bob").resolved(me: me, name: name) == .youAdded(who: "Bob"))
    #expect(GroupSystemLine.removed(by: me, who: "acc-bob").resolved(me: me, name: name) == .youRemoved(who: "Bob"))
    #expect(GroupSystemLine.left(who: me).resolved(me: me, name: name) == .youLeft)
    #expect(GroupSystemLine.nameChanged(by: me, name: "Club").resolved(me: me, name: name) == .youChangedName(name: "Club"))
    #expect(GroupSystemLine.photoChanged(by: me).resolved(me: me, name: name) == .youChangedPhoto)
    #expect(GroupSystemLine.added(by: "acc-bob", who: me).resolved(me: me, name: name) == .added(by: "Bob", who: me))
    #expect(GroupSystemLine.added(by: me, who: "acc-bob").resolved(me: nil, name: name) == .added(by: me, who: "Bob"))
    #expect(SystemEvent.group(.youAdded(who: "Bob")).text == "You added Bob")
    #expect(SystemEvent.group(.youRemoved(who: "Bob")).text == "You removed Bob")
    #expect(SystemEvent.group(.youLeft).text == "You left")
    #expect(SystemEvent.group(.youChangedName(name: "Club")).text == "You changed the group name to “Club”")
    #expect(SystemEvent.group(.youChangedPhoto).text == "You changed the group photo")
}
