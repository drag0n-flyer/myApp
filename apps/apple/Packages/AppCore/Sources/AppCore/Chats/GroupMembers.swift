import Foundation

/// Whether this account is still in a group: `removed` and `left` keep the chat, read-only.
public enum GroupMyState: String, Codable, Sendable {
    case member, removed, left

    /// A state string from the database; anything this build does not know reads as `member`.
    public init(wire: String) { self = GroupMyState(rawValue: wire) ?? .member }

    /// "You're no longer in this group": the composer is replaced by that line.
    public var isReadOnly: Bool { self != .member }
}

/// Why a change to a group did not happen. The screen words each case from the String Catalog.
public enum GroupChangeFailure: Equatable, Sendable {
    /// The server or this device's copy of the list says this person may not do that ("You can't do that in this group.").
    case forbidden
    /// An owner leaving while others remain ("Choose a new owner before you leave.").
    case ownerMustTransfer
    /// More than 100 people ("This group is full.").
    case full
    /// The group or one of the people is not known to the server.
    case notFound
    case invalidName, invalidPhoto
    case rateLimited
    /// A network or server problem: trying again later may work.
    case offline
    case other
}

public enum GroupChangeOutcome: Equatable, Sendable {
    case done
    /// People were added; `notAdded` are the account IDs that are members but not reachable yet ("%@ couldn't be added yet").
    case added(notAdded: [String])
    case failed(GroupChangeFailure)
}

/// Changing a group after it was made: its people, their roles, its name and photo. A separate part of `GroupStore` so
/// stores that cannot keep working through the defaults. Every call returns when the server and the group agree, or says why not.
@MainActor
public protocol GroupAdminStore: AnyObject {
    /// The member list this device holds for a group chat, sorted by account ID; empty for any other chat.
    func groupMembers(chatID: String) -> [GroupMember]
    /// Adds people (owner or admin). They are members at once; a person whose devices cannot be added yet is in `notAdded`.
    func addMembers(chatID: String, accounts: [String]) async -> GroupChangeOutcome
    /// Removes a person (the owner removes an admin or a member; an admin removes a member).
    func removeMember(chatID: String, account: String) async -> GroupChangeOutcome
    /// Leaves the group. An owner must make someone else the owner first.
    func leaveGroup(chatID: String) async -> GroupChangeOutcome
    /// Sets another person's role (owner only); `owner` hands the group over and makes me an admin.
    func setRole(chatID: String, account: String, role: GroupRole) async -> GroupChangeOutcome
    /// Changes the name (owner or admin).
    func renameGroup(chatID: String, name: String) async -> GroupChangeOutcome
    /// Changes the photo (owner or admin); `nil` removes it. A photo must pass `ProfilePhotoRule`.
    func setGroupPhoto(chatID: String, photo: Data?) async -> GroupChangeOutcome
}

extension GroupAdminStore {
    public func groupMembers(chatID: String) -> [GroupMember] { [] }
    public func addMembers(chatID: String, accounts: [String]) async -> GroupChangeOutcome { .failed(.other) }
    public func removeMember(chatID: String, account: String) async -> GroupChangeOutcome { .failed(.other) }
    public func leaveGroup(chatID: String) async -> GroupChangeOutcome { .failed(.other) }
    public func setRole(chatID: String, account: String, role: GroupRole) async -> GroupChangeOutcome { .failed(.other) }
    public func renameGroup(chatID: String, name: String) async -> GroupChangeOutcome { .failed(.other) }
    public func setGroupPhoto(chatID: String, photo: Data?) async -> GroupChangeOutcome { .failed(.other) }
}
