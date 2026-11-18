import AppCore
import Foundation

private func millis(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
private func fromMillis(_ v: Int64) -> Date { Date(timeIntervalSince1970: Double(v) / 1000) }

public enum GroupLimits {
    /// Members in one group, the server's limit.
    public static let maxMembers = 100
    /// The longest conversation ID a group chat is made from.
    public static let maxConversationIDLength = 128
    /// The profile-photo limit also holds for a group photo.
    public static let maxPhoto = 24_576
}

extension Repository {
    private static let roles: Set<String> = ["owner", "admin", "member"]
    private static let myStates: Set<String> = ["member", "removed", "left"]
    private static let receiptKinds: Set<String> = ["delivered", "read"]

    // MARK: groups

    /// Makes the group chat `g:<conversationID>` when there is none (not a message request, no unread). An existing row,
    /// of either kind, is left exactly as it is. Returns the chat ID, or `nil` for a conversation ID outside [A-Za-z0-9_-] or too long, and for a title the group name rule would change.
    @discardableResult
    public func upsertGroup(conversationID: String, title: String?, photo: Data?, at: Date) throws -> String? {
        guard !conversationID.isEmpty, conversationID.count <= GroupLimits.maxConversationIDLength,
              conversationID.utf8.allSatisfy({ ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || $0 == 0x2D || $0 == 0x5F })
        else { return nil }
        // A title is kept only as the group name rule would write it.
        if let title, GroupNameRule.clean(title) != title { return nil }
        let id = "g:" + conversationID
        try db.execute("""
            INSERT INTO chats(id, is_request, unread, muted, last_activity, kind, conversation_id, title, photo)
            VALUES (?,0,0,0,?,'group',?,?,?) ON CONFLICT(id) DO NOTHING
            """, [.text(id), .int(millis(at)), .text(conversationID), title.map { .text($0) } ?? .null,
                  (photo.flatMap { $0.count <= GroupLimits.maxPhoto ? $0 : nil }).map { .blob($0) } ?? .null])
        return id
    }

    public func groupMembers(chatID: String) throws -> [GroupMemberRecord] {
        try db.query("SELECT account_id, role FROM group_members WHERE chat_id=? ORDER BY account_id", [.text(chatID)]) {
            GroupMemberRecord(accountID: $0.text(0), role: $0.text(1))
        }
    }

    /// Replaces the member list of a group chat in one transaction, when `version` is higher than the stored one.
    /// Returns the members as they were, or `nil` when nothing changed: not a group chat, a version that is not higher
    /// (or below 0), or a list over 100 accounts. An empty account ID and a repeated account are dropped; a role this
    /// build does not know is stored as `member`.
    @discardableResult
    public func setGroupMembers(chatID: String, _ members: [GroupMemberRecord], version: Int64) throws -> [GroupMemberRecord]? {
        guard version >= 0, members.count <= GroupLimits.maxMembers else { return nil }
        var seen = Set<String>()
        var clean: [GroupMemberRecord] = []
        for m in members where !m.accountID.isEmpty && seen.insert(m.accountID).inserted {
            clean.append(GroupMemberRecord(accountID: m.accountID, role: Self.roles.contains(m.role) ? m.role : "member"))
        }
        return try db.transaction { d -> [GroupMemberRecord]? in
            let state = try d.query("SELECT members_version FROM chats WHERE id=? AND kind='group'", [.text(chatID)]) {
                $0.isNull(0) ? Int64?.none : Int64?.some($0.int(0))
            }
            guard let stored = state.first else { return nil }
            if let stored, version <= stored { return nil }
            let old = try self.groupMembers(chatID: chatID)
            try d.execute("DELETE FROM group_members WHERE chat_id=?", [.text(chatID)])
            for m in clean {
                try d.execute("INSERT INTO group_members(chat_id, account_id, role) VALUES (?,?,?)", [.text(chatID), .text(m.accountID), .text(m.role)])
            }
            try d.execute("UPDATE chats SET members_version=? WHERE id=?", [.int(version), .text(chatID)])
            return old
        }
    }

    /// Sets the name and photo of a group chat when `ver` is at least 1 and higher than the stored `group_ver`. A photo
    /// over 24,576 bytes is refused whole. `false` when nothing changed.
    @discardableResult
    public func setGroupInfo(chatID: String, title: String, photo: Data?, ver: Int) throws -> Bool {
        guard ver >= 1, (photo?.count ?? 0) <= GroupLimits.maxPhoto else { return false }
        return try db.run("""
            UPDATE chats SET title=?, photo=?, group_ver=? WHERE id=? AND kind='group' AND (group_ver IS NULL OR group_ver < ?)
            """, [.text(title), photo.map { .blob($0) } ?? .null, .int(Int64(ver)), .text(chatID), .int(Int64(ver))]) > 0
    }

    /// `member`, `removed` or `left`, for a group chat only. `false` when nothing changed.
    @discardableResult
    public func setMyState(chatID: String, _ state: String) throws -> Bool {
        guard Self.myStates.contains(state) else { return false }
        return try db.run("UPDATE chats SET my_state=? WHERE id=? AND kind='group'", [.text(state), .text(chatID)]) > 0
    }

    // MARK: receipts

    /// One row per member and kind (`delivered`, `read`); the first time stays. `false` for a repeat, an unknown kind
    /// or a message that is not stored.
    @discardableResult
    public func addReceipt(messageID: String, accountID: String, kind: String, at: Date) throws -> Bool {
        guard Self.receiptKinds.contains(kind), !accountID.isEmpty else { return false }
        return try db.run("""
            INSERT OR IGNORE INTO receipts(message_id, account_id, kind, at)
            SELECT id, ?, ?, ? FROM messages WHERE id=?
            """, [.text(accountID), .text(kind), .int(millis(at)), .text(messageID)]) > 0
    }

    public func receipts(messageID: String) throws -> [ReceiptRecord] {
        try db.query("SELECT message_id, account_id, kind, at FROM receipts WHERE message_id=? ORDER BY account_id, kind", [.text(messageID)]) {
            ReceiptRecord(messageID: $0.text(0), accountID: $0.text(1), kind: $0.text(2), at: fromMillis($0.int(3)))
        }
    }
}
