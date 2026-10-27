import Foundation
import Testing
import SecureMessaging

// M7: member keys, staged processing, application-only decryption. The same contract runs
// against FakeMLSEngine (always) and LiveMLSEngine (where the Rust bindings are built).

private let alice = "acct-a/dev-1"
private let bob = "acct-b/dev-1"
private let carol = "acct-c/dev-1"

private func utf8(_ s: String) -> Data { Data(s.utf8) }

private func makeGroup(creator: any MLSEngine, joiners: [any MLSEngine]) async throws -> GroupID {
    let group = try await creator.createGroup()
    var kps: [Data] = []
    for joiner in joiners { kps += try await joiner.makeKeyPackages(count: 1) }
    let bundle = try await creator.add(keyPackages: kps, to: group)
    try await creator.commitAccepted(group)
    let welcome = try #require(bundle.welcome)
    for joiner in joiners { _ = try await joiner.process(welcome) }
    return group
}

// MARK: Contract

private func checkStageThenDiscard(a: any MLSEngine, b: any MLSEngine) async throws {
    let g = try await makeGroup(creator: a, joiners: [b])
    let (ct, _) = try await a.encrypt(utf8("hello"), in: g)
    let staged = try await b.stageProcess(g, ct)
    #expect(staged.incoming == .application(group: g, sender: alice, plaintext: utf8("hello"), epoch: 1))
    b.discard(staged)
    await #expect(throws: MLSError.stale) { try await b.confirm(staged) }   // discarded: cannot be confirmed
    let again = try await b.stageProcess(g, ct)                              // the same message stages again
    try await b.confirm(again)
}

private func checkStageConfirmThenDuplicate(a: any MLSEngine, b: any MLSEngine) async throws {
    let g = try await makeGroup(creator: a, joiners: [b])
    let (ct, _) = try await a.encrypt(utf8("once"), in: g)
    let staged = try await b.stageProcess(g, ct)
    try await b.confirm(staged)
    await #expect(throws: MLSError.duplicate) { _ = try await b.stageProcess(g, ct) }
    await #expect(throws: MLSError.stale) { try await b.confirm(staged) }     // not applied twice
}

private func checkConfirmAfterEpochAdvanceIsStale(a: any MLSEngine, b: any MLSEngine, c: any MLSEngine) async throws {
    let g = try await makeGroup(creator: a, joiners: [b])
    let (ct, _) = try await a.encrypt(utf8("old epoch"), in: g)
    let staged = try await b.stageProcess(g, ct)
    let bundle = try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: g)
    try await a.commitAccepted(g)
    _ = try await b.process(bundle.commit)
    await #expect(throws: MLSError.stale) { try await b.confirm(staged) }
    #expect(try await b.epoch(of: g) == 2)
}

private func checkStagedCommitAppliesOnConfirmOnly(a: any MLSEngine, b: any MLSEngine, c: any MLSEngine) async throws {
    let g = try await makeGroup(creator: a, joiners: [b])
    let bundle = try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: g)
    try await a.commitAccepted(g)
    let staged = try await b.stageProcess(g, bundle.commit)
    #expect(staged.incoming == .commit(group: g, newEpoch: 2, removedMe: false))
    #expect(try await b.epoch(of: g) == 1)
    try await b.confirm(staged)
    #expect(try await b.epoch(of: g) == 2)
}

private func checkStageForWrongGroupIsMalformed(a: any MLSEngine, b: any MLSEngine) async throws {
    let g = try await makeGroup(creator: a, joiners: [b])
    let other = try await makeGroup(creator: a, joiners: [b])
    let (ct, _) = try await a.encrypt(utf8("x"), in: g)
    await #expect(throws: MLSError.malformed) { _ = try await b.stageProcess(other, ct) }
}

private func checkDecryptApplication(a: any MLSEngine, b: any MLSEngine, c: any MLSEngine) async throws {
    let g = try await makeGroup(creator: a, joiners: [b])
    let (ct, epoch) = try await a.encrypt(utf8("call offer"), in: g)
    let message = try await b.decryptApplication(g, ct)
    #expect(message == ApplicationMessage(sender: alice, plaintext: utf8("call offer"), epoch: epoch))

    // A commit is refused, the group is untouched, and the commit still applies afterwards.
    let bundle = try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: g)
    try await a.commitAccepted(g)
    await #expect(throws: MLSError.notApplication) { _ = try await b.decryptApplication(g, bundle.commit) }
    #expect(try await b.epoch(of: g) == 1)
    // A Welcome is refused before anything is read.
    await #expect(throws: MLSError.notApplication) { _ = try await c.decryptApplication(g, try #require(bundle.welcome)) }
    await #expect(throws: MLSError.unknownGroup) { _ = try await c.epoch(of: g) }
    #expect(try await b.process(bundle.commit) == .commit(group: g, newEpoch: 2, removedMe: false))
}

