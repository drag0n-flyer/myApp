import Foundation
import Testing
#if canImport(Darwin)
import Darwin
#endif
@testable import Messaging

/// Toy AEAD for Linux, where CryptoKit does not exist. NOT secure; it only has the same shape as
/// AES-GCM (ciphertext ‖ 16-byte tag, tag covers key, nonce, AAD and ciphertext) so the chunk
/// framing in `ChunkedAttachmentCipher` is exercised by the same tests on every platform.
struct ToyChunkAEAD: ChunkAEAD {
    private static func mix(_ seed: UInt64, _ parts: [Data]) -> UInt64 {
        var h = seed ^ 0xcbf29ce484222325
        for part in parts { for b in part { h = (h ^ UInt64(b)) &* 0x100000001b3 }; h = (h ^ 0xff) &* 0x100000001b3 }
        return h
    }

    private static func xor(_ data: Data, key: Data, nonce: Data) -> Data {
        let pad = [UInt8](key + nonce)
        var out = Data(count: data.count)
        data.withUnsafeBytes { src in
            out.withUnsafeMutableBytes { dst in
                for i in 0..<src.count { dst[i] = src[i] ^ pad[i % pad.count] ^ UInt8(truncatingIfNeeded: i &* 31) }
            }
        }
        return out
    }

    private static func tag(_ cipher: Data, key: Data, nonce: Data, aad: Data) -> Data {
        var t = Data()
        for seed in [UInt64(1), 2] {
            var h = mix(seed, [key, nonce, aad, cipher]).bigEndian
            t.append(Data(bytes: &h, count: 8))
        }
        return t
    }

    func seal(_ plaintext: Data, key: Data, nonce: Data, aad: Data) throws -> Data {
        let c = Self.xor(plaintext, key: key, nonce: nonce)
        return c + Self.tag(c, key: key, nonce: nonce, aad: aad)
    }

    func open(_ sealed: Data, key: Data, nonce: Data, aad: Data) throws -> Data {
        guard sealed.count >= 16 else { throw AttachmentError.format }
        let c = sealed.prefix(sealed.count - 16)
        guard Data(sealed.suffix(16)) == Self.tag(Data(c), key: key, nonce: nonce, aad: aad) else { throw AttachmentError.authentication }
        return Self.xor(Data(c), key: key, nonce: nonce)
    }
}

private func ciphers() -> [(String, ChunkedAttachmentCipher)] {
    var list: [(String, ChunkedAttachmentCipher)] = [("toy", ChunkedAttachmentCipher(aead: ToyChunkAEAD()))]
    #if canImport(CryptoKit)
    list.append(("cryptokit", ChunkedAttachmentCipher(aead: CryptoKitChunkAEAD())))
    #endif
    return list
}

private let chunk = ChunkedAttachmentCipher.chunkSize
private let sealedChunk = ChunkedAttachmentCipher.sealedChunkSize

private final class Scratch {
    let dir: URL
    init() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("att-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }
    deinit { try? FileManager.default.removeItem(at: dir) }
    func url(_ name: String) -> URL { dir.appendingPathComponent(name) }
    func write(_ data: Data, _ name: String) -> URL { let u = url(name); try! data.write(to: u); return u }
}

private func pattern(_ n: Int) -> Data { Data((0..<n).map { UInt8(truncatingIfNeeded: $0 &* 7 &+ $0 >> 8) }) }

/// Encrypts `plain` and returns the scratch dir, ciphertext URL, key and digest.
private func encrypt(_ c: ChunkedAttachmentCipher, _ plain: Data, _ s: Scratch, name: String = "c") throws -> (url: URL, key: Data, digest: Data) {
    let r = try c.encrypt(file: s.write(plain, name + ".p"), to: s.url(name))
    return (s.url(name), r.key, r.digest)
}

@Suite("TestAttachmentCipher", .serialized) struct TestAttachmentCipher {
    @Test func roundTripSizes() throws {
        let cases: [(Int, Int)] = [(0, 16), (1, 17), (65_535, 65_551), (65_536, 65_552), (65_537, 65_569), (200_000, 200_064)]
        for (name, c) in ciphers() {
            for (plainSize, cipherSize) in cases {
                let s = Scratch()
                let plain = pattern(plainSize)
                let r = try c.encrypt(file: s.write(plain, "p"), to: s.url("c"))
                #expect(r.size == Int64(plainSize), "\(name) \(plainSize)")
                #expect(r.cipherSize == Int64(cipherSize), "\(name) \(plainSize)")
                #expect(try Data(contentsOf: s.url("c")).count == cipherSize, "\(name) \(plainSize)")
                let onDisk = try Data(contentsOf: s.url("c"))
                #expect(r.key.count == 32 && r.digest == PortableSHA256.hash(onDisk))
                try c.decrypt(file: s.url("c"), to: s.url("out"), key: r.key, digest: r.digest)
                #expect(try Data(contentsOf: s.url("out")) == plain, "\(name) \(plainSize)")
            }
        }
    }

