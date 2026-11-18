import AppCore
import Foundation
import LocalStore

/// Chat lines about a group's people, name and photo, and what happens to a chat when I am no longer in the group.
/// Database only and synchronous, so callers run it inside their own transaction.
enum GroupLines {
    /// One line, once: `id` decides, so a repeat (a replay, a second device of mine, a retry) adds nothing.
    static func insert(_ line: GroupSystemLine, id: String, chatID: String, conversationID: String, at: Date, db: Repository) throws {
        let envelope = (try? JSONEncoder().encode(SystemEvent.group(line))) ?? Data()
        _ = try db.insertMessage(MessageRecord(
            id: id, chatID: chatID, conversationID: conversationID, senderAccount: ChatMapping.systemSender, senderDevice: nil,
            sentAt: at, receivedAt: at, envelope: envelope, status: "sent", replyTo: nil, editedAt: nil, deleted: false,
            undecryptable: false, folded: ""))
    }

    /// The key the tie rule compares: the name and the photo's SHA-256 in lowercase hex.
    static func infoKey(name: String, photo: Data?) -> GroupInfoKey {
        GroupInfoKey(name: name, photoHash: photo.map { PortableSHA256.hash($0).map { String(format: "%02x", $0) }.joined() })
    }

    /// The part of a line's ID that says which account it is about, so two lines of one change never share an ID.
    static func key(_ line: GroupSystemLine) -> String {
        switch line {
        case .added(_, let who): "added-\(who)"
        case .removed(_, let who): "removed-\(who)"
        case .left(let who): "left-\(who)"
        case .youWereRemoved: "me-removed"
        case .madeAdmin(let who): "admin-\(who)"
        case .noLongerAdmin(let who): "member-\(who)"
        case .nameChanged: "name"
        case .photoChanged: "photo"
        case .youAdded, .youRemoved, .youLeft, .youChangedName, .youChangedPhoto: "you"   // made only when shown, never stored
        }
    }

    /// The lines of one member-list change, written once under IDs that carry the list version they came from.
    static func writeMemberLines(
        old: [GroupMemberRecord], new: [GroupMemberRecord], actor: String?, me: String, version: Int64, chatID: String,
        conversationID: String, at: Date, db: Repository
    ) throws {
        func members(_ list: [GroupMemberRecord]) -> [GroupMember] {
            list.map { GroupMember(accountID: $0.accountID, role: GroupRole(wire: $0.role)) }
        }
        let lines = MemberDiff.lines(old: members(old), new: members(new), actor: actor, me: me)
        for (index, line) in lines.enumerated() {
            try insert(line, id: "sys-gm-\(conversationID)-\(version)-\(key(line))", chatID: chatID, conversationID: conversationID,
                       at: at.addingTimeInterval(Double(index) / 1000), db: db)
        }
    }

    /// I am no longer in the group (`removed` or `left`): the chat stays, read-only, with its history and member list.
    /// The conversation rows go, so a later Welcome for the same conversation is a fresh join, and what is still queued
    /// for the chat is marked not sent. One transaction. A chat I am already out of is left alone (the line is written once).
    /// `removed` adds the line "You were removed"; leaving adds none.
    static func iAmOut(chatID: String, state: String, at: Date, db: Repository) throws {
        try db.transaction {
            guard let chat = try db.chat(id: chatID), chat.kind == "group", chat.myState == "member",
                  let conversationID = ChatID.conversationID(chatID) else { return }
            _ = try db.setMyState(chatID: chatID, state)
            try db.deleteConversations(groupChatID: chatID)
            try db.failQueuedSends(chatID: chatID)
            if state == "removed" {
                let ms = Int64((at.timeIntervalSince1970 * 1000).rounded())
                try insert(.youWereRemoved, id: "sys-gone-\(conversationID)-\(ms)", chatID: chatID, conversationID: conversationID, at: at, db: db)
            }
        }
    }
}

/// What a group needs from the server's list, as a pure decision: which MLS members to take out and which accounts have
/// no device in the group yet.
enum GroupSyncPlan {
    struct Plan: Equatable {
        /// MLS member identities (`"<account>/<device>"`) of accounts that are not in the list.
        var removeIdentities: [String]
        /// Listed accounts (not me) none of whose devices is in the MLS group.
        var addAccounts: [String]
    }

    static func account(of identity: String) -> String {
        identity.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? identity
    }

    static func plan(listed: Set<String>, mlsIdentities: [String], me: String) -> Plan {
        let inGroup = Set(mlsIdentities.map(account(of:)))
        return Plan(
            removeIdentities: mlsIdentities.filter { !listed.contains(account(of: $0)) }.sorted(),
            addAccounts: listed.filter { $0 != me && !inGroup.contains($0) }.sorted())
    }
}
