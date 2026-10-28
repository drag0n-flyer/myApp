import Foundation
import LocalStore

/// A conversation this device created whose add commit is not settled yet (`kv` row `create-pending:<serverID>`).
///
/// The conversation row is written, inactive and bound to its group, before the commit is posted, together with
/// this record of the exact commit, Welcome and added devices. A lost reply, a timeout or a kill then leaves
/// something to finish: the echo activates the row, or `ConversationManager` confirms it against the server's
/// epoch, or posts the same commit again (MLS profile, "Commits" step 5, exception). Cleared when the conversation
/// becomes active or the server refuses it.
struct PendingCreate: Codable, Equatable, Sendable {
    let commit: Data
    let welcome: Data?
    let addDevices: [String]

    static let prefix = "create-pending:"

    static func key(serverID: String) -> String { prefix + serverID }

    func save(serverID: String, db: Repository) throws {
        try db.set(Self.key(serverID: serverID), try JSONEncoder().encode(self))
    }

    /// `nil` when there is none (an empty value is a cleared one).
    static func load(serverID: String, db: Repository) throws -> PendingCreate? {
        guard let data = try db.get(key(serverID: serverID)), !data.isEmpty else { return nil }
        return try JSONDecoder().decode(PendingCreate.self, from: data)
    }

    static func isPending(serverID: String, db: Repository) throws -> Bool {
        !(try db.get(key(serverID: serverID)) ?? Data()).isEmpty
    }

    static func clear(serverID: String, db: Repository) throws {
        try db.set(key(serverID: serverID), Data())
    }

    /// Server IDs of every pending create, sorted.
    static func all(db: Repository) throws -> [String] {
        try db.entries(prefix: prefix).filter { !$0.value.isEmpty }.keys.map { String($0.dropFirst(prefix.count)) }.sorted()
    }
}

/// An inbox item that could not be applied and must not block the queue: a Welcome whose chat could not be set up.
/// It is kept (`kv` row `parked:<cursor>`: the reason and the raw item) instead of being dropped, so a later version
/// or a repair pass can look at it. Never holds plaintext: the payload is the item as the server delivered it.
struct ParkedItem: Codable, Equatable, Sendable {
    let reason: String
    let cursor: Int64
    let kind: String
    let conversationID: String
    let seq: Int64?
    let epoch: Int64
    let senderAccount: String
    let senderDevice: String
    let sentAt: Date
    let payload: Data

    static let prefix = "parked:"

    static func key(cursor: Int64) -> String { prefix + String(cursor) }

    static func save(_ raw: RawInboxRecord, reason: String, db: Repository) throws {
        let item = ParkedItem(
            reason: reason, cursor: raw.cursor, kind: raw.kind, conversationID: raw.conversationID, seq: raw.seq,
            epoch: raw.epoch, senderAccount: raw.senderAccount, senderDevice: raw.senderDevice, sentAt: raw.sentAt,
            payload: raw.payload)
        try db.set(key(cursor: raw.cursor), try JSONEncoder().encode(item))
    }
}
