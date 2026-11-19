import Foundation
import Testing
@testable import Messaging
#if canImport(CryptoKit)
import CryptoKit
#endif

/// docs/protocol/test-vectors/archive-v1.json. On every platform the framing and every negative case
/// run with a toy AEAD (Linux has no CryptoKit); on Apple platforms the real AES-256-GCM must also
/// reproduce the vector's ciphertext byte for byte and reject every negative case.
@Suite("TestArchiveVector") struct TestArchiveVector {
    private struct Case: Decodable {
        let name: String, plaintextLength: Int, ciphertextLength: Int, ciphertextSha256Hex: String
        let ciphertextHex: String?
    }
    private struct Negative: Decodable {
        let name: String, `case`: String, op: String
        let a: Int?, b: Int?, hex: String?, keyHex: String?
    }
    private struct Vector: Decodable {
        let version: Int, magic: String, chunkSize: Int, keyHex: String, noncePrefixHex: String
        let cases: [Case], negative: [Negative]
    }

    private func load() throws -> Vector {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("docs/protocol/test-vectors/archive-v1.json")
        return try JSONDecoder().decode(Vector.self, from: Data(contentsOf: url))
    }

    private func unhex(_ s: String) -> Data {
        Data(stride(from: 0, to: s.count, by: 2).map { i in
            UInt8(s[s.index(s.startIndex, offsetBy: i)..<s.index(s.startIndex, offsetBy: i + 2)], radix: 16)!
        })
    }

    private func plain(_ n: Int) -> Data { Data((0..<n).map { UInt8($0 % 251) }) }

    private func records(_ c: Data) -> [Data] {
        var out: [Data] = []
        var pos = 16
        while pos < c.count {
            let n = c[pos..<(pos + 4)].reduce(0) { ($0 << 8) | Int($1) }
            out.append(c.subdata(in: pos..<(pos + 4 + n)))
            pos += 4 + n
        }
        return out
    }

    private func mutate(_ base: Data, _ n: Negative) throws -> Data {
        var d = base
        switch n.op {
        case "none": break
        case "dropLastRecord": d = d.prefix(d.count - records(d).last!.count)
        case "swapRecords":
            var r = records(d)
            r.swapAt(n.a ?? 0, n.b ?? 0)
            d = d.prefix(16) + r.reduce(Data()) { $0 + $1 }
        case "flipBit": d[n.a ?? 0] ^= UInt8(1 << (n.b ?? 0))
        case "appendHex": d += unhex(n.hex!)
        case "emptyInput": d = Data()
        default: Issue.record("unknown op \(n.op)")
        }
        return d
    }

    private func check(_ cipher: ArchiveCipher, real: Bool) throws {
        let v = try load()
        #expect(v.version == 1 && v.magic == "APPARCH1" && v.chunkSize == ArchiveCipher.chunkSize && v.cases.count == 4)
        let key = unhex(v.keyHex), prefix = unhex(v.noncePrefixHex)
        var cts: [String: Data] = [:]
        for c in v.cases {
            let p = plain(c.plaintextLength)
            let ct = try cipher.seal(p, key: key, prefix: prefix)
            #expect(ct.count == c.ciphertextLength, "\(c.name)")
            #expect(try cipher.open(ct, key: key) == p, "\(c.name)")
            if real {
                #if canImport(CryptoKit)
                #expect(SHA256.hash(data: ct).map { String(format: "%02x", $0) }.joined() == c.ciphertextSha256Hex, "\(c.name)")
                #endif
                if let h = c.ciphertextHex { #expect(ct == unhex(h), "\(c.name)") }
            }
            cts[c.name] = ct
        }
        #expect(v.negative.count >= 10)
        for n in v.negative {
            let data = try mutate(cts[n.case]!, n)
            let k = n.keyHex.map(unhex) ?? key
            #expect(throws: ArchiveError.self, "\(n.name)") { try cipher.open(data, key: k) }
        }
    }

    @Test func framingWithToyAEAD() throws { try check(ArchiveCipher(aead: ToyChunkAEAD()), real: false) }

    #if canImport(CryptoKit)
    @Test func realAESGCMMatchesTheVector() throws { try check(ArchiveCipher(aead: CryptoKitChunkAEAD()), real: true) }
    #endif

    @Test func randomPrefixRoundTripAndIncrementalSealer() throws {
        let c = ArchiveCipher(aead: ToyChunkAEAD())
        let key = ArchiveCipher.newKey()
        let p = plain(2 * ArchiveCipher.chunkSize + 17)
        let ct = try c.seal(p, key: key)
        #expect(records(ct).count == 3)
        #expect(try c.open(ct, key: key) == p)
        // exact multiples have no trailing empty chunk
        #expect(records(try c.seal(plain(ArchiveCipher.chunkSize), key: key)).count == 1)
        var s = try c.sealer(key: key)
        var inc = s.header
        inc += try s.seal(chunk: p.prefix(ArchiveCipher.chunkSize), last: false)
        inc += try s.seal(chunk: p.dropFirst(ArchiveCipher.chunkSize).prefix(ArchiveCipher.chunkSize), last: false)
        inc += try s.seal(chunk: p.suffix(17), last: true)
        #expect(try c.open(inc, key: key) == p)
        #expect(throws: ArchiveError.badKey) { try c.open(ct, key: Data(count: 16)) }
    }
}
