import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit

/// What `IncomingHandler.apply` did with one processed item.
public enum AppliedResult: Sendable, Equatable {
    /// A new message row (also a message shown as "Update [App] to see this message").
    case stored(MessageRecord)
    /// A call signal. It is never stored; `sender` is the MLS sender `"<accountID>/<deviceID>"`.
    case callSignal(CallSignalPayload, sender: String)
    /// A Welcome. The inbox processor stores the conversation itself (`ConversationManager.joined`).
    case joined(group: GroupID, epoch: UInt64)
    /// A commit by another device was applied to the conversation.
    case commit(conversationID: String, newEpoch: UInt64)
    /// A commit (or a re-sent Welcome) added devices of my own account. The device lines and the safety number
    /// are already written; the caller shows the "New device added" notice once per device ID, after the
    /// process lock is released.
    case ownDeviceAdded(deviceIDs: [String])
    /// A commit removed this device; the conversation is now inactive.
    case removedMe(conversationID: String)
    /// Applied without a new message (edit, delete, reaction, receipt, mode) or dropped.
    case ignored
}

/// Applies one processed MLS result to the database. Shared by the inbox processor (app) and the
/// notification processor (NSE, D2). Everything is synchronous and database-only, so the caller can run
/// it in the same transaction as `markProcessed` and `deleteRaw`. It never logs message content.
public struct IncomingHandler: Sendable {
    public let me: (accountID: String, deviceID: String)
    let now: @Sendable () -> Date
    let log: @Sendable (String) -> Void
    private let membership: Membership

    public init(
        me: (accountID: String, deviceID: String), now: @escaping @Sendable () -> Date,
        log: @escaping @Sendable (String) -> Void = { FileHandle.standardError.write(Data(($0 + "\n").utf8)) }
    ) {
        self.me = me
        self.now = now
        self.log = log
        self.membership = Membership(me: me, now: now)
    }

    // MARK: Echoed own commits (Design 17)

    /// An accepted commit of this device comes back as an inbox item. When the stored epoch is already
    /// past it, there is nothing left to merge and the item is skipped. Otherwise (a crash between the
    /// server's 200 and `commitAccepted`) it goes to `mls.process`, which merges the pending commit.
    public func isEchoedOwnCommit(_ raw: RawInboxRecord, db: Repository) throws -> Bool {
        guard raw.kind == "commit", raw.senderAccount == me.accountID, raw.senderDevice == me.deviceID,
              let conversation = try db.conversation(serverID: raw.conversationID) else { return false }
        return conversation.epoch >= raw.epoch + 1
    }

    // MARK: Apply

    /// - Parameter memberKeys: the group's members after a `.commit` or `.joined` (`Membership.keys(of:)`, read
    ///   under the process lock before the transaction). `nil` skips the device lines and the safety number;
    ///   the next update catches up, because it compares the whole stored state.
    public func apply(
        _ incoming: Incoming, raw: RawInboxRecord, db: Repository, memberKeys: [MemberKey]? = nil
    ) throws -> AppliedResult {
        switch incoming {
        case .joined(let group, let epoch):
            if let own = try updateMembership(memberKeys, raw: raw, db: db) { return own }
            return .joined(group: group, epoch: epoch)
        case .commit(let group, let newEpoch, let removedMe):
            guard var conversation = try db.conversation(serverID: raw.conversationID) else { return .ignored }
            if removedMe {
                conversation.active = false
                try db.upsertConversation(conversation)
                return .removedMe(conversationID: conversation.serverID)
            }
            try db.setEpoch(serverID: conversation.serverID, Int64(newEpoch))
            // The echo of my own add commit for a conversation I created: it is settled, so it becomes usable
            // (before the members, so the safety number follows it as the preferred conversation).
            // Matched by group, not by the server's label.
            if group.raw == conversation.groupID, try PendingCreate.isPending(serverID: conversation.serverID, db: db) {
                conversation.epoch = Int64(newEpoch)
                conversation.active = true
                try db.upsertConversation(conversation)
                try PendingCreate.clear(serverID: conversation.serverID, db: db)
            }
            if let own = try updateMembership(memberKeys, raw: raw, db: db) { return own }
            return .commit(conversationID: conversation.serverID, newEpoch: newEpoch)
        case .application(_, let sender, let plaintext, _):
            guard let conversation = try db.conversation(serverID: raw.conversationID) else { return .ignored }
            return try applyApplication(sender: sender, plaintext: plaintext, raw: raw, conversation: conversation, db: db)
        }
    }

    static let pendingNoticePrefix = "notice.pending."
    static func pendingNoticeKey(_ deviceID: String) -> String { pendingNoticePrefix + deviceID }

