#if canImport(MLSFFIBindings)
import Foundation
@preconcurrency import MLSFFIBindings

/// The real MLS engine: a thin Swift wrapper over the Rust `Engine` (mlsffi).
///
/// Every method takes the cross-process lock (`<storage>/mls.lock`, `flock`) for exactly one
/// engine call. The Rust engine keeps no group cache and re-reads state from disk each call,
/// so the app and the notification extension can share one storage directory safely. This type
/// depends only on the mlsffi Swift API, not on which crypto provider the Rust side uses.
///
/// Waiting for the lock blocks the actor's thread for up to `lockTimeout` (default 5 s); a
/// timeout throws `MLSError.busy`.
public actor LiveMLSEngine: MLSEngine {
    private let engine: Engine
    private let lock: CrossProcessLock

    public init(
        identity: String, storage: URL, keys: SigningKeyStore, lockTimeout: Duration = .seconds(5)
    ) throws {
        try Self.prepareStorage(storage)
        let lock = CrossProcessLock(url: storage.appendingPathComponent("mls.lock"), timeout: lockTimeout)
        // Load-or-generate runs under the lock: if the app and the extension both start with
        // no key, the second one must find the first one's key, not overwrite it.
        let keypair: SignatureKeypair = try lock.withLock {
            if let existing = try keys.load(identity: identity) {
                return SignatureKeypair(publicKey: existing.publicKey, secretKey: existing.secretKey)
            }
            let fresh = try Self.mapping { try generateSignatureKeypair() }
            try keys.save(identity: identity, publicKey: fresh.publicKey, secretKey: fresh.secretKey)
            return fresh
        }
        self.lock = lock
        self.engine = try Self.mapping {
            try Engine(identity: identity, keypair: keypair, storageDir: storage.path)
        }
    }

    // MARK: MLSEngine

    public func makeKeyPackages(count: Int) async throws -> [Data] {
        guard count >= 0, count <= Int(UInt32.max) else { throw MLSError.malformed }
        return try locked { try $0.keyPackages(count: UInt32(count)) }
    }

    public func createGroup() async throws -> GroupID {
        GroupID(raw: try locked { try $0.createGroup() })
    }

    public func add(keyPackages: [Data], to group: GroupID) async throws -> SecureMessagingCore.CommitBundle {
        Self.bundle(try locked { try $0.add(groupId: group.raw, keyPackages: keyPackages) })
    }

    public func remove(members: [String], from group: GroupID) async throws -> SecureMessagingCore.CommitBundle {
        Self.bundle(try locked { try $0.remove(groupId: group.raw, identities: members) })
    }

    public func commitAccepted(_ group: GroupID) async throws {
        try locked { try $0.commitAccepted(groupId: group.raw) }
    }

    public func commitRejected(_ group: GroupID) async throws {
        try locked { try $0.commitRejected(groupId: group.raw) }
    }

    public func encrypt(_ plaintext: Data, in group: GroupID) async throws -> (ciphertext: Data, epoch: UInt64) {
        let result = try locked { try $0.encrypt(groupId: group.raw, plaintext: plaintext) }
        return (result.ciphertext, result.epoch)
    }

    public func process(_ message: Data) async throws -> Incoming {
        Self.incoming(try locked { try $0.process(message: message) })
    }

    /// Real MLS signature keys: safety numbers can be shown.
    public nonisolated var providesRealMemberKeys: Bool { true }

    public func memberKeys(of group: GroupID) async throws -> [SecureMessagingCore.MemberKey] {
        try locked { try $0.memberKeys(groupId: group.raw) }
            .map { SecureMessagingCore.MemberKey(identity: $0.identity, signatureKey: $0.signatureKey) }
    }

    public func stageProcess(_ group: GroupID, _ message: Data) async throws -> SecureMessagingCore.StagedProcess {
        let ffi = try locked { try $0.stageProcess(groupId: group.raw, message: message) }
        return SecureMessagingCore.StagedProcess(group: group, incoming: Self.incoming(ffi.incoming()), token: ffi)
    }

    public func confirm(_ staged: SecureMessagingCore.StagedProcess) async throws {
        guard let ffi = staged.token as? MLSFFIBindings.StagedProcess else { throw MLSError.malformed }
        try locked { try $0.confirm(staged: ffi) }
    }

    /// Needs no engine call and no lock: a staged message holds nothing in storage.
    public nonisolated func discard(_ staged: SecureMessagingCore.StagedProcess) {
        (staged.token as? MLSFFIBindings.StagedProcess)?.discard()
    }

    public func decryptApplication(_ group: GroupID, _ message: Data) async throws -> SecureMessagingCore.ApplicationMessage {
        let ffi = try locked { try $0.decryptApplication(groupId: group.raw, message: message) }
        return SecureMessagingCore.ApplicationMessage(sender: ffi.sender, plaintext: ffi.plaintext, epoch: ffi.epoch)
    }

    public func epoch(of group: GroupID) async throws -> UInt64 {
        try locked { try $0.epoch(groupId: group.raw) }
    }

    public func exportSecret(group: GroupID, label: String, length: Int) async throws -> Data {
        guard length >= 0, length <= Int(UInt32.max) else { throw MLSError.malformed }
        return try locked { try $0.exportSecret(groupId: group.raw, label: label, length: UInt32(length)) }
    }

    public func members(of group: GroupID) async throws -> [String] {
        try locked { try $0.members(groupId: group.raw) }
    }

    // MARK: Helpers

    private func locked<T>(_ body: (Engine) throws -> T) throws -> T {
        try lock.withLock { try Self.mapping { try body(engine) } }
    }

    private static func bundle(_ ffi: MLSFFIBindings.CommitBundle) -> SecureMessagingCore.CommitBundle {
        SecureMessagingCore.CommitBundle(commit: ffi.commit, welcome: ffi.welcome, newEpoch: ffi.newEpoch)
    }

    private static func incoming(_ processed: Processed) -> Incoming {
        switch processed {
        case let .application(groupId, sender, plaintext, epoch):
            return .application(group: GroupID(raw: groupId), sender: sender, plaintext: plaintext, epoch: epoch)
        case let .commit(groupId, newEpoch, removedMe):
            return .commit(group: GroupID(raw: groupId), newEpoch: newEpoch, removedMe: removedMe)
        case let .joined(groupId, epoch):
            return .joined(group: GroupID(raw: groupId), epoch: epoch)
        }
    }

    /// Runs `body`, converting `FfiError` to `MLSError`. Anything else (a Rust panic surfaced
    /// by UniFFI) becomes `.internalError`; `MLSError` passes through unchanged.
    static func mapping<T>(_ body: () throws -> T) throws -> T {
        do { return try body() }
        catch let error as FfiError { throw map(error) }
        catch let error as MLSError { throw error }
        catch { throw MLSError.internalError(String(describing: type(of: error))) }
    }

    static func map(_ error: FfiError) -> MLSError {
        switch error {
        case .Malformed: return .malformed
        case .UnknownGroup: return .unknownGroup
        case .NotMember: return .notMember
        case .Duplicate: return .duplicate
        case .EpochGap: return .epochGap
        case .PendingCommit: return .pendingCommit
        case .Stale: return .stale
        case .NotApplication: return .notApplication
        case .Storage(let message): return .storage(message)
        case .Crypto(let message): return .crypto(message)
        case .Internal(let message): return .internalError(message)
        }
    }

    /// Creates the storage directory. On iOS its protection class is
    /// `completeUntilFirstUserAuthentication`, so the notification extension can read it while
    /// the phone is locked; files created inside inherit the class. Applied to an existing
    /// directory too, so a directory created without it is fixed on the next launch.
    private static func prepareStorage(_ storage: URL) throws {
        do {
            #if os(iOS)
            let attributes: [FileAttributeKey: Any] = [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
            try FileManager.default.createDirectory(at: storage, withIntermediateDirectories: true, attributes: attributes)
            try FileManager.default.setAttributes(attributes, ofItemAtPath: storage.path)
            #else
            try FileManager.default.createDirectory(at: storage, withIntermediateDirectories: true)
            #endif
            // MLS state must not be restored onto another device: it would clone this device's
            // group membership (the signing key is ThisDeviceOnly and would not follow).
            var directory = storage
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try directory.setResourceValues(values)
        } catch {
            throw MLSError.storage("cannot create storage directory")
        }
    }
}

/// The engine the app and the notification extension use: signing key in the shared Keychain
/// access group named by the Info.plist key `MLSKeychainAccessGroup`.
public func makeMLSEngine(identity: String, storage: URL) throws -> MLSEngine {
    // Fails closed when the key is missing: no silent fallback to the default access group.
    let group = try KeychainAccessGroupConfig.resolve(
        Bundle.main.object(forInfoDictionaryKey: KeychainAccessGroupConfig.infoKey))
    return try LiveMLSEngine(
        identity: identity, storage: storage, keys: KeychainSigningKeyStore(accessGroup: group))
}
#endif
