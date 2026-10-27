import AppCore
import Foundation
import LocalStore
import SecureMessagingCore

/// What a membership update changed, as full identities (`"<accountID>/<deviceID>"`) except
/// `ownDevicesAdded`, which holds the device IDs of my own account (never this device).
public struct MembershipChange: Equatable, Sendable {
    public var added: [String]
    public var removed: [String]
    public var ownDevicesAdded: [String]

    public init(added: [String] = [], removed: [String] = [], ownDevicesAdded: [String] = []) {
        self.added = added
        self.removed = removed
        self.ownDevicesAdded = ownDevicesAdded
    }
}

/// Device-change lines and safety numbers (Design 19, 20). After every processed commit, every
/// `commitAccepted` and every Welcome it compares the group's members with the stored `members` rows.
///
/// Reading the members is async (the MLS engine), writing them is database-only. The split lets the inbox
/// processor read under the process lock and write in the transaction that also marks the item processed:
/// `keys(of:)` first, then `apply`. `update` does both, for callers that are not in a transaction.
/// Callers hold the process lock around either.
struct Membership: Sendable {
    let me: (accountID: String, deviceID: String)
    let now: @Sendable () -> Date
    private let mls: MLSEngine?

    init(deps: MessagingDeps) {
        self.me = deps.me
        self.now = deps.now
        self.mls = deps.mls
    }

    /// Database-only use (`IncomingHandler.apply`): `keys(of:)` and `update` are not available.
    init(me: (accountID: String, deviceID: String), now: @escaping @Sendable () -> Date) {
        self.me = me
        self.now = now
        self.mls = nil
    }

    /// The group's members with their signature keys, sorted by identity.
    ///
    /// TODO(plan 4 memberKeys): `MLSEngine.memberKeys(of:)` does not exist yet. Until it lands this uses
    /// `members(of:)` with placeholder keys (`"fake-sig:<identity>"`, what `FakeMLSEngine` will return), so
    /// the device lines and the safety number's *shape* work, but the number does not yet cover the real
    /// keys. The safety-number UI must stay hidden until this is replaced.
    func keys(of group: GroupID) async throws -> [MemberKey] {
        guard let mls else { throw MLSError.internalError("Membership without an engine") }
        return try await mls.members(of: group)
            .map { MemberKey(identity: $0, signatureKey: Data("fake-sig:\($0)".utf8)) }
            .sorted { Array($0.identity.utf8).lexicographicallyPrecedes(Array($1.identity.utf8)) }
    }

    func update(conversation: ConversationRecord, db: Repository) async throws -> MembershipChange {
        let keys = try await keys(of: GroupID(raw: conversation.groupID))
        return try db.transaction { try apply(keys: keys, conversation: conversation, db: db) }
    }

    /// Database-only half. Run it inside the caller's transaction.
    func apply(keys: [MemberKey], conversation: ConversationRecord, db: Repository) throws -> MembershipChange {
        // The epoch in the line IDs is the stored one: callers store the new epoch first.
        let conversation = try db.conversation(serverID: conversation.serverID) ?? conversation
        let stored = try db.members(conversationID: conversation.serverID)
        let myIdentity = "\(me.accountID)/\(me.deviceID)"
        var change = MembershipChange()
        var lines: [(id: String, event: SystemEvent)] = []

        // No stored rows yet: the commit or Welcome that creates the conversation. Store, no lines.
        if !stored.isEmpty {
            let old = Set(stored.map(\.identity)), new = Set(keys.map(\.identity))
            func event(_ identity: String, added: Bool) -> SystemEvent {
                let account = String(SafetyNumberCalc.account(of: identity))
                // Others by account ID: the display name is resolved when the line is shown, never stored.
                let name: String? = SafetyNumberCalc.isAccount(identity, me.accountID) ? nil : account
                return added ? .deviceAdded(name: name) : .deviceRemoved(name: name)
            }
            for identity in new.subtracting(old).sorted() where identity != myIdentity {
                change.added.append(identity)
                lines.append(("add-\(identity)", event(identity, added: true)))
                if SafetyNumberCalc.isAccount(identity, me.accountID) {
                    change.ownDevicesAdded.append(String(identity.dropFirst(me.accountID.count + 1)))
                }
            }
            for identity in old.subtracting(new).sorted() where identity != myIdentity {
                change.removed.append(identity)
                lines.append(("remove-\(identity)", event(identity, added: false)))
            }
        }
        if stored.map(\.identity) != keys.map(\.identity) || stored.map(\.signatureKey) != keys.map(\.signatureKey) {
            try db.replaceMembers(conversationID: conversation.serverID, keys.map { ($0.identity, $0.signatureKey) })
        }

        // The chat's number follows its preferred conversation.
        if let preferred = try db.preferredConversation(chatID: conversation.chatID), preferred.serverID == conversation.serverID {
            // An oversized identity or key gives no number (the stored one stays); never a wrapped one.
            let number = try? SafetyNumberCalc.compute(accountA: me.accountID, accountB: conversation.chatID, members: keys).joined()
            let previous = try db.safety(chatID: conversation.chatID)?.current
            if let number, previous != number {
                try db.setSafetyCurrent(chatID: conversation.chatID, number)
                if previous != nil { lines.append(("safety", .safetyNumberChanged)) }
            }
        }

        // One millisecond apart, so the lines keep their order.
        for (index, line) in lines.enumerated() {
            try addLine(line.event, id: "sys-\(conversation.serverID)-\(conversation.epoch)-\(line.id)", conversation: conversation,
                        at: now().addingTimeInterval(Double(index) / 1000), db: db)
        }
        return change
    }

    private func addLine(_ event: SystemEvent, id: String, conversation: ConversationRecord, at: Date, db: Repository) throws {
        let envelope = (try? JSONEncoder().encode(event)) ?? Data()
        _ = try db.insertMessage(MessageRecord(
            id: id, chatID: conversation.chatID, conversationID: conversation.serverID, senderAccount: "system",
            senderDevice: nil, sentAt: at, receivedAt: at, envelope: envelope, status: "sent", replyTo: nil,
            editedAt: nil, deleted: false, undecryptable: false, folded: ""))
    }
}
