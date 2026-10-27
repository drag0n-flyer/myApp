#if canImport(MLSFFIBindings)
import Foundation
import Testing
import SecureMessaging

private let alice = "acct-a/dev-1"
private let bob = "acct-b/dev-1"
private let alice2 = "acct-a/dev-2"

private func utf8(_ s: String) -> Data { Data(s.utf8) }

private func tempDir() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("mls-live-\(UUID().uuidString)")
}

private func live(_ identity: String, keys: SigningKeyStore = InMemorySigningKeyStore(), dir: URL = tempDir()) throws -> LiveMLSEngine {
    try LiveMLSEngine(identity: identity, storage: dir, keys: keys)
}

private func makeGroup(creator: any MLSEngine, joiners: [any MLSEngine]) async throws -> GroupID {
    let group = try await creator.createGroup()
    var kps: [Data] = []
    for joiner in joiners { kps += try await joiner.makeKeyPackages(count: 1) }
    let bundle = try await creator.add(keyPackages: kps, to: group)
    try await creator.commitAccepted(group)
    let welcome = try #require(bundle.welcome)
    for joiner in joiners {
        #expect(try await joiner.process(welcome) == .joined(group: group, epoch: 1))
    }
    return group
}

@Suite struct LiveMLSEngineTests {
    @Test func testTwoDevices() async throws {
        let a = try live(alice), b = try live(bob)
        let g = try await a.createGroup()
        #expect(g.raw.count == 32)
        let bundle = try await a.add(keyPackages: try await b.makeKeyPackages(count: 1), to: g)
        #expect(bundle.newEpoch == 1)
        try await a.commitAccepted(g)
        // RFC 9420 MLSMessage framing: version 0x0001, wire_format 0x0003 (welcome).
        let welcome = try #require(bundle.welcome)
        #expect(Array(welcome.prefix(4)) == [0x00, 0x01, 0x00, 0x03])
        #expect(try await b.process(welcome) == .joined(group: g, epoch: 1))
        let (ct, epoch) = try await a.encrypt(utf8("hi"), in: g)
        #expect(epoch == 1)
        #expect(try await b.process(ct) == .application(group: g, sender: alice, plaintext: utf8("hi"), epoch: 1))
        let (ct2, _) = try await b.encrypt(utf8("hello back"), in: g)
        #expect(try await a.process(ct2) == .application(group: g, sender: bob, plaintext: utf8("hello back"), epoch: 1))
        #expect(Set(try await a.members(of: g)) == [alice, bob])
        #expect(try await a.exportSecret(group: g, label: "x", length: 16) == (try await b.exportSecret(group: g, label: "x", length: 16)))
    }

    @Test func testThreeDevicesAddRemove() async throws {
        let a = try live(alice), b = try live(bob), a2 = try live(alice2)
        let g = try await makeGroup(creator: a, joiners: [b, a2])
        let removal = try await a.remove(members: [alice2], from: g)
        #expect(removal.newEpoch == 2 && removal.welcome == nil)
        try await a.commitAccepted(g)
        #expect(try await b.process(removal.commit) == .commit(group: g, newEpoch: 2, removedMe: false))
        #expect(try await a2.process(removal.commit) == .commit(group: g, newEpoch: 2, removedMe: true))
        let (ct, epoch) = try await b.encrypt(utf8("after removal"), in: g)
        #expect(epoch == 2)
        await #expect(throws: MLSError.self) { _ = try await a2.process(ct) }
        #expect(try await a.process(ct) == .application(group: g, sender: bob, plaintext: utf8("after removal"), epoch: 2))
    }

