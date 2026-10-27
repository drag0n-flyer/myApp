#if os(macOS) && canImport(MLSFFIBindings)
import Foundation
import Testing
import SecureMessaging

private let alice = "acct-a/dev-1"
private let bob = "acct-b/dev-1"

private func utf8(_ s: String) -> Data { Data(s.utf8) }

private func tempDir() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("mls-nse-\(UUID().uuidString)")
}

private func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

/// Mirrors server/internal/pushproto `Pad` (server spec section 7.4).
private func pad(_ payload: Data) -> Data {
    let buckets = [256, 512, 1024, 2048, 3072]
    let size = buckets.first { payload.count + 2 <= $0 } ?? 3072
    var out = Data(count: size)
    out[0] = UInt8(payload.count >> 8)
    out[1] = UInt8(payload.count & 0xff)
    out.replaceSubrange(2..<(2 + payload.count), with: payload)
    return out
}

/// Finds the helper next to the test bundle (`swift test` builds all targets), with a fallback
/// to the package's `.build` directory.
private func helperURL() throws -> URL {
    var candidates: [URL] = []
    for bundle in Bundle.allBundles + [Bundle.main] where bundle.bundleURL.pathExtension == "xctest" {
        candidates.append(bundle.bundleURL.deletingLastPathComponent().appendingPathComponent("mls-nse-sim"))
    }
    let packageRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    candidates.append(packageRoot.appendingPathComponent(".build/debug/mls-nse-sim"))
    guard let found = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0.path) }) else {
        throw MLSError.internalError("mls-nse-sim not found; tried \(candidates.map(\.path))")
    }
    return found
}

private struct HelperResult { let line: String; let rss: Int }

/// Runs the helper as its own process: the stand-in for the notification extension.
private func runHelper(storage: URL, identity: String, key: (publicKey: Data, secretKey: Data), padded: Data) async throws -> HelperResult {
    let process = Process()
    process.executableURL = try helperURL()
    process.arguments = [storage.path, identity, hex(padded)]
    process.environment = ["MLS_TEST_KEY": hex(key.publicKey) + ":" + hex(key.secretKey)]
    let output = Pipe()
    process.standardOutput = output
    process.standardError = FileHandle.nullDevice
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        process.terminationHandler = { _ in continuation.resume() }
        do { try process.run() } catch { continuation.resume(throwing: error) }
    }
    let text = String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
    let lines = text.split(separator: "\n").map(String.init)
    guard lines.count == 2, lines[1].hasPrefix("RSS "), let rss = Int(lines[1].dropFirst(4)) else {
        throw MLSError.internalError("unexpected helper output: \(text)")
    }
    return HelperResult(line: lines[0], rss: rss)
}

/// Alice and bob in one group at epoch 1. Bob's storage plays the shared app group container.
private struct Pair {
    let alice: LiveMLSEngine
    let bob: LiveMLSEngine
    let bobDir: URL
    let bobKey: (publicKey: Data, secretKey: Data)
    let group: GroupID
}

private func makePair() async throws -> Pair {
    let a = try LiveMLSEngine(identity: alice, storage: tempDir(), keys: InMemorySigningKeyStore())
    let bobDir = tempDir()
    let bobKeys = InMemorySigningKeyStore()
    let b = try LiveMLSEngine(identity: bob, storage: bobDir, keys: bobKeys)
    let group = try await a.createGroup()
    let bundle = try await a.add(keyPackages: try await b.makeKeyPackages(count: 1), to: group)
    try await a.commitAccepted(group)
    #expect(try await b.process(try #require(bundle.welcome)) == .joined(group: group, epoch: 1))
    let key = try #require(try bobKeys.load(identity: bob))
    return Pair(alice: a, bob: b, bobDir: bobDir, bobKey: key, group: group)
}

@Suite struct NSEProcessTests {
    // Every LiveMLSEngine call takes the cross-process lock itself, in the app and in the helper.
    @Test func testAppAndNSEProcessesShareState() async throws {
        let p = try await makePair()
        var cts: [Data] = []
        for text in ["one", "two", "three"] { cts.append(try await p.alice.encrypt(utf8(text), in: p.group).ciphertext) }
        #expect(try await p.bob.process(cts[0]) == .application(group: p.group, sender: alice, plaintext: utf8("one"), epoch: 1))
        let helper = try await runHelper(storage: p.bobDir, identity: bob, key: p.bobKey, padded: pad(cts[1]))
        #expect(helper.line == "OK two")
        #expect(try await p.bob.process(cts[2]) == .application(group: p.group, sender: alice, plaintext: utf8("three"), epoch: 1))
    }

    @Test func testConcurrentAppAndNSEDoNotCorrupt() async throws {
        let p = try await makePair()
        var cts: [Data] = []
        for i in 0..<20 { cts.append(try await p.alice.encrypt(utf8("m\(i)"), in: p.group).ciphertext) }
        let appHalf = Array(cts[0..<10])
        let helperHalves = [Array(cts[10..<13]), Array(cts[13..<16]), Array(cts[16..<18]), Array(cts[18..<20])]
        let engine = p.bob, dir = p.bobDir, key = p.bobKey

        var lines: [String] = []
        try await withThrowingTaskGroup(of: [String].self) { group in
            group.addTask {
                var out: [String] = []
                for ct in appHalf {
                    do {
                        if case .application(_, _, let pt, _) = try await engine.process(ct) {
                            out.append("OK " + String(decoding: pt, as: UTF8.self))
                        }
                    } catch { out.append("ERR \(error)") }
                }
                return out
            }
            for half in helperHalves {
                group.addTask {
                    var out: [String] = []
                    for ct in half {
                        out.append(try await runHelper(storage: dir, identity: bob, key: key, padded: pad(ct)).line)
                    }
                    return out
                }
            }
            for try await result in group { lines += result }
        }
        #expect(lines.count == 20)
        for line in lines {
            #expect(line.hasPrefix("OK m") || line == "ERR duplicate", "unexpected outcome: \(line)")
        }
        let (fresh, _) = try await p.alice.encrypt(utf8("after the race"), in: p.group)
        #expect(try await p.bob.process(fresh) == .application(group: p.group, sender: alice, plaintext: utf8("after the race"), epoch: 1))
    }

    @Test func testHelperMemoryBudget() async throws {
        let p = try await makePair()
        let (ct, _) = try await p.alice.encrypt(utf8("memory"), in: p.group)
        let helper = try await runHelper(storage: p.bobDir, identity: bob, key: p.bobKey, padded: pad(ct))
        #expect(helper.line == "OK memory")
        // The extension limit is about 24 MB (GOAL.md section 10); stay below 20 MB.
        #expect(helper.rss < 20_971_520, "helper peak RSS \(helper.rss) bytes")
    }

    @Test func testPaddedPushEndToEnd() async throws {
        let p = try await makePair()
        let (ct, _) = try await p.alice.encrypt(utf8("hello from MLS"), in: p.group)
        let padded = pad(ct)
        #expect([256, 512, 1024].contains(padded.count))
        let helper = try await runHelper(storage: p.bobDir, identity: bob, key: p.bobKey, padded: padded)
        #expect(helper.line == "OK hello from MLS")
    }
}
#endif