    /// Device lines and the safety number for the conversation of `raw` (Design 19, 20), after its epoch is stored.
    private func updateMembership(_ keys: [MemberKey]?, raw: RawInboxRecord, db: Repository) throws -> AppliedResult? {
        guard let keys, let conversation = try db.conversation(serverID: raw.conversationID) else { return nil }
        let change = try membership.apply(keys: keys, conversation: conversation, db: db)
        // Written in the caller's transaction, with the new members and the deleted raw item: the notice
        // survives a kill before it is shown. `InboxProcessor` clears the marker after posting it.
        for id in change.ownDevicesAdded { try db.set(Self.pendingNoticeKey(id), Data([1])) }
        return change.ownDevicesAdded.isEmpty ? nil : .ownDeviceAdded(deviceIDs: change.ownDevicesAdded)
    }

    /// An MLS failure: one placeholder row (`undecryptable = 1`, ID `undecryptable-<cursor>`) in the chat the
    /// server named. Returns `false` when that conversation is not known; the caller then `park`s the item.
    @discardableResult
    public func storePlaceholder(for raw: RawInboxRecord, db: Repository) throws -> Bool {
        guard let conversation = try db.conversation(serverID: raw.conversationID) else { return false }
        try insertPlaceholder(
            id: "undecryptable-\(raw.cursor)", kind: "undecryptable", chatID: conversation.chatID,
            conversationID: conversation.serverID, raw: raw, db: db)
        return true
    }

    /// A Welcome whose chat could not be set up (a permanent failure): one placeholder row (`joinFailed`, ID
    /// `join-failed-<cursor>`, `undecryptable = 1`) in the chat of the account the server names as the sender,
    /// created as a request when it is not a contact. Nothing for a Welcome the server says I sent. The item
    /// itself is `park`ed by the caller.
    public func storeJoinFailed(for raw: RawInboxRecord, db: Repository) throws {
        let chatID = raw.senderAccount
        guard chatID != me.accountID, !chatID.isEmpty else { return }
        if try db.chat(id: chatID) == nil {
            let isContact = try db.contact(id: chatID)?.isContact ?? false
            try db.upsertChat(ChatRecord(id: chatID, isRequest: !isContact, unread: 0, muted: false, lastActivity: now()))
        }
        try insertPlaceholder(id: "join-failed-\(raw.cursor)", kind: "joinFailed", chatID: chatID, conversationID: nil, raw: raw, db: db)
    }

    /// Keeps an item that cannot be applied (`ParkedItem`) and logs its IDs, never its content.
    public func park(_ raw: RawInboxRecord, reason: String, db: Repository) throws {
        try ParkedItem.save(raw, reason: reason, db: db)
        log("\(raw.kind == "welcome" ? "join_parked" : "item_parked") reason=\(reason) conversation=\(raw.conversationID) cursor=\(raw.cursor)")
    }

    private func insertPlaceholder(
        id: String, kind: String, chatID: String, conversationID: String?, raw: RawInboxRecord, db: Repository
    ) throws {
        let envelope = ContentEnvelope(id: id, at: Self.ms(raw.sentAt), body: .unsupported(kind: kind))
        let record = MessageRecord(
            id: id, chatID: chatID, conversationID: conversationID, senderAccount: raw.senderAccount,
            senderDevice: raw.senderDevice, sentAt: raw.sentAt, receivedAt: now(), envelope: envelope.encoded(),
            status: "delivered", replyTo: nil, editedAt: nil, deleted: false, undecryptable: true, folded: "")
        if try db.insertMessage(record) { try bumpChat(record, db: db) }
    }

    // MARK: Application messages

