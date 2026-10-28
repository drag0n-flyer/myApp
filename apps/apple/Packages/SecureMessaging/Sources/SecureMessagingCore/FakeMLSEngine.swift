import Foundation

/// A plaintext, in-memory `MLSEngine` for tests in plans 6-8.
///
/// **NOT SECURE.** Nothing is encrypted or authenticated: messages are JSON with the plaintext
/// base64-encoded, and any sender identity can be forged. Never ship it in a code path that
/// handles real conversations.
///
/// It is faithful about state, not about cryptography. It applies the same epoch and
/// pending-commit rules as the real engine:
/// - `add` and `remove` throw `.pendingCommit` while a commit is pending; the epoch advances
///   only on `commitAccepted` (or when our own commit comes back through `process`).
/// - Processing someone else's commit clears our pending commit (it lost the race); a later
///   `commitRejected` or `commitAccepted` with nothing pending is a no-op.
/// - An application message from an epoch above ours throws `.epochGap`, and so does a commit
///   that skips an epoch. A message from more than two epochs below ours, or seen twice, or our
///   own, throws `.duplicate`. A message for an unknown group throws `.unknownGroup`.
/// - After a commit removes this device, the group stays known but every use throws `.notMember`.
///
/// Wire format: JSON with `"fake"` set to `"kp"`, `"commit"`, `"welcome"` or `"app"`, plus
/// `group` (hex), `epoch`, `members` (identities), `sender`, `body` (base64) and a random
/// `nonce` so that equal plaintexts still give distinct messages.
public final class FakeMLSEngine: MLSEngine, @unchecked Sendable {
    private struct Wire: Codable {
        var fake: String
        var group: String?
        var epoch: UInt64?
        var members: [String]?
        var sender: String?
        var body: String?
        var nonce: String?
    }

    private struct GroupState {
        var epoch: UInt64
        var members: [String]
        var pending: (epoch: UInt64, members: [String])?
        var seen: Set<Data> = []
        var removed = false

        func isSame(as other: GroupState) -> Bool {
            epoch == other.epoch && members == other.members && seen == other.seen
                && removed == other.removed && pending?.epoch == other.pending?.epoch
                && pending?.members == other.pending?.members
        }
    }

    /// What a staged message needs at confirm time: the state it was staged against and the state
    /// it leads to. (`nil` = the group did not exist.)
    private final class Token {
        let before: GroupState?
        let after: GroupState?
        var closed = false   // guarded by the engine's `lock`
        init(before: GroupState?, after: GroupState?) { self.before = before; self.after = after }
    }

    private let identity: String
    private let lock = NSLock()
    private var groups: [GroupID: GroupState] = [:]   // guarded by `lock`

    /// Its member keys are placeholders (`"fake-sig:<identity>"`), so it reports no real member keys unless a
    /// test passes `realMemberKeys: true` to exercise the safety-number UI.
    public let providesRealMemberKeys: Bool

    public init(identity: String, realMemberKeys: Bool = false) {
        self.identity = identity
        self.providesRealMemberKeys = realMemberKeys
    }

    // MARK: MLSEngine

    public func makeKeyPackages(count: Int) async throws -> [Data] {
        guard count >= 0 else { throw MLSError.malformed }
        return try (0..<count).map { _ in
            try encode(Wire(fake: "kp", sender: identity, nonce: UUID().uuidString))
        }
    }

    public func createGroup() async throws -> GroupID {
        let group = GroupID(raw: Data((0..<32).map { _ in UInt8.random(in: 0...255) }))
        lock.withLock { groups[group] = GroupState(epoch: 0, members: [identity]) }
        return group
    }

    public func add(keyPackages: [Data], to group: GroupID) async throws -> CommitBundle {
        guard !keyPackages.isEmpty else { throw MLSError.malformed }
        let joiners = try keyPackages.map { data -> String in
            let wire = try decode(data)
            guard wire.fake == "kp", let sender = wire.sender else { throw MLSError.malformed }
            return sender
        }
        return try lock.withLock {
            var state = try activeState(group)
            guard state.pending == nil else { throw MLSError.pendingCommit }
            guard Set(joiners).count == joiners.count, Set(joiners).isDisjoint(with: state.members) else {
                throw MLSError.duplicate   // already a member (or listed twice)
            }
            let members = state.members + joiners
            let newEpoch = state.epoch + 1
            state.pending = (newEpoch, members)
            groups[group] = state
            let commit = try encode(Wire(
                fake: "commit", group: hex(group), epoch: newEpoch, members: members,
                sender: identity, nonce: UUID().uuidString))
            let welcome = try encode(Wire(
                fake: "welcome", group: hex(group), epoch: newEpoch, members: members,
                sender: identity, nonce: UUID().uuidString))
            return CommitBundle(commit: commit, welcome: welcome, newEpoch: newEpoch)
        }
    }

