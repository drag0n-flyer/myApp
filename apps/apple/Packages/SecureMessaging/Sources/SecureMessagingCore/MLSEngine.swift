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

/// A group member's identity and the MLS signature public key of its leaf. Safety numbers are
/// computed from these.
public struct MemberKey: Sendable, Equatable {
    public let identity: String
    public let signatureKey: Data
    public init(identity: String, signatureKey: Data) {
        self.identity = identity
        self.signatureKey = signatureKey
    }
}

/// An application message and nothing else, from `decryptApplication`.
public struct ApplicationMessage: Sendable, Equatable {
    public let sender: String   // "<accountID>/<deviceID>"
    public let plaintext: Data
    public let epoch: UInt64    // the epoch it was sent in
    public init(sender: String, plaintext: Data, epoch: UInt64) {
        self.sender = sender
        self.plaintext = plaintext
        self.epoch = epoch
    }
}

/// A message that `stageProcess` decrypted or validated but did not persist. Hand it to
/// `confirm` (persist) or `discard` (drop). Dropping it without either is the same as
/// `discard`: nothing was written. `token` belongs to the engine that made it.
public final class StagedProcess: @unchecked Sendable {
    public let group: GroupID
    /// What `process` would have returned.
    public let incoming: Incoming
    public let token: AnyObject?
    public init(group: GroupID, incoming: Incoming, token: AnyObject?) {
        self.group = group
        self.incoming = incoming
        self.token = token
    }
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
    /// Every member's identity and leaf signature public key, sorted by identity (UTF-8 byte
    /// order), including this device.
    func memberKeys(of group: GroupID) async throws -> [MemberKey]
    /// Decrypts or validates `message` for `group` and persists NOTHING. Throws what `process` throws;
    /// `.malformed` if the message is for another group.
    ///
    /// Caller protocol (stage, write rows, confirm):
    /// 1. `stageProcess`.
    /// 2. In one transaction, insert the message's rows keyed by the SHA-256 of the ciphertext (a unique
    ///    key; inserting it again is a no-op). Do NOT key by the server's envelope id: an untrusted server
    ///    can deliver one ciphertext under two ids.
    /// 3. `confirm`. If it throws `.stale`, do not write rows again: stage the same ciphertext again and
    ///    confirm that. A `.duplicate` from that second stage means someone else already applied it;
    ///    the rows are already there, so you are done.
    ///
    /// Why `.stale` happens, with no crash involved:
    /// - Double stage: the app and the notification extension fetch the same message and both stage it
    ///   (the engine lock is held per call, not between stage and confirm). Both get the plaintext and
    ///   both insert rows (the second insert is a no-op thanks to the ciphertext-hash key); the first
    ///   confirm wins and the second gets `.stale`, then re-stages, gets `.duplicate`, and stops.
    /// - Another send: anything that changes the group between stage and confirm (this device's own
    ///   `encrypt`, another message, a commit) makes `confirm` throw `.stale`; re-stage and confirm.
    /// A crash after the rows commit and before `confirm` is the same case: the retry stages again, the
    /// row insert is a no-op, and `confirm` runs.
    ///
    /// The message may still be a replay or garbage until `confirm` succeeds; treat the staged plaintext
    /// as provisional, and show it only after `confirm` returned.
    func stageProcess(_ group: GroupID, _ message: Data) async throws -> StagedProcess
    /// Persists a staged message. Throws `.stale` (and writes nothing) if the group's stored state changed
    /// since staging, or if it was already confirmed or discarded; see `stageProcess` for what to do.
    /// The real engine re-processes the message from storage under the lock when it confirms, so a stale
    /// staging can never overwrite newer state.
    func confirm(_ staged: StagedProcess) async throws
    /// Drops a staged message. Nothing was persisted, so there is nothing to undo.
    func discard(_ staged: StagedProcess)
    /// Decrypts an application message and nothing else. A commit, proposal, Welcome or anything that
    /// is not an application message throws `.notApplication` with the group state unchanged. The content
    /// type of a commit is encrypted, so a commit from a future epoch throws `.epochGap` and one from a past
    /// epoch throws `.duplicate`, as `process` does; only commits the engine can read give `.notApplication`.
    func decryptApplication(_ group: GroupID, _ message: Data) async throws -> ApplicationMessage
    /// Whether `memberKeys(of:)` returns real MLS signature keys. Safety numbers are shown only when this is true.
    var providesRealMemberKeys: Bool { get }
}

extension MLSEngine {
    public var providesRealMemberKeys: Bool { false }
}

/// Engine errors. Rust's `FfiError` maps one-to-one onto every case except `.busy`, which
/// comes only from Swift (the cross-process lock timed out). `.epochGap` means the message
/// belongs to a later epoch than this device has: a commit was missed, so resync.
public enum MLSError: Error, Equatable, Sendable {
    case malformed
    case unknownGroup
    case notMember
    case duplicate
    case epochGap
    case pendingCommit
    /// `confirm` on a staged message whose group state changed since staging (or that was already
    /// confirmed or discarded). Not `.epochGap`: that means a commit was missed; here the state
    /// moved on, perhaps by an unrelated change at the same epoch. Stage the message again.
    case stale
    /// `decryptApplication` was given something other than an application message.
    case notApplication
    case busy
    case storage(String)
    case crypto(String)
    case internalError(String)
}
