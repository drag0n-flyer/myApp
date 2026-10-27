import Foundation

/// An MLS group identifier: 32 random bytes generated client-side. The server's conversation
/// ID is separate; plan 7 maps one to the other.
public struct GroupID: Hashable, Sendable, Codable {
    public let raw: Data
    public init(raw: Data) { self.raw = raw }
}

/// The result of `add` or `remove`: the commit to send, the Welcome for added members (if any),
/// and the epoch the group reaches once the commit is accepted.
public struct CommitBundle: Sendable, Equatable {
    public let commit: Data
    public let welcome: Data?
    public let newEpoch: UInt64
    public init(commit: Data, welcome: Data?, newEpoch: UInt64) {
        self.commit = commit
        self.welcome = welcome
        self.newEpoch = newEpoch
    }
}

public enum Incoming: Sendable, Equatable {
    case application(group: GroupID, sender: String /* "<accountID>/<deviceID>" */, plaintext: Data, epoch: UInt64)
    case commit(group: GroupID, newEpoch: UInt64, removedMe: Bool)
    case joined(group: GroupID, epoch: UInt64)          // from a Welcome
}

/// The MLS client contract that plans 6-8 code against.
///
/// The real engine re-reads group state from storage on every call, because the notification
/// service extension changes it from another process.
public protocol MLSEngine: Sendable {
    func makeKeyPackages(count: Int) async throws -> [Data]
    func createGroup() async throws -> GroupID
    func add(keyPackages: [Data], to group: GroupID) async throws -> CommitBundle
    func remove(members: [String], from group: GroupID) async throws -> CommitBundle
    func commitAccepted(_ group: GroupID) async throws   // server said 200: merge the pending commit
    func commitRejected(_ group: GroupID) async throws   // server said stale_epoch: discard it
    func encrypt(_ plaintext: Data, in group: GroupID) async throws -> (ciphertext: Data, epoch: UInt64)
    func process(_ message: Data) async throws -> Incoming   // Welcome, commit, or application message
    func epoch(of group: GroupID) async throws -> UInt64
    func exportSecret(group: GroupID, label: String, length: Int) async throws -> Data
    func members(of group: GroupID) async throws -> [String]   // added by plan 7's request
    /// Whether the engine's member keys are real MLS signature keys. Safety numbers are shown only when this is
    /// true. TODO(M7): the real engine overrides this with `true` when `memberKeys(of:)` lands.
    var providesRealMemberKeys: Bool { get }
}

extension MLSEngine {
    public var providesRealMemberKeys: Bool { false }
}

/// Engine errors. Rust's `FfiError` maps one-to-one onto the first five cases and the last
/// three; `.busy` comes only from Swift (the cross-process lock timed out).
public enum MLSError: Error, Equatable, Sendable {
    case malformed
    case unknownGroup
    case notMember
    case duplicate
    case pendingCommit
    case busy
    case storage(String)
    case crypto(String)
    case internalError(String)
}
