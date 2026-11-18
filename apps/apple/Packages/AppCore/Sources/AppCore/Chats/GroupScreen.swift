import Foundation

/// Where the person stands in a group chat. A chat that is not `member` is read-only and keeps its history.
public enum GroupMyState: String, Sendable {
    case member, removed, left

    /// A state string from the store; one this build does not know has no rights (it is treated as removed).
    public init(wire: String) { self = GroupMyState(rawValue: wire) ?? .removed }
}

/// One row of the member list in group info. `name` is what to draw: the contact's name, else the name from the
/// person's profile, else "" (the screen then says "Someone").
public struct GroupMemberRow: Hashable, Sendable {
    public var accountID: String
    public var name: String
    public var role: GroupRole
    public var isMe: Bool
    /// The person is a contact of this device: only then is there a one-to-one chat page for Message and the safety number.
    public var isContact: Bool

    public init(accountID: String, name: String, role: GroupRole, isMe: Bool, isContact: Bool) {
        self.accountID = accountID; self.name = name; self.role = role; self.isMe = isMe; self.isContact = isContact
    }
}

/// Everything the group info screen shows, read from the store at one moment.
public struct GroupInfo: Equatable, Sendable {
    public var chatID: String
    /// The group's name; `nil` until the first `group` message arrives.
    public var name: String?
    /// The stored photo, which the screen draws only after `GroupScreenRules.displayablePhoto`.
    public var photo: Data?
    public var members: [GroupMemberRow]
    /// The person's own role in the list; `nil` when the list has no entry for them.
    public var myRole: GroupRole?
    public var myState: GroupMyState

    public init(chatID: String, name: String?, photo: Data?, members: [GroupMemberRow], myRole: GroupRole?, myState: GroupMyState) {
        self.chatID = chatID; self.name = name; self.photo = photo; self.members = members; self.myRole = myRole; self.myState = myState
    }
}

/// What a change to a group (add, remove, leave, role, name, photo) came to.
public enum GroupChangeFailure: Equatable, Sendable {
    /// The server refused: the person may not do this here.
    case forbidden
    /// The owner must hand the group to someone before leaving.
    case ownerMustTransfer
    case full
    case invalidName, invalidPhoto
    /// The server does not know the person or the group.
    case notFound
    case offline
    case other
}

public enum GroupChangeOutcome: Equatable, Sendable {
    case done
    case failed(GroupChangeFailure)

    /// Plain words for the person, or `nil` when it worked. These are keys of the string catalog.
    public var message: String? {
        switch self {
        case .done: nil
        case .failed(let failure):
            switch failure {
            case .ownerMustTransfer: "Choose a new owner before you leave."
            case .forbidden: "You can't do that in this group."
            case .full: "This group is full."
            case .invalidName: "Choose a different name."
            case .invalidPhoto: "Choose a different photo."
            case .notFound: "This group or person could not be found."
            case .offline, .other: "Couldn't change the group. Check your connection and try again."
            }
        }
    }
}

/// What a member's menu may offer.
public enum GroupMemberAction: Hashable, Sendable {
    case message, viewSafetyNumber, makeAdmin, removeAdmin, makeOwner, remove
}

/// The decisions of the group screens, as pure functions (the server enforces the same rules again).
public enum GroupScreenRules {
    /// Owner, then admins, then members; each by name (case-insensitive), equal names by account ID.
    public static func sorted(_ members: [GroupMemberRow]) -> [GroupMemberRow] {
        func rank(_ role: GroupRole) -> Int {
            switch role {
            case .owner: 0
            case .admin: 1
            case .member: 2
            }
        }
        return members.sorted { a, b in
            if rank(a.role) != rank(b.role) { return rank(a.role) < rank(b.role) }
            switch a.name.caseInsensitiveCompare(b.name) {
            case .orderedAscending: return true
            case .orderedDescending: return false
            case .orderedSame: return a.accountID < b.accountID
            }
        }
    }

    public static func canAdd(myRole: GroupRole?, myState: GroupMyState) -> Bool {
        myState == .member && (myRole?.canAdd ?? false)
    }

    public static func canEditInfo(myRole: GroupRole?, myState: GroupMyState) -> Bool {
        myState == .member && (myRole?.canEditInfo ?? false)
    }

    public static func canSetTimer(myRole: GroupRole?, myState: GroupMyState) -> Bool {
        myState == .member && (myRole?.canSetTimer ?? false)
    }

    public static func canLeave(myState: GroupMyState) -> Bool { myState == .member }

    /// "Delete chat" shows after the person left or was removed.
    public static func canDeleteChat(myState: GroupMyState) -> Bool { myState != .member }

    /// The menu of one member row, in a fixed order. Nothing on the person's own row or once they are out of the
    /// group (Message and the safety number stay: they belong to the one-to-one chat). Roles are set by the owner only;
    /// the owner is never removed or made owner again.
    public static func actions(myRole: GroupRole?, myState: GroupMyState, target: GroupMemberRow) -> [GroupMemberAction] {
        guard !target.isMe, myState == .member else { return [] }
        var list: [GroupMemberAction] = []
        if target.isContact { list += [.message, .viewSafetyNumber] }
        if myRole?.canSetRoles == true {
            switch target.role {
            case .member: list += [.makeAdmin, .makeOwner]
            case .admin: list += [.removeAdmin, .makeOwner]
            case .owner: break
            }
        }
        if let myRole, myRole.canRemove(target: target.role) { list.append(.remove) }
        return list
    }

    /// Contacts that may be offered under "Add members": not already in the group, each once, in the given order.
    public static func addCandidates(contactIDs: [String], members: [GroupMemberRow]) -> [String] {
        var taken = Set(members.map(\.accountID))
        var result: [String] = []
        for id in contactIDs where !taken.contains(id) {
            taken.insert(id)
            result.append(id)
        }
        return result
    }

    /// How many more people fit (100 in total).
    public static func room(memberCount: Int) -> Int { max(0, 100 - memberCount) }

    /// The photo to draw, only when it passes the same rule as a profile photo.
    public static func displayablePhoto(_ data: Data?) -> Data? {
        guard let data, ProfilePhotoRule.isAcceptable(data) else { return nil }
        return data
    }

    /// The name to send for a rename: the field's text under the group-name rule, or `nil` when it is not a valid
    /// name or is what the group is already called.
    public static func editedName(_ text: String, current: String?) -> String? {
        guard let clean = GroupNameRule.clean(text), clean != current else { return nil }
        return clean
    }
}