private func checkMemberKeysSortedAndIncludeSelf(creator: any MLSEngine, joiners: [any MLSEngine]) async throws -> [MemberKey] {
    let g = try await makeGroup(creator: creator, joiners: joiners)
    let keys = try await creator.memberKeys(of: g)
    #expect(keys.map(\.identity) == keys.map(\.identity).sorted())
    #expect(keys.count == joiners.count + 1)
    for joiner in joiners { #expect(try await joiner.memberKeys(of: g) == keys) }
    return keys
}

// MARK: Fake

@Suite struct FakeStagedProcessingTests {
    private func engines() -> (FakeMLSEngine, FakeMLSEngine, FakeMLSEngine) {
        (FakeMLSEngine(identity: alice), FakeMLSEngine(identity: bob), FakeMLSEngine(identity: carol))
    }

    @Test func testStageThenDiscard() async throws { let (a, b, _) = engines(); try await checkStageThenDiscard(a: a, b: b) }
    @Test func testStageConfirmThenDuplicate() async throws { let (a, b, _) = engines(); try await checkStageConfirmThenDuplicate(a: a, b: b) }
    @Test func testConfirmAfterEpochAdvanceIsStale() async throws {
        let (a, b, c) = engines(); try await checkConfirmAfterEpochAdvanceIsStale(a: a, b: b, c: c)
    }
    @Test func testStagedCommit() async throws { let (a, b, c) = engines(); try await checkStagedCommitAppliesOnConfirmOnly(a: a, b: b, c: c) }
    @Test func testWrongGroup() async throws { let (a, b, _) = engines(); try await checkStageForWrongGroupIsMalformed(a: a, b: b) }
    @Test func testDecryptApplication() async throws { let (a, b, c) = engines(); try await checkDecryptApplication(a: a, b: b, c: c) }

    @Test func testDroppedStageLeavesNothing() async throws {
        let (a, b, _) = engines()
        let g = try await makeGroup(creator: a, joiners: [b])
        let (ct, _) = try await a.encrypt(utf8("x"), in: g)
        _ = try await b.stageProcess(g, ct)   // dropped without confirm or discard (a "crash")
        _ = try await b.stageProcess(g, ct)
        try await b.confirm(try await b.stageProcess(g, ct))
    }

    @Test func testMemberKeys() async throws {
        let (a, b, c) = engines()
        // Creator is not first alphabetically on purpose.
        let keys = try await checkMemberKeysSortedAndIncludeSelf(creator: c, joiners: [b, a])
        #expect(keys == [alice, bob, carol].map { MemberKey(identity: $0, signatureKey: Data("fake-sig:\($0)".utf8)) })
        #expect(c.providesRealMemberKeys)
    }
}

// MARK: Live

#if canImport(MLSFFIBindings)
@Suite struct LiveStagedProcessingTests {
    private func dir() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("mls-staged-\(UUID().uuidString)")
    }
    private func live(_ identity: String, keys: SigningKeyStore = InMemorySigningKeyStore(), dir: URL? = nil) throws -> LiveMLSEngine {
        try LiveMLSEngine(identity: identity, storage: dir ?? self.dir(), keys: keys)
    }
    private func engines() throws -> (LiveMLSEngine, LiveMLSEngine, LiveMLSEngine) {
        (try live(alice), try live(bob), try live(carol))
    }

    @Test func testStageThenDiscard() async throws { let (a, b, _) = try engines(); try await checkStageThenDiscard(a: a, b: b) }
    @Test func testStageConfirmThenDuplicate() async throws { let (a, b, _) = try engines(); try await checkStageConfirmThenDuplicate(a: a, b: b) }
    @Test func testConfirmAfterEpochAdvanceIsStale() async throws {
        let (a, b, c) = try engines(); try await checkConfirmAfterEpochAdvanceIsStale(a: a, b: b, c: c)
    }
    @Test func testStagedCommit() async throws { let (a, b, c) = try engines(); try await checkStagedCommitAppliesOnConfirmOnly(a: a, b: b, c: c) }
    @Test func testWrongGroup() async throws { let (a, b, _) = try engines(); try await checkStageForWrongGroupIsMalformed(a: a, b: b) }
    @Test func testDecryptApplication() async throws { let (a, b, c) = try engines(); try await checkDecryptApplication(a: a, b: b, c: c) }

    @Test func testCrashBeforeConfirmProcessesAgain() async throws {
        let bobDir = dir(), bobKeys = InMemorySigningKeyStore()
        let a = try live(alice)
        var b: LiveMLSEngine? = try live(bob, keys: bobKeys, dir: bobDir)
        let g = try await makeGroup(creator: a, joiners: [b!])
        let (ct, _) = try await a.encrypt(utf8("survives"), in: g)
        _ = try await b!.stageProcess(g, ct)
        b = nil   // "crash": the engine goes away without confirm
        let reopened = try live(bob, keys: bobKeys, dir: bobDir)
        let staged = try await reopened.stageProcess(g, ct)
        #expect(staged.incoming == .application(group: g, sender: alice, plaintext: utf8("survives"), epoch: 1))
        try await reopened.confirm(staged)
        await #expect(throws: MLSError.duplicate) { _ = try await reopened.stageProcess(g, ct) }
    }

    @Test func testMemberKeysAreTheLeafSignatureKeys() async throws {
        let aKeys = InMemorySigningKeyStore(), bKeys = InMemorySigningKeyStore(), cKeys = InMemorySigningKeyStore()
        let a = try live(alice, keys: aKeys), b = try live(bob, keys: bKeys), c = try live(carol, keys: cKeys)
        let keys = try await checkMemberKeysSortedAndIncludeSelf(creator: c, joiners: [b, a])
        let expected = try [(alice, aKeys), (bob, bKeys), (carol, cKeys)].map { id, store in
            MemberKey(identity: id, signatureKey: try #require(try store.load(identity: id)).publicKey)
        }
        #expect(keys == expected)
        #expect(a.providesRealMemberKeys)
    }
}
#endif