    public func remove(members removed: [String], from group: GroupID) async throws -> CommitBundle {
        guard !removed.isEmpty else { throw MLSError.malformed }
        return try lock.withLock {
            var state = try activeState(group)
            guard state.pending == nil else { throw MLSError.pendingCommit }
            guard removed.allSatisfy(state.members.contains) else { throw MLSError.notMember }
            guard !removed.contains(identity) else { throw MLSError.malformed }   // cannot remove ourselves
            let members = state.members.filter { !removed.contains($0) }
            let newEpoch = state.epoch + 1
            state.pending = (newEpoch, members)
            groups[group] = state
            let commit = try encode(Wire(
                fake: "commit", group: hex(group), epoch: newEpoch, members: members,
                sender: identity, nonce: UUID().uuidString))
            return CommitBundle(commit: commit, welcome: nil, newEpoch: newEpoch)
        }
    }

    public func commitAccepted(_ group: GroupID) async throws {
        try lock.withLock {
            var state = try activeState(group)
            guard let pending = state.pending else { return }
            state.epoch = pending.epoch
            state.members = pending.members
            state.pending = nil
            groups[group] = state
        }
    }

    public func commitRejected(_ group: GroupID) async throws {
        try lock.withLock {
            var state = try activeState(group)
            state.pending = nil
            groups[group] = state
        }
    }

    public func encrypt(_ plaintext: Data, in group: GroupID) async throws -> (ciphertext: Data, epoch: UInt64) {
        try lock.withLock {
            let state = try activeState(group)
            let message = try encode(Wire(
                fake: "app", group: hex(group), epoch: state.epoch, sender: identity,
                body: plaintext.base64EncodedString(), nonce: UUID().uuidString))
            return (message, state.epoch)
        }
    }

    public func process(_ message: Data) async throws -> Incoming {
        let wire = try decode(message)
        return try lock.withLock { try processLocked(wire, raw: message) }
    }

    public func memberKeys(of group: GroupID) async throws -> [MemberKey] {
        try lock.withLock {
            try activeState(group).members
                .sorted { $0.utf8.lexicographicallyPrecedes($1.utf8) }
                .map { MemberKey(identity: $0, signatureKey: Data("fake-sig:\($0)".utf8)) }
        }
    }

    public func stageProcess(_ group: GroupID, _ message: Data) async throws -> StagedProcess {
        let wire = try decode(message)
        guard try groupID(wire) == group else { throw MLSError.malformed }
        return try lock.withLock {
            // Run the real processing on the live table, then put the table back.
            let before = groups[group]
            let saved = groups
            defer { groups = saved }
            let incoming = try processLocked(wire, raw: message)
            return StagedProcess(group: group, incoming: incoming, token: Token(before: before, after: groups[group]))
        }
    }

    public func confirm(_ staged: StagedProcess) async throws {
        guard let token = staged.token as? Token else { throw MLSError.malformed }
        try lock.withLock {
            guard !token.closed else { throw MLSError.stale }
            let now = groups[staged.group]
            switch (now, token.before) {
            case (nil, nil): break
            case let (now?, before?) where now.isSame(as: before): break
            default: throw MLSError.stale
            }
            groups[staged.group] = token.after
            token.closed = true
        }
    }

    public func discard(_ staged: StagedProcess) {
        guard let token = staged.token as? Token else { return }
        lock.withLock { token.closed = true }
    }

    public func decryptApplication(_ group: GroupID, _ message: Data) async throws -> ApplicationMessage {
        let wire = try decode(message)
        guard wire.fake == "app" else { throw MLSError.notApplication }   // before anything is touched
        guard try groupID(wire) == group else { throw MLSError.malformed }
        guard case let .application(_, sender, plaintext, epoch) = try await process(message) else {
            throw MLSError.notApplication
        }
        return ApplicationMessage(sender: sender, plaintext: plaintext, epoch: epoch)
    }

    public func epoch(of group: GroupID) async throws -> UInt64 {
        try lock.withLock { try activeState(group).epoch }
    }

