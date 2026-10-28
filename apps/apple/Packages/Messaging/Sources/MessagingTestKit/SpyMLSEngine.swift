import Foundation
import SecureMessagingCore

/// Wraps an `MLSEngine`: counts `process` calls and fails chosen payloads. For tests only.
public final class SpyMLSEngine: MLSEngine, @unchecked Sendable {
    private let inner: MLSEngine
    private let lock = NSLock()
    private var _processed: [Data] = []
    private var failing: Set<Data> = []
    private var membersFail = false
    private var _rejected = 0
    private var membersFailures: (count: Int, error: MLSError) = (0, .busy)
    private var _addCount = 0
    private var holds: [Data: (entered: AsyncStream<Void>.Continuation, release: AsyncStream<Void>)] = [:]

    public init(_ inner: MLSEngine) { self.inner = inner }

    /// Every payload passed to `process`, in order.
    public var processed: [Data] { lock.withLock { _processed } }
    /// `process` throws `MLSError.crypto` for this payload (without reaching the wrapped engine).
    public func fail(_ payload: Data) { lock.withLock { _ = failing.insert(payload) } }

    /// How many times `commitRejected` was called.
    public var commitRejectedCount: Int { lock.withLock { _rejected } }

    /// `members(of:)` throws `MLSError.storage` while set.
    public func failMembers(_ on: Bool) { lock.withLock { membersFail = on } }

    /// The next `count` calls to `members(of:)` throw `error`.
    public func failNextMembers(_ count: Int, with error: MLSError) { lock.withLock { membersFailures = (count, error) } }

    /// How many add commits `add` has built.
    public var addCount: Int { lock.withLock { _addCount } }

    /// `process` for this payload stops until `release()` is called. `entered` yields once `process` is running.
    public func hold(_ payload: Data) -> (entered: AsyncStream<Void>, release: @Sendable () -> Void) {
        let (entered, enteredContinuation) = AsyncStream.makeStream(of: Void.self)
        let (released, releaseContinuation) = AsyncStream.makeStream(of: Void.self)
        lock.withLock { holds[payload] = (enteredContinuation, released) }
        return (entered, { releaseContinuation.finish() })
    }

    public func process(_ message: Data) async throws -> Incoming {
        if let held = lock.withLock({ holds.removeValue(forKey: message) }) {
            lock.withLock { _processed.append(message) }
            held.entered.yield()
            for await _ in held.release {}
            return try await inner.process(message)
        }
        let shouldFail = lock.withLock { () -> Bool in
            _processed.append(message)
            return failing.contains(message)
        }
        if shouldFail { throw MLSError.crypto("injected failure") }
        return try await inner.process(message)
    }

    public var providesRealMemberKeys: Bool { inner.providesRealMemberKeys }

    public func makeKeyPackages(count: Int) async throws -> [Data] { try await inner.makeKeyPackages(count: count) }
    public func createGroup() async throws -> GroupID { try await inner.createGroup() }
    public func add(keyPackages: [Data], to group: GroupID) async throws -> CommitBundle {
        lock.withLock { _addCount += 1 }
        return try await inner.add(keyPackages: keyPackages, to: group)
    }
    public func remove(members: [String], from group: GroupID) async throws -> CommitBundle { try await inner.remove(members: members, from: group) }
    public func commitAccepted(_ group: GroupID) async throws { try await inner.commitAccepted(group) }
    public func commitRejected(_ group: GroupID) async throws {
        lock.withLock { _rejected += 1 }
        try await inner.commitRejected(group)
    }
    public func encrypt(_ plaintext: Data, in group: GroupID) async throws -> (ciphertext: Data, epoch: UInt64) { try await inner.encrypt(plaintext, in: group) }
    public func epoch(of group: GroupID) async throws -> UInt64 { try await inner.epoch(of: group) }
    public func exportSecret(group: GroupID, label: String, length: Int) async throws -> Data { try await inner.exportSecret(group: group, label: label, length: length) }
    public func memberKeys(of group: GroupID) async throws -> [MemberKey] { try await inner.memberKeys(of: group) }
    public func stageProcess(_ group: GroupID, _ message: Data) async throws -> StagedProcess { try await inner.stageProcess(group, message) }
    public func confirm(_ staged: StagedProcess) async throws { try await inner.confirm(staged) }
    public func discard(_ staged: StagedProcess) { inner.discard(staged) }
    public func decryptApplication(_ group: GroupID, _ message: Data) async throws -> ApplicationMessage { try await inner.decryptApplication(group, message) }
    public func members(of group: GroupID) async throws -> [String] {
        if lock.withLock({ membersFail }) { throw MLSError.storage("injected failure") }
        let injected = lock.withLock { () -> MLSError? in
            guard membersFailures.count > 0 else { return nil }
            membersFailures.count -= 1
            return membersFailures.error
        }
        if let injected { throw injected }
        return try await inner.members(of: group)
    }
}
