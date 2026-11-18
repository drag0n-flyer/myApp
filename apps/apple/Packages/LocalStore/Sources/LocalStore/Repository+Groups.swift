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
    /// The smallest group photo a client stores or sends (a JPEG header alone is longer).
    public static let minPhoto = 4

    /// A conversation ID a group chat may be made from: 1 to 128 characters of A-Z, a-z, 0-9, `-` and `_`.
    public static func isValidConversationID(_ id: String) -> Bool {
        !id.isEmpty && id.count <= maxConversationIDLength
            && id.utf8.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || $0 == 0x2D || $0 == 0x5F }
    }
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
        guard GroupLimits.isValidConversationID(conversationID) else { return nil }
        // A title is kept only as the group name rule would write it.
        if let title, GroupNameRule.clean(title) != title { return nil }
        let id = "g:" + conversationID
        try db.execute("""
            INSERT INTO chats(id, is_request, unread, muted, last_activity, kind, conversation_id, title, photo)
            VALUES (?,0,0,0,?,'group',?,?,?) ON CONFLICT(id) DO NOTHING
            """, [.text(id), .int(millis(at)), .text(conversationID), title.map { .text($0) } ?? .null,
                  (photo.flatMap { ProfilePhotoRule.isAcceptable($0) ? $0 : nil }).map { .blob($0) } ?? .null])
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
    /// under 4 or over 24,576 bytes is refused whole. `false` when nothing changed.
    @discardableResult
    public func setGroupInfo(chatID: String, title: String, photo: Data?, ver: Int, orEqual: Bool = false) throws -> Bool {
        guard ver >= 1, photo.map({ ProfilePhotoRule.isAcceptable($0) }) ?? true else { return false }
        // `orEqual`: the caller has already decided an equal version wins (`GroupMerge`, the tie rule).
        return try db.run("""
            UPDATE chats SET title=?, photo=?, group_ver=? WHERE id=? AND kind='group' AND (group_ver IS NULL OR group_ver \(orEqual ? "<=" : "<") ?)
            """, [.text(title), photo.map { .blob($0) } ?? .null, .int(Int64(ver)), .text(chatID), .int(Int64(ver))]) > 0
    }

    /// `member`, `removed` or `left`, for a group chat only. `false` when nothing changed.
    @discardableResult
    public func setMyState(chatID: String, _ state: String) throws -> Bool {
        guard Self.myStates.contains(state) else { return false }
        return try db.run("UPDATE chats SET my_state=? WHERE id=? AND kind='group'", [.text(state), .text(chatID)]) > 0
    }

    /// Removes every conversation row of a group chat (and, by cascade, its stored MLS members), keeping the chat, its
    /// messages and its member list. Used when I am removed or leave, so a later Welcome for the same conversation is
    /// a fresh join and never refused as rebound. Returns the server IDs that were removed.
    @discardableResult
    public func deleteConversations(groupChatID chatID: String) throws -> [String] {
        guard ChatID.isGroup(chatID) else { return [] }
        return try db.transaction { d -> [String] in
            let ids = try d.query("SELECT server_id FROM conversations WHERE chat_id=?", [.text(chatID)]) { $0.text(0) }
            try d.execute("DELETE FROM conversations WHERE chat_id=?", [.text(chatID)])
            return ids
        }
    }

    /// Deletes a group chat I am no longer in, with its messages, member list, queued sends and every other row under it,
    /// in one transaction. Returns the local files of its attachments and the IDs of its messages (a queued upload keeps a
    /// folder named after its message), so the caller deletes the files. `nil` and nothing deleted for a chat that is
    /// not a group chat, is unknown, or is one I am still a member of.
    public func deleteGroupChat(id chatID: String) throws -> (paths: [String], messageIDs: [String])? {
        guard ChatID.isGroup(chatID) else { return nil }
        return try db.transaction { d -> (paths: [String], messageIDs: [String])? in
            let state = try d.query("SELECT my_state FROM chats WHERE id=? AND kind='group'", [.text(chatID)]) { $0.text(0) }
            guard let state = state.first, state != "member" else { return nil }
            let paths = try d.query("""
                SELECT a.local_path FROM attachments a JOIN messages m ON m.id = a.message_id WHERE m.chat_id=? AND a.local_path IS NOT NULL
                """, [.text(chatID)]) { $0.text(0) }
            let messageIDs = try d.query("SELECT id FROM messages WHERE chat_id=?", [.text(chatID)]) { $0.text(0) }
            try d.execute("DELETE FROM outbox WHERE chat_id=?", [.text(chatID)])
            try d.execute("DELETE FROM chats WHERE id=?", [.text(chatID)])
            return (paths, messageIDs)
        }
    }

    /// What is still queued for a chat can never be sent (I am out of the group): the queue rows go and the messages
    /// among them are marked `failed`, one transaction.
    public func failQueuedSends(chatID: String) throws {
        try db.transaction { d in
            try d.execute("UPDATE messages SET status='failed' WHERE status='sending' AND id IN (SELECT message_id FROM outbox WHERE chat_id=?)", [.text(chatID)])
            try d.execute("DELETE FROM outbox WHERE chat_id=?", [.text(chatID)])
        }
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
