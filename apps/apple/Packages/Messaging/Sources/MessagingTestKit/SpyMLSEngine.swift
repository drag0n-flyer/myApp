import Foundation
import SecureMessagingCore

/// Wraps an `MLSEngine`: counts `process` calls and fails chosen payloads. For tests only.
public final class SpyMLSEngine: MLSEngine, @unchecked Sendable {
    private let inner: MLSEngine
    private let lock = NSLock()
    private var _processed: [Data] = []
    private var failing: Set<Data> = []

    public init(_ inner: MLSEngine) { self.inner = inner }

    /// Every payload passed to `process`, in order.
    public var processed: [Data] { lock.withLock { _processed } }
    /// `process` throws `MLSError.crypto` for this payload (without reaching the wrapped engine).
    public func fail(_ payload: Data) { lock.withLock { _ = failing.insert(payload) } }

    public func process(_ message: Data) async throws -> Incoming {
        let shouldFail = lock.withLock { () -> Bool in
            _processed.append(message)
            return failing.contains(message)
        }
        if shouldFail { throw MLSError.crypto("injected failure") }
        return try await inner.process(message)
    }

    public func makeKeyPackages(count: Int) async throws -> [Data] { try await inner.makeKeyPackages(count: count) }
    public func createGroup() async throws -> GroupID { try await inner.createGroup() }
    public func add(keyPackages: [Data], to group: GroupID) async throws -> CommitBundle { try await inner.add(keyPackages: keyPackages, to: group) }
    public func remove(members: [String], from group: GroupID) async throws -> CommitBundle { try await inner.remove(members: members, from: group) }
    public func commitAccepted(_ group: GroupID) async throws { try await inner.commitAccepted(group) }
    public func commitRejected(_ group: GroupID) async throws { try await inner.commitRejected(group) }
    public func encrypt(_ plaintext: Data, in group: GroupID) async throws -> (ciphertext: Data, epoch: UInt64) { try await inner.encrypt(plaintext, in: group) }
    public func epoch(of group: GroupID) async throws -> UInt64 { try await inner.epoch(of: group) }
    public func exportSecret(group: GroupID, label: String, length: Int) async throws -> Data { try await inner.exportSecret(group: group, label: label, length: length) }
    public func members(of group: GroupID) async throws -> [String] { try await inner.members(of: group) }
}