    @Test func freshKeyPerFile() throws {
        let c = ciphers()[0].1
        let s = Scratch()
        let p = s.write(pattern(10), "p")
        #expect(try c.encrypt(file: p, to: s.url("a")).key != c.encrypt(file: p, to: s.url("b")).key)
    }

    @Test func nonceAndAADLayout() {
        #expect(ChunkedAttachmentCipher.nonce(index: 0) == Data(repeating: 0, count: 12))
        #expect(ChunkedAttachmentCipher.nonce(index: 0x0102_0304_0506_0708) == Data([0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8]))
        #expect(ChunkedAttachmentCipher.aad(last: true) == Data([1]))
        #expect(ChunkedAttachmentCipher.aad(last: false) == Data([0]))
    }

    @Test func truncationDetected() throws {
        for (name, c) in ciphers() {
            let s = Scratch()
            let e = try encrypt(c, pattern(3 * chunk - 100), s)   // 3 chunks
            let bytes = try Data(contentsOf: e.url)
            #expect(bytes.count == 2 * sealedChunk + (chunk - 100) + 16)
            // Drop the last chunk; digest recomputed so the AEAD check, not the digest, must catch it.
            let cut = Data(bytes.prefix(2 * sealedChunk))
            let u = s.write(cut, "cut")
            #expect(throws: AttachmentError.authentication, "\(name)") {
                try c.decrypt(file: u, to: s.url("out"), key: e.key, digest: PortableSHA256.hash(cut))
            }
            #expect(!FileManager.default.fileExists(atPath: s.url("out").path))
            // Cutting inside a chunk fails too.
            let mid = Data(bytes.prefix(sealedChunk + 5000))
            #expect(throws: AttachmentError.authentication) {
                try c.decrypt(file: s.write(mid, "mid"), to: s.url("out"), key: e.key, digest: PortableSHA256.hash(mid))
            }
        }
    }

    @Test func truncationAtChunkBoundaryOfFullChunks() throws {
        // Exactly two full chunks: the second is the final one. Dropping it must fail.
        for (name, c) in ciphers() {
            let s = Scratch()
            let e = try encrypt(c, pattern(2 * chunk), s)
            let bytes = try Data(contentsOf: e.url)
            #expect(bytes.count == 2 * sealedChunk)
            let cut = Data(bytes.prefix(sealedChunk))
            #expect(throws: AttachmentError.authentication, "\(name)") {
                try c.decrypt(file: s.write(cut, "cut"), to: s.url("out"), key: e.key, digest: PortableSHA256.hash(cut))
            }
        }
    }

    @Test func appendedChunkDetected() throws {
        for (_, c) in ciphers() {
            let s = Scratch()
            let e = try encrypt(c, pattern(chunk + 10), s)
            let bytes = try Data(contentsOf: e.url)
            let longer = bytes + bytes.prefix(sealedChunk)
            #expect(throws: AttachmentError.authentication) {
                try c.decrypt(file: s.write(longer, "l"), to: s.url("out"), key: e.key, digest: PortableSHA256.hash(longer))
            }
        }
    }

    @Test func chunkSwapDetected() throws {
        for (name, c) in ciphers() {
            let s = Scratch()
            let e = try encrypt(c, pattern(3 * chunk), s)
            var bytes = try Data(contentsOf: e.url)
            let a = Data(bytes[0..<sealedChunk]), b = Data(bytes[sealedChunk..<2 * sealedChunk])
            bytes.replaceSubrange(0..<sealedChunk, with: b)
            bytes.replaceSubrange(sealedChunk..<2 * sealedChunk, with: a)
            #expect(throws: AttachmentError.authentication, "\(name)") {
                try c.decrypt(file: s.write(bytes, "sw"), to: s.url("out"), key: e.key, digest: PortableSHA256.hash(bytes))
            }
        }
    }

    @Test func chunkFromAnotherFileDetected() throws {
        for (name, c) in ciphers() {
            let s = Scratch()
            let one = try encrypt(c, pattern(3 * chunk), s, name: "one")
            let two = try encrypt(c, Data(repeating: 9, count: 3 * chunk), s, name: "two")
            var bytes = try Data(contentsOf: one.url)
            let other = try Data(contentsOf: two.url)
            bytes.replaceSubrange(sealedChunk..<2 * sealedChunk, with: other[sealedChunk..<2 * sealedChunk])
            #expect(throws: AttachmentError.authentication, "\(name)") {
                try c.decrypt(file: s.write(bytes, "mix"), to: s.url("out"), key: one.key, digest: PortableSHA256.hash(bytes))
            }
        }
    }

    @Test func digestChecked() throws {
        for (name, c) in ciphers() {
            let s = Scratch()
            let e = try encrypt(c, pattern(chunk + 500), s)
            let bytes = try Data(contentsOf: e.url)
            for position in [0, 100, sealedChunk - 1, sealedChunk, bytes.count - 1] {
                var flipped = bytes
                flipped[position] ^= 0x01
                let u = s.write(flipped, "f")
                #expect(throws: AttachmentError.digest, "\(name) \(position)") {
                    try c.decrypt(file: u, to: s.url("out"), key: e.key, digest: e.digest)
                }
                #expect(!FileManager.default.fileExists(atPath: s.url("out").path))
                #expect(!FileManager.default.fileExists(atPath: s.url("out.part").path))
            }
        }
    }

    @Test func wrongKeyAndBadKeyRejected() throws {
        for (_, c) in ciphers() {
            let s = Scratch()
            let e = try encrypt(c, pattern(100), s)
            #expect(throws: AttachmentError.authentication) {
                try c.decrypt(file: e.url, to: s.url("out"), key: Data(repeating: 1, count: 32), digest: e.digest)
            }
            #expect(throws: AttachmentError.badKey) {
                try c.decrypt(file: e.url, to: s.url("out"), key: Data(repeating: 1, count: 16), digest: e.digest)
            }
            #expect(!FileManager.default.fileExists(atPath: s.url("out.part").path))
        }
    }

    @Test func emptyAndTinyCiphertextRejected() throws {
        for (_, c) in ciphers() {
            let s = Scratch()
            for n in [0, 15] {
                let d = Data(count: n)
                #expect(throws: AttachmentError.format) {
                    try c.decrypt(file: s.write(d, "x"), to: s.url("out"), key: Data(repeating: 1, count: 32), digest: PortableSHA256.hash(d))
                }
            }
        }
    }

    @Test func emptyFileIsOneEmptyLastChunk() throws {
        for (name, c) in ciphers() {
            let s = Scratch()
            let e = try encrypt(c, Data(), s)
            #expect(try Data(contentsOf: e.url).count == 16, "\(name)")
            // An empty last chunk cannot be passed off as a non-final chunk, nor vice versa.
            try c.decrypt(file: e.url, to: s.url("out"), key: e.key, digest: e.digest)
            #expect(try Data(contentsOf: s.url("out")).isEmpty)
        }
    }

    @Test func streamingSHA256MatchesPortable() {
        for n in [0, 1, 55, 56, 63, 64, 65, 119, 120, 128, 1000, 65_552] {
            let data = pattern(n)
            var h = StreamingSHA256()
            var i = 0
            var step = 1
            while i < data.count { let e = min(i + step, data.count); h.update(data[i..<e]); i = e; step = step * 2 + 1 }
            #expect(h.finalize() == PortableSHA256.hash(data), "\(n)")
        }
    }

    // MARK: memory

    /// Process high-water mark of resident memory, in bytes (0 if unavailable).
    private func peakResidentBytes() -> Int {
        #if canImport(Darwin)
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count) }
        }
        return kr == KERN_SUCCESS ? Int(info.resident_size_max) : 0
        #else
        guard let s = try? String(contentsOfFile: "/proc/self/status", encoding: .utf8),
              let line = s.split(separator: "\n").first(where: { $0.hasPrefix("VmHWM:") }),
              let kb = line.split(separator: " ").dropFirst().first.flatMap({ Int($0) }) else { return 0 }
        return kb * 1024
        #endif
    }

    @Test func streamsWithoutLoadingFile() throws {
        #if canImport(CryptoKit)
        let c = ChunkedAttachmentCipher(aead: CryptoKitChunkAEAD())
        let total = 50 * 1024 * 1024
        #else
        let c = ChunkedAttachmentCipher(aead: ToyChunkAEAD())
        let total = 20 * 1024 * 1024
        #endif
        let s = Scratch()
        let src = s.url("big")
        _ = FileManager.default.createFile(atPath: src.path, contents: nil)
        let h = try FileHandle(forWritingTo: src)
        let block = pattern(1 << 20)
        for _ in 0..<(total >> 20) { try h.write(contentsOf: block) }
        try h.close()

        let before = peakResidentBytes()
        #expect(before > 0)
        let r = try c.encrypt(file: src, to: s.url("big.enc"))
        try c.decrypt(file: s.url("big.enc"), to: s.url("big.out"), key: r.key, digest: r.digest)
        let peak = peakResidentBytes()
        #expect(r.size == Int64(total))
        #expect(try FileManager.default.attributesOfItem(atPath: s.url("big.out").path)[.size] as? Int == total)
        #expect(peak - before < 4 * 1024 * 1024, "grew by \(peak - before) bytes")
    }
}