    private func applyApplication(
        sender: String, plaintext: Data, raw: RawInboxRecord, conversation: ConversationRecord, db: Repository
    ) throws -> AppliedResult {
        let parts = sender.split(separator: "/", maxSplits: 1).map(String.init)
        let account = parts.first ?? raw.senderAccount
        let device = parts.count > 1 ? parts[1] : raw.senderDevice
        let envelope = ContentEnvelope.decode(plaintext)
        let chatID = conversation.chatID

        func store(_ status: String? = nil, replyTo: String? = nil, folded: String = "", id: String? = nil) throws -> AppliedResult {
            let ownDevice = account == me.accountID
            let record = MessageRecord(
                id: id ?? (envelope.id.isEmpty ? "unsupported-\(raw.cursor)" : envelope.id), chatID: chatID,
                conversationID: conversation.serverID, senderAccount: account, senderDevice: device,
                sentAt: Date(timeIntervalSince1970: Double(envelope.at) / 1000), receivedAt: now(), envelope: plaintext,
                status: status ?? (ownDevice ? "sent" : "delivered"), replyTo: replyTo, editedAt: nil, deleted: false,
                undecryptable: false, folded: folded)
            guard try db.insertMessage(record) else { return .ignored }
            try bumpChat(record, db: db)
            return .stored(record)
        }

        func media(_ att: Att, kind: String, reply: String?, folded: String = "") throws -> AppliedResult {
            let result = try store(replyTo: reply, folded: folded)
            if case .stored(let record) = result {
                try db.upsertAttachment(AttachmentRecord(
                    id: att.id, messageID: record.id, kind: kind, key: att.key, digest: att.dg, size: att.sz,
                    cipherSize: att.csz, state: "remote", downloaded: 0, localPath: nil))
            }
            return result
        }

        switch envelope.body {
        case .text(let text, let reply):
            return try store(replyTo: reply, folded: fold(text))
        case .photo(let att, _, _, _, let reply):
            return try media(att, kind: "photo", reply: reply)
        case .voice(let att, _, let reply):
            return try media(att, kind: "voice", reply: reply)
        case .file(let att, let name, _, let reply):
            return try media(att, kind: "file", reply: reply, folded: fold(name))
        case .unsupported:
            return try store()
        case .edit(let target, let text):
            guard let message = try db.message(id: target), message.chatID == chatID, !message.deleted,
                  case .text(_, let reply) = ContentEnvelope.decode(message.envelope).body else { return .ignored }
            let old = ContentEnvelope.decode(message.envelope)
            let edited = ContentEnvelope(id: old.id, at: old.at, body: .text(text, replyTo: reply)).encoded()
            // The sender check is in the SQL, so an edit by anyone else changes nothing.
            try db.applyEdit(
                id: target, sender: account, envelope: edited, folded: fold(text),
                at: Date(timeIntervalSince1970: Double(envelope.at) / 1000))
            return .ignored
        case .delete(let target):
            guard let message = try db.message(id: target), message.chatID == chatID else { return .ignored }
            try db.markDeleted(id: target, sender: account)
            return .ignored
        case .react(let target, let emoji):
            guard let message = try db.message(id: target), message.chatID == chatID, !message.deleted else { return .ignored }
            try db.setReaction(messageID: message.id, accountID: account, emoji: emoji)
            return .ignored
        case .receipt(let kind, let ids):
            try applyReceipt(kind, ids: ids, from: account, chatID: chatID, db: db)
            return .ignored
        case .callSignal(let payload):
            return .callSignal(payload, sender: sender)
        case .mode(let mode):
            if try !db.pinInnerMode(serverID: conversation.serverID, mode: mode) {
                log("mode_mismatch conversation=\(conversation.serverID)")
                try addPausedLine(conversation, db: db)
            }
            return .ignored
        }
    }

    /// A receipt counts when it is about my own messages (from the peer), or when it comes from one of my
    /// own devices (read state in step across my devices).
    private func applyReceipt(_ kind: ReceiptKind, ids: [String], from account: String, chatID: String, db: Repository) throws {
        let fromMe = account == me.accountID
        var valid: [String] = []
        var unreadCleared = 0
        for id in ids {
            guard let message = try db.message(id: id), message.chatID == chatID else { continue }
            guard message.senderAccount == me.accountID || fromMe else { continue }
            valid.append(id)
            if fromMe, kind == .read, message.senderAccount != me.accountID, message.status != "read" { unreadCleared += 1 }
        }
        try db.updateStatus(ids: valid, to: kind.rawValue)
        if unreadCleared > 0, let chat = try db.chat(id: chatID) {
            try db.setUnread(chatID: chatID, max(0, chat.unread - unreadCleared))
        }
    }

    /// One system line per conversation ("Messages in this chat are paused"), never stored twice.
    /// The ID has no epoch part, so a later mismatch cannot add a second line.
    func addPausedLine(_ conversation: ConversationRecord, db: Repository) throws {
        let envelope = (try? JSONEncoder().encode(SystemEvent.messagesPaused)) ?? Data()
        try db.insertMessage(MessageRecord(
            id: "sys-\(conversation.serverID)-messagesPaused", chatID: conversation.chatID,
            conversationID: conversation.serverID, senderAccount: "system", senderDevice: nil, sentAt: now(),
            receivedAt: now(), envelope: envelope, status: "sent", replyTo: nil, editedAt: nil, deleted: false,
            undecryptable: false, folded: ""))
    }

    /// An incoming message from someone else counts as unread and moves the chat up.
    private func bumpChat(_ message: MessageRecord, db: Repository) throws {
        guard var chat = try db.chat(id: message.chatID) else { return }
        if message.senderAccount != me.accountID { chat.unread += 1 }
        chat.lastActivity = max(chat.lastActivity, min(message.sentAt, now()))
        try db.upsertChat(chat)
    }

    static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
}
