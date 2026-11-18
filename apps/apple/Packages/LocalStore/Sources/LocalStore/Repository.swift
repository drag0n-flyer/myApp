import Foundation

private func ms(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
private func date(_ v: Int64) -> Date { Date(timeIntervalSince1970: Double(v) / 1000) }
private func b(_ v: Bool) -> SQLValue { .int(v ? 1 : 0) }
private func opt(_ s: String?) -> SQLValue { s.map { .text($0) } ?? .null }

private let statusRank = ["sending": 0, "sent": 1, "delivered": 2, "read": 3]

/// Typed access to the tables. Timestamps are stored as milliseconds since 1970.
public final class Repository: @unchecked Sendable {
    private let db: Database
    private let now: @Sendable () -> Date

    public init(db: Database, now: @escaping @Sendable () -> Date) {
        self.db = db
        self.now = now
    }

    /// Runs `body` in one transaction (joins an outer one). Rolls back when `body` throws.
    public func transaction<T>(_ body: () throws -> T) throws -> T {
        try db.transaction { _ in try body() }
    }

    // MARK: contacts

    public func upsertContact(_ c: ContactRecord) throws {
        try db.execute("INSERT OR REPLACE INTO contacts(account_id, username, display_name, is_contact, added_at) VALUES (?,?,?,?,?)",
                       [.text(c.accountID), .text(c.username), .text(c.displayName), b(c.isContact), .int(ms(c.addedAt))])
    }

    private func contactRow(_ r: Row) -> ContactRecord {
        ContactRecord(accountID: r.text(0), username: r.text(1), displayName: r.text(2), isContact: r.int(3) != 0, addedAt: date(r.int(4)))
    }
    private let contactCols = "account_id, username, display_name, is_contact, added_at"

    public func contact(id: String) throws -> ContactRecord? {
        try db.query("SELECT \(contactCols) FROM contacts WHERE account_id=?", [.text(id)], row: contactRow).first
    }

    public func contacts() throws -> [ContactRecord] {
        try db.query("SELECT \(contactCols) FROM contacts ORDER BY display_name, account_id", row: contactRow)
    }

    // MARK: chats

    private let chatCols = "id, is_request, unread, muted, last_activity, pinned_at, archived, timer_seconds"
    private func chatRow(_ r: Row) -> ChatRecord {
        ChatRecord(id: r.text(0), isRequest: r.int(1) != 0, unread: Int(r.int(2)), muted: r.int(3) != 0, lastActivity: date(r.int(4)),
                   pinnedAt: r.isNull(5) ? nil : date(r.int(5)), archived: r.int(6) != 0, timerSeconds: Int(r.int(7)))
    }

    /// Inserts the chat, or updates `is_request`, `unread` and `last_activity` of an existing one. Pin, mute and
    /// archive are device choices with their own single-column writes (`setPinnedAt`, `setMuted`, `setArchived`):
    /// a whole-row write built from an older read never undoes them, so they are not written here for an existing row.
    public func upsertChat(_ c: ChatRecord) throws {
        // Not INSERT OR REPLACE: that would cascade-delete everything under the chat.
        try db.execute("""
            INSERT INTO chats(id, is_request, unread, muted, last_activity, pinned_at, archived) VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET is_request=excluded.is_request, unread=excluded.unread,
            last_activity=excluded.last_activity
            """, [.text(c.id), b(c.isRequest), .int(Int64(c.unread)), b(c.muted), .int(ms(c.lastActivity)),
                  c.pinnedAt.map { .int(ms($0)) } ?? .null, b(c.archived)])
    }

    public func chat(id: String) throws -> ChatRecord? {
        try db.query("SELECT \(chatCols) FROM chats WHERE id=?", [.text(id)], row: chatRow).first
    }

    public func chats() throws -> [ChatRecord] {
        try db.query("SELECT \(chatCols) FROM chats ORDER BY last_activity DESC, id", row: chatRow)
    }

    public func setUnread(chatID: String, _ n: Int) throws {
        try db.execute("UPDATE chats SET unread=? WHERE id=?", [.int(Int64(n)), .text(chatID)])
    }

    public func setRequest(chatID: String, _ v: Bool) throws {
        try db.execute("UPDATE chats SET is_request=? WHERE id=?", [b(v), .text(chatID)])
    }

    // MARK: disappearing messages

    /// The chat's timer in seconds (0 = off, also for an unknown chat). One column, one statement.
    public func setTimer(chatID: String, seconds: Int) throws {
        try db.execute("UPDATE chats SET timer_seconds=? WHERE id=?", [.int(Int64(seconds)), .text(chatID)])
    }

    public func timerSeconds(chatID: String) throws -> Int {
        try db.query("SELECT timer_seconds FROM chats WHERE id=?", [.text(chatID)]) { Int($0.int(0)) }.first ?? 0
    }

    /// Removes every message whose `expires_at` is at or before `now`, in one transaction: the row, its attachment
    /// rows, reactions and search entry (cascade and trigger) and any queued send. Returns what was removed, with the
    /// attachment file paths, so the caller can delete the files and the delivered notifications.
    /// With `me` (this account), each chat's unread count drops by the removed messages that were unread and from others.
    public func sweepExpired(now: Date, me: String? = nil) throws -> [ExpiredMessage] {
        try db.transaction { d in
            if let me {
                let counts = try d.query("""
                    SELECT chat_id, COUNT(*) FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ? AND \(Self.unreadFromOthers)
                    GROUP BY chat_id
                    """, [.int(ms(now)), .text(me)]) { ($0.text(0), Int($0.int(1))) }
                for (chatID, n) in counts {
                    try d.execute("UPDATE chats SET unread = MAX(0, unread - ?) WHERE id=?", [.int(Int64(n)), .text(chatID)])
                }
            }
            let due = try d.query("SELECT id, chat_id FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ? ORDER BY expires_at, id",
                                  [.int(ms(now))]) { ($0.text(0), $0.text(1)) }
            var removed: [ExpiredMessage] = []
            for (id, chatID) in due {
                let paths = try d.query("SELECT local_path FROM attachments WHERE message_id=? AND local_path IS NOT NULL", [.text(id)]) { $0.text(0) }
                try d.execute("DELETE FROM outbox WHERE message_id=?", [.text(id)])
                try d.execute("DELETE FROM messages WHERE id=?", [.text(id)])
                removed.append(ExpiredMessage(id: id, chatID: chatID, localPaths: paths))
            }
            return removed
        }
    }

    /// Unread messages from others (not `me`, not system lines, not read, not deleted).
    private static let unreadFromOthers = "sender_account != ? AND sender_account != 'system' AND status != 'read' AND deleted = 0 AND undecryptable = 0"

    /// Per chat, how many of its counted unread messages have expired but are not swept yet. A reader subtracts this
    /// from `ChatRecord.unread`, so an expired message no longer counts before the sweep has run.
    public func expiredUnreadCounts(me: String) throws -> [String: Int] {
        let rows = try db.query("""
            SELECT chat_id, COUNT(*) FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ? AND \(Self.unreadFromOthers)
            GROUP BY chat_id
            """, [.int(ms(now())), .text(me)]) { ($0.text(0), Int($0.int(1))) }
        return Dictionary(rows, uniquingKeysWith: { $1 })
    }

    /// The earliest `expires_at` of any message, for the sweep timer.
    public func nextExpiry() throws -> Date? {
        try db.query("SELECT MIN(expires_at) FROM messages WHERE expires_at IS NOT NULL") { $0.isNull(0) ? nil : date($0.int(0)) }.first ?? nil
    }

    // MARK: profiles

    public func profile(for accountID: String) throws -> ProfileRecord? {
        try db.query("SELECT account_id, name, photo, ver FROM profiles WHERE account_id=?", [.text(accountID)]) {
            ProfileRecord(accountID: $0.text(0), name: $0.text(1), photo: $0.isNull(2) ? nil : $0.blob(2), ver: Int($0.int(3)))
        }.first
    }

    /// Every stored contact profile (read once per reload, not once per screen row).
    public func profiles() throws -> [ProfileRecord] {
        try db.query("SELECT account_id, name, photo, ver FROM profiles") {
            ProfileRecord(accountID: $0.text(0), name: $0.text(1), photo: $0.isNull(2) ? nil : $0.blob(2), ver: Int($0.int(3)))
        }
    }

    /// Keeps the profile only when `ver` is at least 1 and strictly higher than the stored one; `false` otherwise,
    /// and nothing changes.
    @discardableResult
    public func saveProfile(accountID: String, name: String, photo: Data?, ver: Int) throws -> Bool {
        guard ver >= 1 else { return false }
        return try db.run("""
            INSERT INTO profiles(account_id, name, photo, ver) VALUES (?,?,?,?)
            ON CONFLICT(account_id) DO UPDATE SET name=excluded.name, photo=excluded.photo, ver=excluded.ver
            WHERE excluded.ver > profiles.ver
            """, [.text(accountID), .text(name), photo.map { .blob($0) } ?? .null, .int(Int64(ver))]) > 0
    }

    // MARK: chat list actions (one column, one statement)

    public func setPinnedAt(chatID: String, _ at: Date?) throws {
        try db.execute("UPDATE chats SET pinned_at=? WHERE id=?", [at.map { .int(ms($0)) } ?? .null, .text(chatID)])
    }

    /// Pins the chat at `at` unless `limit` chats are already pinned or the chat is archived or missing; in one
    /// transaction. A chat that is already pinned keeps its pin time and counts as done.
    @discardableResult
    public func pin(chatID: String, at: Date, limit: Int) throws -> Bool {
        try db.transaction { d in
            guard let row = try d.query("SELECT archived, pinned_at IS NOT NULL FROM chats WHERE id=?", [.text(chatID)], row: { ($0.int(0) != 0, $0.int(1) != 0) }).first,
                  !row.0 else { return false }
            if row.1 { return true }
            let pinned = try d.query("SELECT COUNT(*) FROM chats WHERE pinned_at IS NOT NULL AND archived=0") { Int($0.int(0)) }.first ?? 0
            guard pinned < limit else { return false }
            try d.execute("UPDATE chats SET pinned_at=? WHERE id=? AND archived=0", [.int(ms(at)), .text(chatID)])
            return true
        }
    }

    public func setMuted(chatID: String, _ muted: Bool) throws {
        try db.execute("UPDATE chats SET muted=? WHERE id=?", [b(muted), .text(chatID)])
    }

    /// Archiving also unpins, in the same statement; unarchiving does not pin again.
    public func setArchived(chatID: String, _ archived: Bool) throws {
        if archived {
            try db.execute("UPDATE chats SET archived=1, pinned_at=NULL WHERE id=?", [.text(chatID)])
        } else {
            try db.execute("UPDATE chats SET archived=0 WHERE id=?", [.text(chatID)])
        }
    }

    /// Raises `last_activity` only; never lowers it and touches no other column.
    public func bumpActivity(chatID: String, at: Date) throws {
        try db.execute("UPDATE chats SET last_activity=? WHERE id=? AND last_activity < ?", [.int(ms(at)), .text(chatID), .int(ms(at))])
    }

    /// An incoming or own message: adds `unreadDelta` to `unread` and raises `last_activity` to `at` when that is
    /// later, in one statement that touches no other column.
    public func bumpChat(chatID: String, unreadDelta: Int, at: Date) throws {
        try db.execute("UPDATE chats SET unread = unread + ?, last_activity = MAX(last_activity, ?) WHERE id=?",
                       [.int(Int64(unreadDelta)), .int(ms(at)), .text(chatID)])
    }

    /// Lowers `unread` by `n`, not below zero, in one statement.
    public func reduceUnread(chatID: String, by n: Int) throws {
        try db.execute("UPDATE chats SET unread = MAX(0, unread - ?) WHERE id=?", [.int(Int64(n)), .text(chatID)])
    }

    // MARK: drafts

    /// Saves the unsent text of a chat; blank text deletes it. One row per chat. `false` when the chat has no row
    /// (the draft cannot hang on anything yet): the caller keeps it and writes it when the row appears.
    @discardableResult
    public func setDraft(chatID: String, _ text: String, at: Date) throws -> Bool {
        try db.transaction { d in
            guard try !d.query("SELECT 1 FROM chats WHERE id=?", [.text(chatID)], row: { $0.int(0) }).isEmpty else { return false }
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                try d.execute("DELETE FROM drafts WHERE chat_id=?", [.text(chatID)])
            } else {
                try d.execute("""
                    INSERT INTO drafts(chat_id, text, updated_at) VALUES (?,?,?)
                    ON CONFLICT(chat_id) DO UPDATE SET text=excluded.text, updated_at=excluded.updated_at
                    """, [.text(chatID), .text(text), .int(ms(at))])
            }
            return true
        }
    }

    public func drafts() throws -> [String: String] {
        Dictionary(try db.query("SELECT chat_id, text FROM drafts") { ($0.text(0), $0.text(1)) }, uniquingKeysWith: { $1 })
    }

    /// Deletes the chat and everything under it (outbox rows have no foreign key and are removed explicitly).
    public func deleteChat(id: String) throws {
        try db.transaction { d in
            try d.execute("DELETE FROM outbox WHERE chat_id=?", [.text(id)])
            try d.execute("DELETE FROM chats WHERE id=?", [.text(id)])
        }
    }

    // MARK: conversations

    private let convCols = "server_id, chat_id, group_id, mode, inner_mode, mode_mismatch, epoch, active, created_at"
    private func convRow(_ r: Row) -> ConversationRecord {
        ConversationRecord(serverID: r.text(0), chatID: r.text(1), groupID: r.blob(2), mode: r.text(3),
                           innerMode: r.isNull(4) ? nil : r.text(4), modeMismatch: r.int(5) != 0, epoch: r.int(6),
                           active: r.int(7) != 0, createdAt: date(r.int(8)))
    }

    /// Inserts or updates the conversation. `inner_mode` and `mode_mismatch` are never overwritten here;
    /// use `pinInnerMode` and `flagModeMismatch`.
    public func upsertConversation(_ c: ConversationRecord) throws {
        try db.execute("""
            INSERT INTO conversations(server_id, chat_id, group_id, mode, inner_mode, mode_mismatch, epoch, active, created_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON CONFLICT(server_id) DO UPDATE SET chat_id=excluded.chat_id, group_id=excluded.group_id, mode=excluded.mode,
            epoch=excluded.epoch, active=excluded.active
            """, [.text(c.serverID), .text(c.chatID), .blob(c.groupID), .text(c.mode), opt(c.innerMode),
                  b(c.modeMismatch), .int(c.epoch), b(c.active), .int(ms(c.createdAt))])
    }

    public func conversations(chatID: String) throws -> [ConversationRecord] {
        try db.query("SELECT \(convCols) FROM conversations WHERE chat_id=? ORDER BY server_id", [.text(chatID)], row: convRow)
    }

    public func conversation(serverID: String) throws -> ConversationRecord? {
        try db.query("SELECT \(convCols) FROM conversations WHERE server_id=?", [.text(serverID)], row: convRow).first
    }

    public func conversation(groupID: Data) throws -> ConversationRecord? {
        try db.query("SELECT \(convCols) FROM conversations WHERE group_id=?", [.blob(groupID)], row: convRow).first
    }

    /// The active conversation with the smallest server ID (Design 1).
    public func preferredConversation(chatID: String) throws -> ConversationRecord? {
        try db.query("SELECT \(convCols) FROM conversations WHERE chat_id=? AND active=1 ORDER BY server_id LIMIT 1",
                     [.text(chatID)], row: convRow).first
    }

    public func setEpoch(serverID: String, _ epoch: Int64) throws {
        try db.execute("UPDATE conversations SET epoch=? WHERE server_id=?", [.int(epoch), .text(serverID)])
    }

    /// Pins `inner_mode` only when it is NULL. A different pinned value is kept, `mode_mismatch` is set and
    /// `false` is returned. Re-pinning the same value returns `true`.
    @discardableResult
    public func pinInnerMode(serverID: String, mode: String) throws -> Bool {
        try db.transaction { d in
            let current = try d.query("SELECT inner_mode FROM conversations WHERE server_id=?", [.text(serverID)]) {
                $0.isNull(0) ? nil : $0.text(0)
            }.first ?? nil
            if let current {
                if current == mode { return true }
                try d.execute("UPDATE conversations SET mode_mismatch=1 WHERE server_id=?", [.text(serverID)])
                return false
            }
            try d.execute("UPDATE conversations SET inner_mode=? WHERE server_id=?", [.text(mode), .text(serverID)])
            return true
        }
    }

    public func setServerMode(serverID: String, _ mode: String) throws {
        try db.execute("UPDATE conversations SET mode=? WHERE server_id=?", [.text(mode), .text(serverID)])
    }

    public func flagModeMismatch(serverID: String) throws {
        try db.execute("UPDATE conversations SET mode_mismatch=1 WHERE server_id=?", [.text(serverID)])
    }

    // MARK: members

    public func members(conversationID: String) throws -> [(identity: String, signatureKey: Data)] {
        try db.query("SELECT identity, signature_key FROM members WHERE conversation_id=? ORDER BY identity",
                     [.text(conversationID)]) { (identity: $0.text(0), signatureKey: $0.blob(1)) }
    }

    public func replaceMembers(conversationID: String, _ members: [(identity: String, signatureKey: Data)]) throws {
        try db.transaction { d in
            try d.execute("DELETE FROM members WHERE conversation_id=?", [.text(conversationID)])
            for m in members {
                try d.execute("INSERT OR REPLACE INTO members(conversation_id, identity, signature_key) VALUES (?,?,?)",
                              [.text(conversationID), .text(m.identity), .blob(m.signatureKey)])
            }
        }
    }

    // MARK: safety

    public func safety(chatID: String) throws -> (current: String, verified: String?)? {
        try db.query("SELECT current, verified FROM safety WHERE chat_id=?", [.text(chatID)]) {
            (current: $0.text(0), verified: $0.isNull(1) ? nil : $0.text(1))
        }.first
    }

    /// Sets the current number; the verified number is kept.
    public func setSafetyCurrent(chatID: String, _ current: String) throws {
        try db.execute("""
            INSERT INTO safety(chat_id, current, verified) VALUES (?,?,NULL)
            ON CONFLICT(chat_id) DO UPDATE SET current=excluded.current
            """, [.text(chatID), .text(current)])
    }

    public func setVerified(chatID: String, _ verified: String?) throws {
        try db.execute("UPDATE safety SET verified=? WHERE chat_id=?", [opt(verified), .text(chatID)])
    }

    // MARK: messages

    private let msgCols = """
        id, chat_id, conversation_id, sender_account, sender_device, sent_at, received_at, envelope, status, reply_to, \
        edited_at, deleted, undecryptable, folded, expires_at, caption, link_preview
        """

    /// SQL that hides a message once its time has come, before the sweep has removed it.
    private var notExpired: String { "(expires_at IS NULL OR expires_at > \(ms(now())))" }
    private func msgRow(_ r: Row) -> MessageRecord {
        MessageRecord(id: r.text(0), chatID: r.text(1), conversationID: r.isNull(2) ? nil : r.text(2),
                      senderAccount: r.text(3), senderDevice: r.isNull(4) ? nil : r.text(4),
                      sentAt: date(r.int(5)), receivedAt: date(r.int(6)), envelope: r.blob(7), status: r.text(8),
                      replyTo: r.isNull(9) ? nil : r.text(9), editedAt: r.isNull(10) ? nil : date(r.int(10)),
                      deleted: r.int(11) != 0, undecryptable: r.int(12) != 0, folded: r.text(13),
                      expiresAt: r.isNull(14) ? nil : date(r.int(14)), caption: r.isNull(15) ? nil : r.text(15),
                      linkPreview: r.isNull(16) ? nil : r.text(16))
    }

    /// Returns `true` when inserted, `false` when the ID already exists.
    @discardableResult
    public func insertMessage(_ m: MessageRecord) throws -> Bool {
        try db.run("""
            INSERT OR IGNORE INTO messages(id, chat_id, conversation_id, sender_account, sender_device, sent_at, received_at,
            envelope, status, reply_to, edited_at, deleted, undecryptable, folded, expires_at, caption, link_preview)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, [.text(m.id), .text(m.chatID), opt(m.conversationID), .text(m.senderAccount), opt(m.senderDevice),
                  .int(ms(m.sentAt)), .int(ms(m.receivedAt)), .blob(m.envelope), .text(m.status), opt(m.replyTo),
                  m.editedAt.map { .int(ms($0)) } ?? .null, b(m.deleted), b(m.undecryptable), .text(m.folded),
                  m.expiresAt.map { .int(ms($0)) } ?? .null, opt(m.caption), opt(m.linkPreview)]) > 0
    }

    public func message(id: String) throws -> MessageRecord? {
        try db.query("SELECT \(msgCols) FROM messages WHERE id=?", [.text(id)], row: msgRow).first
    }

    /// The newest `limit` messages older than `before` (all when nil), returned oldest first.
    public func messages(chatID: String, before: MessageRecord?, limit: Int) throws -> [MessageRecord] {
        var sql = "SELECT \(msgCols) FROM messages WHERE chat_id=? AND \(notExpired)"
        var args: [SQLValue] = [.text(chatID)]
        if let before {
            sql += " AND (sent_at < ? OR (sent_at = ? AND id < ?))"
            args += [.int(ms(before.sentAt)), .int(ms(before.sentAt)), .text(before.id)]
        }
        sql += " ORDER BY sent_at DESC, id DESC LIMIT ?"
        args.append(.int(Int64(limit)))
        return try db.query(sql, args, row: msgRow).reversed()
    }

    /// The chat's photo and file messages (voice notes and deleted messages excluded), newest first, `limit` of them
    /// older than `before` (all when nil). One query, so a gallery never reads the whole chat.
    public func attachmentMessages(chatID: String, before: MessageRecord?, limit: Int) throws -> [MessageRecord] {
        var sql = """
            SELECT \(msgCols) FROM messages WHERE chat_id=? AND deleted=0 AND \(notExpired)
            AND EXISTS (SELECT 1 FROM attachments a WHERE a.message_id = messages.id AND a.kind IN ('photo','file'))
            """
        var args: [SQLValue] = [.text(chatID)]
        if let before {
            sql += " AND (sent_at < ? OR (sent_at = ? AND id < ?))"
            args += [.int(ms(before.sentAt)), .int(ms(before.sentAt)), .text(before.id)]
        }
        sql += " ORDER BY sent_at DESC, id DESC LIMIT ?"
        args.append(.int(Int64(limit)))
        return try db.query(sql, args, row: msgRow)
    }

    /// Never lowers a status along sending < sent < delivered < read. Other targets (e.g. "failed") are set unconditionally.
    public func updateStatus(ids: [String], to status: String) throws {
        guard !ids.isEmpty else { return }
        try db.transaction { d in
            for start in stride(from: 0, to: ids.count, by: 500) {
                let chunk = Array(ids[start..<min(start + 500, ids.count)])
                let marks = Array(repeating: "?", count: chunk.count).joined(separator: ",")
                var args: [SQLValue] = [.text(status)] + chunk.map { .text($0) }
                var sql = "UPDATE messages SET status=? WHERE id IN (\(marks))"
                if let rank = statusRank[status] {
                    sql += " AND CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 WHEN 'delivered' THEN 2 WHEN 'read' THEN 3 ELSE -1 END < ?"
                    args.append(.int(Int64(rank)))
                }
                try d.execute(sql, args)
            }
        }
    }

    /// Replaces the envelope and folded text and sets `edited_at`, only when `sender` is the message's sender.
    @discardableResult
    public func applyEdit(id: String, sender: String, envelope: Data, folded: String, at: Date) throws -> Bool {
        try db.run("UPDATE messages SET envelope=?, folded=?, edited_at=? WHERE id=? AND sender_account=? AND deleted=0",
                   [.blob(envelope), .text(folded), .int(ms(at)), .text(id), .text(sender)]) > 0
    }

    /// Marks the message deleted and drops its content (envelope and search text), only when `sender` matches.
    @discardableResult
    public func markDeleted(id: String, sender: String) throws -> Bool {
        try db.run("UPDATE messages SET deleted=1, envelope=x'', folded='' WHERE id=? AND sender_account=?",
                   [.text(id), .text(sender)]) > 0
    }

    /// One reaction per account per message; an empty emoji removes it.
    public func setReaction(messageID: String, accountID: String, emoji: String) throws {
        if emoji.isEmpty {
            try db.execute("DELETE FROM reactions WHERE message_id=? AND account_id=?", [.text(messageID), .text(accountID)])
        } else {
            try db.execute("INSERT OR REPLACE INTO reactions(message_id, account_id, emoji) VALUES (?,?,?)",
                           [.text(messageID), .text(accountID), .text(emoji)])
        }
    }

    public func reactions(messageID: String) throws -> [ReactionRecord] {
        try db.query("SELECT message_id, account_id, emoji FROM reactions WHERE message_id=? ORDER BY account_id",
                     [.text(messageID)]) { ReactionRecord(messageID: $0.text(0), accountID: $0.text(1), emoji: $0.text(2)) }
    }

    // MARK: search

    /// Searches folded text. Queries under 3 characters use LIKE; longer ones use the trigram index.
    /// With `chatID`, only that chat's messages are searched, so its hits are never crowded out by newer hits elsewhere.
    public func search(_ folded: String, chatID: String? = nil, limit: Int) throws -> [(chatID: String, messageID: String)] {
        let q = fold(folded)
        guard !q.isEmpty else { return [] }
        let scope: SQLValue = chatID.map { .text($0) } ?? .null
        if q.unicodeScalars.count < 3 {
            let escaped = q.replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "%", with: "\\%").replacingOccurrences(of: "_", with: "\\_")
            return try db.query("""
                SELECT chat_id, id FROM messages WHERE deleted=0 AND \(notExpired) AND folded LIKE ? ESCAPE '\\'
                AND (? IS NULL OR chat_id = ?)
                ORDER BY sent_at DESC LIMIT ?
                """, [.text("%" + escaped + "%"), scope, scope, .int(Int64(limit))]) { (chatID: $0.text(0), messageID: $0.text(1)) }
        }
        let phrase = "\"" + q.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        return try db.query("""
            SELECT m.chat_id, m.id FROM message_fts JOIN messages m ON m.rowid = message_fts.rowid
            WHERE message_fts MATCH ? AND m.deleted=0 AND (m.expires_at IS NULL OR m.expires_at > \(ms(now()))) AND (? IS NULL OR m.chat_id = ?)
            ORDER BY m.sent_at DESC LIMIT ?
            """, [.text(phrase), scope, scope, .int(Int64(limit))]) { (chatID: $0.text(0), messageID: $0.text(1)) }
    }

    // MARK: attachments

    private let attCols = "id, message_id, kind, key, digest, size, cipher_size, state, downloaded, local_path"
    private func attRow(_ r: Row) -> AttachmentRecord {
        AttachmentRecord(id: r.text(0), messageID: r.text(1), kind: r.text(2), key: r.blob(3), digest: r.blob(4),
                         size: r.int(5), cipherSize: r.int(6), state: r.text(7), downloaded: r.int(8),
                         localPath: r.isNull(9) ? nil : r.text(9))
    }

    public func upsertAttachment(_ a: AttachmentRecord) throws {
        try db.execute("""
            INSERT INTO attachments(\(attCols)) VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET message_id=excluded.message_id, kind=excluded.kind, key=excluded.key,
            digest=excluded.digest, size=excluded.size, cipher_size=excluded.cipher_size, state=excluded.state,
            downloaded=excluded.downloaded, local_path=excluded.local_path
            """, [.text(a.id), .text(a.messageID), .text(a.kind), .blob(a.key), .blob(a.digest), .int(a.size),
                  .int(a.cipherSize), .text(a.state), .int(a.downloaded), opt(a.localPath)])
    }

    public func attachment(id: String) throws -> AttachmentRecord? {
        try db.query("SELECT \(attCols) FROM attachments WHERE id=?", [.text(id)], row: attRow).first
    }

    public func attachment(messageID: String) throws -> AttachmentRecord? {
        try db.query("SELECT \(attCols) FROM attachments WHERE message_id=? ORDER BY id LIMIT 1", [.text(messageID)], row: attRow).first
    }

    public func setAttachmentState(id: String, state: String, downloaded: Int64, localPath: String?) throws {
        try db.execute("UPDATE attachments SET state=?, downloaded=?, local_path=? WHERE id=?",
                       [.text(state), .int(downloaded), opt(localPath), .text(id)])
    }

    // MARK: outbox

    private func outRow(_ r: Row) -> OutboxRecord {
        OutboxRecord(messageID: r.text(0), chatID: r.text(1), envelope: r.blob(2), attempts: Int(r.int(3)), nextAttemptAt: date(r.int(4)))
    }

    public func enqueue(_ o: OutboxRecord) throws {
        try db.execute("INSERT OR REPLACE INTO outbox(message_id, chat_id, envelope, attempts, next_attempt_at) VALUES (?,?,?,?,?)",
                       [.text(o.messageID), .text(o.chatID), .blob(o.envelope), .int(Int64(o.attempts)), .int(ms(o.nextAttemptAt))])
    }

    /// Stores a message and its outbox row in one transaction.
    public func enqueueSend(message: MessageRecord, outbox: OutboxRecord) throws {
        try db.transaction { _ in
            try insertMessage(message)
            try enqueue(outbox)
        }
    }

    public func dueOutbox(at: Date) throws -> [OutboxRecord] {
        try db.query("""
            SELECT message_id, chat_id, envelope, attempts, next_attempt_at FROM outbox
            WHERE next_attempt_at <= ? ORDER BY next_attempt_at, message_id
            """, [.int(ms(at))], row: outRow)
    }

    /// The oldest row of each chat (by enqueue order), when it is due. A newer message never overtakes an
    /// older one that is waiting or backing off. Oldest chat head first.
    public func dueOutboxHeads(at: Date) throws -> [OutboxRecord] {
        try db.query("""
            SELECT message_id, chat_id, envelope, attempts, next_attempt_at FROM outbox o
            WHERE o.rowid = (SELECT MIN(rowid) FROM outbox WHERE chat_id = o.chat_id) AND next_attempt_at <= ?
            ORDER BY o.rowid
            """, [.int(ms(at))], row: outRow)
    }

    /// Sets the message status and deletes its outbox row in one transaction.
    public func completeSend(id: String, status: String) throws {
        try db.transaction { d in
            try updateStatus(ids: [id], to: status)
            try d.execute("DELETE FROM outbox WHERE message_id=?", [.text(id)])
        }
    }

    /// Manual retry of a failed message: status back to `sending`, enqueue time reset, and a new outbox row at the
    /// END of the chat's queue. Returns false when the message does not exist or is not a failed one.
    @discardableResult
    public func requeueSend(id: String) throws -> Bool {
        try db.transaction { d in
            // Only a failed message can be tried again: a second tap on a message already queued, sent or deleted does nothing.
            guard let m = try message(id: id), m.status == "failed", !m.deleted else { return false }
            try d.execute("UPDATE messages SET status='sending', received_at=? WHERE id=?", [.int(ms(now())), .text(id)])
            try enqueue(OutboxRecord(messageID: id, chatID: m.chatID, envelope: m.envelope, attempts: 0, nextAttemptAt: now()))
            return true
        }
    }

    // MARK: unsent messages (a picked attachment is shown while its upload is still to do)

    /// Puts the real envelope into an own message that has not been sent. False when the row is gone, deleted,
    /// someone else's, or already sent.
    @discardableResult
    public func replaceUnsentEnvelope(id: String, sender: String, envelope: Data, folded: String) throws -> Bool {
        try db.run("""
            UPDATE messages SET envelope=?, folded=? WHERE id=? AND sender_account=? AND deleted=0
            AND status IN ('sending','failed')
            """, [.blob(envelope), .text(folded), .text(id), .text(sender)]) > 0
    }

    /// Removes an own message that has not been sent, with its outbox row and attachment rows.
    @discardableResult
    public func deleteUnsentMessage(id: String, sender: String) throws -> Bool {
        try db.transaction { d in
            guard let m = try message(id: id), m.senderAccount == sender, m.status == "sending" || m.status == "failed" else { return false }
            try d.execute("DELETE FROM outbox WHERE message_id=?", [.text(id)])
            try d.execute("DELETE FROM attachments WHERE message_id=?", [.text(id)])
            try d.execute("DELETE FROM messages WHERE id=?", [.text(id)])
            return true
        }
    }

    /// failed -> sending, without queueing (the upload comes first). False when it is not a failed message.
    @discardableResult
    public func resumeUnsent(id: String) throws -> Bool {
        try db.run("UPDATE messages SET status='sending', received_at=? WHERE id=? AND status='failed' AND deleted=0",
                   [.int(ms(now())), .text(id)]) > 0
    }

    /// sending -> failed. False when it is not a sending message.
    @discardableResult
    public func markUnsentFailed(id: String) throws -> Bool {
        try db.run("UPDATE messages SET status='failed' WHERE id=? AND status='sending'", [.text(id)]) > 0
    }

    public func deleteAttachment(id: String) throws {
        try db.execute("DELETE FROM attachments WHERE id=?", [.text(id)])
    }

    public func bumpOutbox(id: String, nextAttempt: Date) throws {
        try db.execute("UPDATE outbox SET attempts=attempts+1, next_attempt_at=? WHERE message_id=?", [.int(ms(nextAttempt)), .text(id)])
    }

    public func removeOutbox(id: String) throws {
        try db.execute("DELETE FROM outbox WHERE message_id=?", [.text(id)])
    }

    // MARK: raw inbox

    public func insertRaw(_ items: [RawInboxRecord]) throws {
        try db.transaction { d in
            for r in items {
                try d.execute("""
                    INSERT OR IGNORE INTO inbox_raw(cursor, kind, conversation_id, seq, epoch, sender_account, sender_device, payload, sent_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, [.int(r.cursor), .text(r.kind), .text(r.conversationID), r.seq.map { .int($0) } ?? .null, .int(r.epoch),
                          .text(r.senderAccount), .text(r.senderDevice), .blob(r.payload), .int(ms(r.sentAt))])
            }
        }
    }

    public func rawItems(limit: Int) throws -> [RawInboxRecord] {
        try db.query("""
            SELECT cursor, kind, conversation_id, seq, epoch, sender_account, sender_device, payload, sent_at
            FROM inbox_raw ORDER BY cursor LIMIT ?
            """, [.int(Int64(limit))]) {
            RawInboxRecord(cursor: $0.int(0), kind: $0.text(1), conversationID: $0.text(2), seq: $0.isNull(3) ? nil : $0.int(3),
                           epoch: $0.int(4), senderAccount: $0.text(5), senderDevice: $0.text(6), payload: $0.blob(7),
                           sentAt: date($0.int(8)))
        }
    }

    public func deleteRaw(cursor: Int64) throws {
        try db.execute("DELETE FROM inbox_raw WHERE cursor=?", [.int(cursor)])
    }

    // MARK: processed

    public func markProcessed(hash: Data) throws {
        try db.execute("INSERT OR IGNORE INTO processed(payload_hash, processed_at) VALUES (?,?)", [.blob(hash), .int(ms(now()))])
    }

    public func isProcessed(hash: Data) throws -> Bool {
        try !db.query("SELECT 1 FROM processed WHERE payload_hash=?", [.blob(hash)]) { $0.int(0) }.isEmpty
    }

    public func pruneProcessed(olderThan: Date) throws {
        try db.execute("DELETE FROM processed WHERE processed_at < ?", [.int(ms(olderThan))])
    }

    // MARK: folders

    public func folders() throws -> [FolderRecord] {
        try db.query("SELECT id, name, position FROM folders ORDER BY position, id") {
            FolderRecord(id: $0.text(0), name: $0.text(1), position: Int($0.int(2)))
        }
    }

    public func addFolder(_ f: FolderRecord) throws {
        try db.execute("INSERT INTO folders(id, name, position) VALUES (?,?,?)", [.text(f.id), .text(f.name), .int(Int64(f.position))])
    }

    public func renameFolder(id: String, name: String) throws {
        try db.execute("UPDATE folders SET name=? WHERE id=?", [.text(name), .text(id)])
    }

    public func deleteFolder(id: String) throws {
        try db.execute("DELETE FROM folders WHERE id=?", [.text(id)])
    }

    /// Moves the folder to `index` and renumbers all positions 0..<n.
    public func moveFolder(id: String, to index: Int) throws {
        try db.transaction { d in
            var ids = try d.query("SELECT id FROM folders ORDER BY position, id") { $0.text(0) }
            guard let from = ids.firstIndex(of: id) else { return }
            ids.remove(at: from)
            ids.insert(id, at: max(0, min(index, ids.count)))
            for (i, fid) in ids.enumerated() {
                try d.execute("UPDATE folders SET position=? WHERE id=?", [.int(Int64(i)), .text(fid)])
            }
        }
    }

    public func setFolderChats(folderID: String, chatIDs: [String]) throws {
        try db.transaction { d in
            try d.execute("DELETE FROM folder_chats WHERE folder_id=?", [.text(folderID)])
            for c in chatIDs {
                try d.execute("INSERT OR IGNORE INTO folder_chats(folder_id, chat_id) VALUES (?,?)", [.text(folderID), .text(c)])
            }
        }
    }

    public func folderChats(folderID: String) throws -> [String] {
        try db.query("SELECT chat_id FROM folder_chats WHERE folder_id=? ORDER BY chat_id", [.text(folderID)]) { $0.text(0) }
    }

    // MARK: blocks

    public func block(_ accountID: String) throws {
        try db.execute("INSERT OR IGNORE INTO blocked(account_id, blocked_at) VALUES (?,?)", [.text(accountID), .int(ms(now()))])
    }

    public func unblock(_ accountID: String) throws {
        try db.execute("DELETE FROM blocked WHERE account_id=?", [.text(accountID)])
    }

    public func isBlocked(_ accountID: String) throws -> Bool {
        try !db.query("SELECT 1 FROM blocked WHERE account_id=?", [.text(accountID)]) { $0.int(0) }.isEmpty
    }

    public func blockedIDs() throws -> [String] {
        try db.query("SELECT account_id FROM blocked ORDER BY account_id") { $0.text(0) }
    }

    // MARK: calls

    public func insertCall(_ c: CallRecord) throws {
        try db.execute("INSERT OR REPLACE INTO calls(id, chat_id, video, outgoing, outcome, started_at, duration) VALUES (?,?,?,?,?,?,?)",
                       [.text(c.id), .text(c.chatID), b(c.video), b(c.outgoing), .text(c.outcome), .int(ms(c.startedAt)), .double(c.duration)])
    }

    public func calls(limit: Int) throws -> [CallRecord] {
        try db.query("""
            SELECT id, chat_id, video, outgoing, outcome, started_at, duration FROM calls
            ORDER BY started_at DESC, id DESC LIMIT ?
            """, [.int(Int64(limit))]) {
            CallRecord(id: $0.text(0), chatID: $0.text(1), video: $0.int(2) != 0, outgoing: $0.int(3) != 0,
                       outcome: $0.text(4), startedAt: date($0.int(5)), duration: $0.double(6))
        }
    }

    // MARK: settings

    public func get(_ key: String) throws -> Data? {
        try db.query("SELECT value FROM kv WHERE key=?", [.text(key)]) { $0.blob(0) }.first
    }

    public func set(_ key: String, _ value: Data) throws {
        try db.execute("INSERT OR REPLACE INTO kv(key, value) VALUES (?,?)", [.text(key), .blob(value)])
    }

    /// Every `kv` entry whose key starts with `prefix` (used for the link snapshot's settings).
    public func entries(prefix: String) throws -> [String: Data] {
        let rows = try db.query("SELECT key, value FROM kv WHERE substr(key, 1, ?) = ?", [.int(Int64(prefix.utf8.count)), .text(prefix)]) {
            ($0.text(0), $0.blob(1))
        }
        return Dictionary(rows, uniquingKeysWith: { $1 })
    }

    // MARK: wipe

    /// Deletes every row in every table (sign-out / reset).
    public func wipe() throws {
        try db.transaction { d in
            for table in ["messages", "drafts", "chats", "contacts", "outbox", "inbox_raw", "processed", "folders", "blocked", "profiles", "kv"] {
                try d.execute("DELETE FROM \(table)")
            }
        }
        // Drop deleted content from the WAL file as well.
        try db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    }
}