    @Test(arguments: [true, false]) func testCommitRaceBothOrders(rejectFirst: Bool) async throws {
        let a = try live(alice), b = try live(bob), c = try live("acct-c/dev-1"), d = try live("acct-d/dev-1")
        let g = try await makeGroup(creator: a, joiners: [b])
        _ = try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: g)
        let winning = try await b.add(keyPackages: try await d.makeKeyPackages(count: 1), to: g)
        try await b.commitAccepted(g)
        if rejectFirst { try await a.commitRejected(g) }
        #expect(try await a.process(winning.commit) == .commit(group: g, newEpoch: 2, removedMe: false))
        if !rejectFirst { try await a.commitRejected(g) }
        #expect(try await a.epoch(of: g) == 2)
        let (ct, _) = try await a.encrypt(utf8("alice at two"), in: g)
        #expect(try await b.process(ct) == .application(group: g, sender: alice, plaintext: utf8("alice at two"), epoch: 2))
    }

    @Test func testStorageExcludedFromBackup() throws {
        let dir = tempDir()
        _ = try live(alice, dir: dir)
        #expect(try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
    }

    @Test func testSameIdentityKeepsKey() async throws {
        let keys = InMemorySigningKeyStore()
        let first = try live(alice, keys: keys), second = try live(alice, keys: keys)
        #expect(keys.saveCount == 1)
        _ = (first, second)
    }

    @Test func testLockFreeAfterEveryOperation() async throws {
        let dir = tempDir()
        let bDir = tempDir()
        let a = try live(alice, dir: dir), b = try live(bob, dir: bDir)
        let probe = CrossProcessLock(url: dir.appendingPathComponent("mls.lock"), timeout: .zero)
        func free() throws { try probe.withLock {} }
        try free()
        let kps = try await b.makeKeyPackages(count: 1); try free()
        let g = try await a.createGroup(); try free()
        let bundle = try await a.add(keyPackages: kps, to: g); try free()
        await #expect(throws: MLSError.pendingCommit) { _ = try await a.remove(members: [bob], from: g) }; try free()
        try await a.commitRejected(g); try free()
        _ = try await a.add(keyPackages: kps, to: g); try free()
        try await a.commitAccepted(g); try free()
        let (ct, _) = try await a.encrypt(utf8("x"), in: g); try free()
        _ = try await b.process(try #require(bundle.welcome)); _ = ct
        try CrossProcessLock(url: bDir.appendingPathComponent("mls.lock"), timeout: .zero).withLock {}
        await #expect(throws: MLSError.malformed) { _ = try await a.process(utf8("garbage")) }; try free()
        #expect(try await a.epoch(of: g) == 1); try free()
        _ = try await a.exportSecret(group: g, label: "l", length: 8); try free()
        _ = try await a.members(of: g); try free()
        await #expect(throws: MLSError.unknownGroup) { _ = try await a.epoch(of: GroupID(raw: Data(repeating: 9, count: 32))) }; try free()
    }

    @Test func testBusyWhenLockHeld() async throws {
        let dir = tempDir()
        let a = try LiveMLSEngine(identity: alice, storage: dir, keys: InMemorySigningKeyStore(), lockTimeout: .milliseconds(50))
        // Hold the lock on a background thread (`withLock` takes a synchronous closure), so the
        // engine call below runs in this task while the lock is taken.
        let acquired = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        let holder = CrossProcessLock(url: dir.appendingPathComponent("mls.lock"), timeout: .seconds(5))
        let thread = Thread {
            try? holder.withLock {
                acquired.signal()
                release.wait()
            }
        }
        thread.start()
        // `DispatchSemaphore.wait` is unavailable in async contexts: wait on a global queue.
        await withCheckedContinuation { continuation in
            DispatchQueue.global().async { acquired.wait(); continuation.resume() }
        }
        await #expect(throws: MLSError.busy) { _ = try await a.createGroup() }
        release.signal()
        // Once the holder lets go the engine works again.
        _ = try await LiveMLSEngine(identity: alice, storage: dir, keys: InMemorySigningKeyStore()).createGroup()
    }

    #if os(iOS)
    @Test func testStorageProtectionClass() throws {
        let dir = tempDir()
        _ = try live(alice, dir: dir)
        let attributes = try FileManager.default.attributesOfItem(atPath: dir.path)
        #expect(attributes[.protectionKey] as? FileProtectionType == .completeUntilFirstUserAuthentication)
    }
    #endif

    // One script, run against the fake and the live engine: outcomes must be identical.
    @Test func testFakeAndLiveAgreeOnContract() async throws {
        func run(_ mk: (String) throws -> any MLSEngine) async throws -> [String] {
            let a = try mk(alice), b = try mk(bob), c = try mk("acct-c/dev-1")
            var log: [String] = []
            var gid = GroupID(raw: Data())
            func norm(_ i: Incoming) -> String {
                switch i {
                case .application(let g, let s, let p, let e): return "app \(g == gid ? "G" : "?") \(s) \(String(decoding: p, as: UTF8.self)) \(e)"
                case .commit(let g, let e, let r): return "commit \(g == gid ? "G" : "?") \(e) \(r)"
                case .joined(let g, let e): return "joined \(g == gid ? "G" : "?") \(e)"
                }
            }
            func step<T>(_ name: String, _ body: () async throws -> T, show: (T) -> String = { _ in "ok" }) async -> T? {
                do { let v = try await body(); log.append("\(name): \(show(v))"); return v }
                catch { log.append("\(name): error \(error)"); return nil }
            }
            gid = try await a.createGroup()
            let kp = try await b.makeKeyPackages(count: 1)
            let bundle = try #require(await step("add", { try await a.add(keyPackages: kp, to: gid) }, show: { "epoch \($0.newEpoch) welcome \($0.welcome != nil)" }))
            _ = await step("add while pending", { try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: gid) })
            _ = await step("remove while pending", { try await a.remove(members: [bob], from: gid) })
            _ = await step("accept", { try await a.commitAccepted(gid) })
            _ = await step("accept again", { try await a.commitAccepted(gid) })
            _ = await step("reject nothing", { try await a.commitRejected(gid) })
            _ = await step("join", { try await b.process(try #require(bundle.welcome)) }, show: norm)
            let (ct, _) = try await a.encrypt(utf8("hi"), in: gid)
            _ = await step("receive", { try await b.process(ct) }, show: norm)
            _ = await step("receive twice", { try await b.process(ct) }, show: norm)
            _ = await step("own message", { try await a.process(ct) }, show: norm)
            _ = await step("garbage", { try await b.process(utf8("garbage")) }, show: norm)
            _ = await step("unknown group", { try await b.epoch(of: GroupID(raw: Data(repeating: 7, count: 32))) }, show: { "\($0)" })
            _ = await step("remove stranger", { try await a.remove(members: ["nobody/x"], from: gid) })
            // b misses a commit: a's message at epoch 2 is a gap, then catching up works.
            let second = try await a.add(keyPackages: try await c.makeKeyPackages(count: 1), to: gid)
            try await a.commitAccepted(gid)
            let (ct2, _) = try await a.encrypt(utf8("two"), in: gid)
            _ = await step("gap", { try await b.process(ct2) }, show: norm)
            _ = await step("catch up", { try await b.process(second.commit) }, show: norm)
            _ = await step("after catch up", { try await b.process(ct2) }, show: norm)
            _ = await step("epochs", { (try await a.epoch(of: gid), try await b.epoch(of: gid)) }, show: { "\($0.0) \($0.1)" })
            return log
        }
        let fake = try await run { FakeMLSEngine(identity: $0) }
        let real = try await run { try live($0) }
        #expect(fake == real)
    }
}
#endif
