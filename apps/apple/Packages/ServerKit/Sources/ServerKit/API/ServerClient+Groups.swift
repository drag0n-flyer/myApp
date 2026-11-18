import AppCore
import Foundation

private func seg(_ s: String) -> String {
    s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s
}

/// One member of a group as the server lists it. A role this build does not know reads as `member`.
public struct GroupMemberInfo: Decodable, Equatable, Sendable {
    public let account: String
    public let role: GroupRole

    public init(account: String, role: GroupRole) {
        self.account = account
        self.role = role
    }

    enum CodingKeys: String, CodingKey { case account, role }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        account = try c.decode(String.self, forKey: .account)
        role = GroupRole(wire: try c.decode(String.self, forKey: .role))
    }
}

/// The answer of every membership call: the member list version after the change, and whether the conversation is gone
/// (the last member left).
public struct MembersChange: Decodable, Equatable, Sendable {
    public let membersVersion: Int64
    public let deleted: Bool

    public init(membersVersion: Int64, deleted: Bool) {
        self.membersVersion = membersVersion
        self.deleted = deleted
    }
}

/// A `members` inbox item read as an event: the group's member list changed and is now at `membersVersion`.
public struct GroupMembersEvent: Equatable, Sendable {
    public let conversationID: String
    public let membersVersion: Int64

    public init(conversationID: String, membersVersion: Int64) {
        self.conversationID = conversationID
        self.membersVersion = membersVersion
    }
}

extension InboxItem {
    /// The event of a `members` item; `nil` for any other kind, or one without a usable version or conversation.
    public var membersEvent: GroupMembersEvent? {
        guard kind == "members", let membersVersion, membersVersion >= 0, !conversationID.isEmpty else { return nil }
        return GroupMembersEvent(conversationID: conversationID, membersVersion: membersVersion)
    }
}

extension ServerClient {
    /// Makes a group with 1 to 99 other accounts; the caller is its owner. Throws `not_found` (an unknown account),
    /// `group_full` or `rate_limited`.
    public func createGroup(accounts: [String]) async throws(ServerError) -> NewConversation {
        struct Body: Encodable { let kind: String; let accounts: [String] }
        return try await request("POST", "/v1/conversations", body: Body(kind: "group", accounts: accounts), auth: .session)
    }

    /// Adds accounts (owner or admin). Throws `forbidden` or `group_full`.
    public func addAccounts(conversationID: String, accounts: [String]) async throws(ServerError) -> MembersChange {
        struct Body: Encodable { let accounts: [String] }
        return try await request("POST", "/v1/conversations/\(seg(conversationID))/accounts", body: Body(accounts: accounts), auth: .session)
    }

    /// Removes an account, or leaves when `account` is the caller's own. Throws `forbidden`, `not_found` or
    /// `owner_must_transfer` (an owner leaving while others remain).
    public func removeAccount(conversationID: String, account: String) async throws(ServerError) -> MembersChange {
        try await request("DELETE", "/v1/conversations/\(seg(conversationID))/accounts/\(seg(account))", auth: .session)
    }

    /// Sets another member's role (owner only); `owner` makes the caller an admin.
    public func setRole(conversationID: String, account: String, role: GroupRole) async throws(ServerError) -> MembersChange {
        struct Body: Encodable { let role: String }
        return try await request("PUT", "/v1/conversations/\(seg(conversationID))/accounts/\(seg(account))/role",
                                 body: Body(role: role.rawValue), auth: .session)
    }
}