    public func exportSecret(group: GroupID, label: String, length: Int) async throws -> Data {
        guard length >= 0 else { throw MLSError.malformed }
        return try lock.withLock {
            let state = try activeState(group)
            // Varying parts first, so short lengths still differ per label and epoch.
            let unit = Array("\(label):\(state.epoch):\(hex(group))".utf8)
            return Data((0..<length).map { unit[$0 % unit.count] })
        }
    }

    public func members(of group: GroupID) async throws -> [String] {
        try lock.withLock { try activeState(group).members }
    }

    // MARK: Processing

    /// Must be called with `lock` held.
    private func processLocked(_ wire: Wire, raw: Data) throws -> Incoming {
        switch wire.fake {
        case "welcome": return try processWelcome(wire)
        case "commit": return try processCommit(wire)
        case "app": return try processApplication(wire, raw: raw)
        default: throw MLSError.malformed   // includes key packages
        }
    }

    private func processWelcome(_ wire: Wire) throws -> Incoming {
        let group = try groupID(wire)
        guard let epoch = wire.epoch, let members = wire.members else { throw MLSError.malformed }
        return try { () throws -> Incoming in
            if let existing = groups[group], !existing.removed { throw MLSError.duplicate }
            guard members.contains(identity) else { throw MLSError.notMember }
            groups[group] = GroupState(epoch: epoch, members: members)
            return .joined(group: group, epoch: epoch)
        }()
    }

    private func processCommit(_ wire: Wire) throws -> Incoming {
        let group = try groupID(wire)
        guard let newEpoch = wire.epoch, let members = wire.members, let sender = wire.sender else {
            throw MLSError.malformed
        }
        return try { () throws -> Incoming in
            var state = try activeState(group)
            if sender == identity {
                // Our own commit came back (the 200 was lost): apply it if it is the pending one.
                guard let pending = state.pending, pending.epoch == newEpoch, pending.members == members else {
                    throw MLSError.duplicate
                }
                state.epoch = newEpoch
                state.members = members
                state.pending = nil
                groups[group] = state
                return .commit(group: group, newEpoch: newEpoch, removedMe: false)
            }
            guard newEpoch > state.epoch else { throw MLSError.duplicate }
            guard newEpoch == state.epoch + 1 else {
                throw MLSError.epochGap   // a commit was missed
            }
            state.epoch = newEpoch
            state.members = members
            state.pending = nil   // our own pending commit lost the race
            let removedMe = !members.contains(identity)
            state.removed = removedMe
            groups[group] = state
            return .commit(group: group, newEpoch: newEpoch, removedMe: removedMe)
        }()
    }

    private func processApplication(_ wire: Wire, raw: Data) throws -> Incoming {
        let group = try groupID(wire)
        guard let epoch = wire.epoch, let sender = wire.sender, let body = wire.body,
              let plaintext = Data(base64Encoded: body) else { throw MLSError.malformed }
        return try { () throws -> Incoming in
            var state = try activeState(group)
            if sender == identity { throw MLSError.duplicate }   // cannot process our own message
            guard epoch <= state.epoch else {
                throw MLSError.epochGap   // a commit was missed
            }
            guard state.epoch - epoch <= 2 else {
                throw MLSError.duplicate   // too old to read; the real engine reports it as already passed
            }
            guard state.seen.insert(raw).inserted else { throw MLSError.duplicate }
            groups[group] = state
            return .application(group: group, sender: sender, plaintext: plaintext, epoch: epoch)
        }()
    }

    // MARK: Helpers

    /// Must be called with `lock` held.
    private func activeState(_ group: GroupID) throws -> GroupState {
        guard let state = groups[group] else { throw MLSError.unknownGroup }
        guard !state.removed else { throw MLSError.notMember }
        return state
    }

    private func hex(_ group: GroupID) -> String {
        group.raw.map { String(format: "%02x", $0) }.joined()
    }

    private func groupID(_ wire: Wire) throws -> GroupID {
        guard let text = wire.group, text.count % 2 == 0 else { throw MLSError.malformed }
        var bytes: [UInt8] = []
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            guard let byte = UInt8(text[index..<next], radix: 16) else { throw MLSError.malformed }
            bytes.append(byte)
            index = next
        }
        return GroupID(raw: Data(bytes))
    }

    private func encode(_ wire: Wire) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(wire)
    }

    private func decode(_ data: Data) throws -> Wire {
        do { return try JSONDecoder().decode(Wire.self, from: data) } catch { throw MLSError.malformed }
    }
}
