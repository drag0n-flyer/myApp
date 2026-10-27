import Foundation
import Testing
import SecureMessaging

private let alice = "acct-a/dev-1"
private let bob = "acct-b/dev-1"
private let alice2 = "acct-a/dev-2"

private func utf8(_ s: String) -> Data { Data(s.utf8) }

/// Adds `joiners` (each a fresh engine) to a new group created by `creator`, accepts the commit
/// and has every joiner process the Welcome.
private func makeGroup(
    creator: any MLSEngine, joiners: [any MLSEngine]
) async throws -> GroupID {
    let group = try await creator.createGroup()
    var kps: [Data] = []
    for joiner in joiners { kps += try await joiner.makeKeyPackages(count: 1) }
    let bundle = try await creator.add(keyPackages: kps, to: group)
    try await creator.commitAccepted(group)
    let welcome = try #require(bundle.welcome)
    for joiner in joiners {
        let incoming = try await joiner.process(welcome)
        guard case .joined(let g, _) = incoming else {
            Issue.record("expected .joined, got \(incoming)")
            continue
        }
        #expect(g == group)
    }
    return group
}

@Suite struct FakeMLSEngineTests {
    @Test func testTwoDevicesExchange() async throws {
        let a = FakeMLSEngine(identity: alice)
        let b = FakeMLSEngine(identity: bob)

        let kps = try await b.makeKeyPackages(count: 1)
        #expect(kps.count == 1)
        let g = try await a.createGroup()
        #expect(g.raw.count == 32)
        let bundle = try await a.add(keyPackages: kps, to: g)
        #expect(bundle.welcome != nil)
        #expect(bundle.newEpoch == 1)
        #expect(try await a.epoch(of: g) == 0)
        try await a.commitAccepted(g)
        #expect(try await a.epoch(of: g) == 1)

        let joined = try await b.process(try #require(bundle.welcome))
        #expect(joined == .joined(group: g, epoch: 1))

        let (ct, epoch) = try await a.encrypt(utf8("hi"), in: g)
        #expect(epoch == 1)
        let got = try await b.process(ct)
        #expect(got == .application(group: g, sender: alice, plaintext: utf8("hi"), epoch: 1))

        let (ct2, epoch2) = try await b.encrypt(utf8("hello back"), in: g)
        #expect(epoch2 == 1)
        let got2 = try await a.process(ct2)
        #expect(got2 == .application(group: g, sender: bob, plaintext: utf8("hello back"), epoch: 1))

        #expect(try await a.members(of: g).sorted() == [alice, bob])
        #expect(try await b.members(of: g).sorted() == [alice, bob])
    }

    @Test func testPendingCommitRules() async throws {
        let a = FakeMLSEngine(identity: alice)
        let b = FakeMLSEngine(identity: bob)
        let c = FakeMLSEngine(identity: "acct-c/dev-1")
        let g = try await a.createGroup()

        _ = try await a.add(keyPackages: try await b.makeKeyPackages(count: 1), to: g)
        await #expect(throws: MLSError.pendingCommit) {
            _ = try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: g)
        }
        await #expect(throws: MLSError.pendingCommit) {
            _ = try await a.remove(members: [bob], from: g)
        }
        #expect(try await a.epoch(of: g) == 0)

        try await a.commitRejected(g)
        #expect(try await a.epoch(of: g) == 0)
        #expect(try await a.members(of: g) == [alice])
        // A second rejection is a no-op, and a new add now works.
        try await a.commitRejected(g)
        let bundle = try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: g)
        #expect(bundle.newEpoch == 1)
    }

    @Test func testOthersCommitClearsPendingCommit() async throws {
        let a = FakeMLSEngine(identity: alice)
        let b = FakeMLSEngine(identity: bob)
        let c = FakeMLSEngine(identity: "acct-c/dev-1")
        let g = try await makeGroup(creator: a, joiners: [b])

        // Both add someone at epoch 1; bob's commit wins the race.
        _ = try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: g)
        let d = FakeMLSEngine(identity: "acct-d/dev-1")
        let winner = try await b.add(keyPackages: try await d.makeKeyPackages(count: 1), to: g)
        try await b.commitAccepted(g)

        let incoming = try await a.process(winner.commit)
        #expect(incoming == .commit(group: g, newEpoch: 2, removedMe: false))
        #expect(try await a.epoch(of: g) == 2)
        // Our losing commit was cleared; rejecting it later is a no-op, and a new commit works.
        try await a.commitRejected(g)
        #expect(try await a.epoch(of: g) == 2)
        _ = try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: g)
    }

    @Test func testRemoveMarksRemovedMe() async throws {
        let a = FakeMLSEngine(identity: alice)
        let b = FakeMLSEngine(identity: bob)
        let a2 = FakeMLSEngine(identity: alice2)
        let g = try await makeGroup(creator: a, joiners: [b, a2])
        #expect(try await a.epoch(of: g) == 1)

        let bundle = try await a.remove(members: [alice2], from: g)
        #expect(bundle.newEpoch == 2)
        #expect(bundle.welcome == nil)
        try await a.commitAccepted(g)
        #expect(try await a.members(of: g).sorted() == [alice, bob])

        #expect(try await b.process(bundle.commit) == .commit(group: g, newEpoch: 2, removedMe: false))
        #expect(try await a2.process(bundle.commit) == .commit(group: g, newEpoch: 2, removedMe: true))

        // Bob's epoch-2 message: alice still reads it, the removed device cannot.
        let (ct, _) = try await b.encrypt(utf8("after"), in: g)
        await #expect(throws: MLSError.self) { _ = try await a2.process(ct) }
        let ok = try await a.process(ct)
        #expect(ok == .application(group: g, sender: bob, plaintext: utf8("after"), epoch: 2))

        // Removing someone who is not a member fails.
        await #expect(throws: MLSError.notMember) {
            _ = try await a.remove(members: [alice2], from: g)
        }
    }

    @Test func testDuplicateThrows() async throws {
        let a = FakeMLSEngine(identity: alice)
        let b = FakeMLSEngine(identity: bob)
        let g = try await makeGroup(creator: a, joiners: [b])

        let (ct, _) = try await a.encrypt(utf8("once"), in: g)
        _ = try await b.process(ct)
        await #expect(throws: MLSError.duplicate) { _ = try await b.process(ct) }

        // The next new message still decrypts, even with identical plaintext.
        let (ct2, _) = try await a.encrypt(utf8("once"), in: g)
        let got = try await b.process(ct2)
        #expect(got == .application(group: g, sender: alice, plaintext: utf8("once"), epoch: 1))

        // A device cannot process its own message.
        await #expect(throws: MLSError.duplicate) { _ = try await a.process(ct) }
    }

    @Test func testProcessRejectsBadInput() async throws {
        let a = FakeMLSEngine(identity: alice)
        let b = FakeMLSEngine(identity: bob)
        let g = try await makeGroup(creator: a, joiners: [b])

        await #expect(throws: MLSError.malformed) { _ = try await a.process(Data("junk".utf8)) }
        await #expect(throws: MLSError.malformed) {
            _ = try await a.process(try await b.makeKeyPackages(count: 1)[0])
        }
        // An app message for a group this engine does not know.
        let stranger = FakeMLSEngine(identity: "acct-x/dev-1")
        let (ct, _) = try await a.encrypt(utf8("x"), in: g)
        await #expect(throws: MLSError.unknownGroup) { _ = try await stranger.process(ct) }
        await #expect(throws: MLSError.unknownGroup) { _ = try await stranger.epoch(of: g) }
        await #expect(throws: MLSError.unknownGroup) { _ = try await stranger.encrypt(utf8("x"), in: g) }
    }

    @Test func testApplicationFromFutureEpochThrows() async throws {
        let a = FakeMLSEngine(identity: alice)
        let b = FakeMLSEngine(identity: bob)
        let c = FakeMLSEngine(identity: "acct-c/dev-1")
        let g = try await makeGroup(creator: a, joiners: [b])

        _ = try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: g)
        try await a.commitAccepted(g)            // alice is at epoch 2, bob has not seen the commit
        let (ct, epoch) = try await a.encrypt(utf8("early"), in: g)
        #expect(epoch == 2)
        await #expect(throws: MLSError.self) { _ = try await b.process(ct) }
        #expect(try await b.epoch(of: g) == 1)
    }

    @Test func testExportSecretAgreesAcrossMembers() async throws {
        let a = FakeMLSEngine(identity: alice)
        let b = FakeMLSEngine(identity: bob)
        let g = try await makeGroup(creator: a, joiners: [b])

        let sa = try await a.exportSecret(group: g, label: "call", length: 32)
        let sb = try await b.exportSecret(group: g, label: "call", length: 32)
        #expect(sa.count == 32)
        #expect(sa == sb)
        // The spec'd value starts with the 64-hex-digit group ID, so tell labels and epochs
        // apart with a length that reaches past it.
        let full = try await a.exportSecret(group: g, label: "call", length: 100)
        #expect(try await a.exportSecret(group: g, label: "other", length: 100) != full)
        // Shorter lengths cut, longer lengths repeat.
        let short = try await a.exportSecret(group: g, label: "call", length: 8)
        #expect(short == sa.prefix(8))
        let long = try await a.exportSecret(group: g, label: "call", length: 100)
        #expect(long.count == 100)
        let hex = g.raw.map { String(format: "%02x", $0) }.joined()
        let unit = Data("\(hex):1:call".utf8)
        #expect(long.prefix(unit.count) == unit)
        // A new epoch gives a new secret.
        let c = FakeMLSEngine(identity: "acct-c/dev-1")
        _ = try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: g)
        try await a.commitAccepted(g)
        #expect(try await a.exportSecret(group: g, label: "call", length: 100) != full)
    }
}
