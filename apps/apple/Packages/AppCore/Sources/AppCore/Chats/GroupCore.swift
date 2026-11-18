/// How a chat ID says what kind of chat it is. A one-to-one chat's ID is the peer's account ID (unchanged); a group
/// chat's ID is `g:<conversationID>`.
public enum ChatID {
    public static let groupPrefix = "g:"

    public static func group(_ conversationID: String) -> String { groupPrefix + conversationID }

    /// `true` for `g:` followed by at least one character.
    public static func isGroup(_ id: String) -> Bool {
        id.hasPrefix(groupPrefix) && id.count > groupPrefix.count
    }

    /// The server conversation ID of a group chat ID; `nil` for anything else.
    public static func conversationID(_ id: String) -> String? {
        isGroup(id) ? String(id.dropFirst(groupPrefix.count)) : nil
    }
}

/// A member's part in a group. The server's list is the authority; the meaning of each role lives here.
public enum GroupRole: String, Codable, Sendable, CaseIterable {
    case owner, admin, member

    /// A role string from the server; any value this build does not know is a plain member, never more.
    public init(wire: String) { self = GroupRole(rawValue: wire) ?? .member }

    public var canAdd: Bool { self == .owner || self == .admin }
    public var canSetRoles: Bool { self == .owner }
    public var canEditInfo: Bool { self == .owner || self == .admin }
    public var canSetTimer: Bool { self == .owner || self == .admin }

    /// The owner removes an admin or a member; an admin removes only a member; nobody removes an owner.
    public func canRemove(target: GroupRole) -> Bool {
        switch self {
        case .owner: target != .owner
        case .admin: target == .member
        case .member: false
        }
    }
}

public struct GroupMember: Hashable, Sendable {
    public var accountID: String
    public var role: GroupRole
    public init(accountID: String, role: GroupRole) { self.accountID = accountID; self.role = role }
}

/// A line in a group chat that says what changed in the member list.
public enum GroupSystemLine: Hashable, Sendable {
    /// "%@ added %@"; `by` is `nil` when the actor is not known.
    case added(by: String?, who: String)
    /// "%@ removed %@"
    case removed(by: String?, who: String)
    /// "%@ left"
    case left(who: String)
    /// "You were removed"
    case youWereRemoved
    /// "%@ is now an admin"
    case madeAdmin(who: String)
    case noLongerAdmin(who: String)
}

public enum MemberDiff {
    /// The lines for the change from `old` to `new`, one per affected account, ordered by account ID so every
    /// device writes the same lines in the same order. `actor` is the account that made the change, when known:
    /// an account that is gone and is the actor left; otherwise it was removed by the actor. `me` being gone is
    /// one line without names. A change to or from `owner` makes no line.
    public static func lines(old: [GroupMember], new: [GroupMember], actor: String?, me: String) -> [GroupSystemLine] {
        func index(_ list: [GroupMember]) -> [String: GroupRole] {
            var map: [String: GroupRole] = [:]
            for m in list where map[m.accountID] == nil { map[m.accountID] = m.role }
            return map
        }
        let before = index(old)
        let after = index(new)
        var lines: [GroupSystemLine] = []
        for id in Set(before.keys).union(after.keys).sorted() {
            switch (before[id], after[id]) {
            case (nil, .some):
                lines.append(.added(by: actor, who: id))
            case (.some, nil):
                if id == me { lines.append(.youWereRemoved) }
                else if id == actor { lines.append(.left(who: id)) }
                else { lines.append(.removed(by: actor, who: id)) }
            case (.some(let was), .some(let now)) where was != now:
                if was == .member, now == .admin { lines.append(.madeAdmin(who: id)) }
                else if was == .admin, now == .member { lines.append(.noLongerAdmin(who: id)) }
            default: break
            }
        }
        return lines
    }
}

/// A group name: 1 to 50 code points under the display-name character rules.
public enum GroupNameRule {
    public static let max = 50

    public static func clean(_ name: String) -> String? { DisplayNameRule.clean(name, limit: max) }
}

/// A `group` envelope changes the name or photo only when an owner or admin sent it and its version is higher.
public enum GroupMerge {
    /// `senderRole` is the sender's role in the stored member list; `nil` when the sender is not in it.
    public static func accept(currentVer: Int?, incomingVer: Int, senderRole: GroupRole?) -> Bool {
        guard incomingVer >= 1, let senderRole, senderRole.canEditInfo else { return false }
        return incomingVer > (currentVer ?? 0)
    }
}
