import Foundation
import Testing
@testable import Messaging
#if canImport(CryptoKit)
import CryptoKit
#endif

/// docs/protocol/test-vectors/attachment-stream.json. The Go test verifies it on Linux. Here the chunk size
/// is compared on every platform, and on Apple platforms (where AES-GCM exists) the app's own chunk framing
/// and cipher must give the vector's ciphertext digest.
@Suite("TestAttachmentVector") struct TestAttachmentVector {
    private struct Case: Decodable {
        let name: String, keyHex: String, plaintextLength: Int, plaintextSeed: String
        let ciphertextSha256Hex: String, digestB64: String
    }
    private struct Vector: Decodable { let version: Int, chunkSize: Int; let cases: [Case] }

    private func load() throws -> Vector {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("docs/protocol/test-vectors/attachment-stream.json")
        return try JSONDecoder().decode(Vector.self, from: Data(contentsOf: url))
    }

    @Test func framingConstantsMatch() throws {
        let v = try load()
        #expect(v.version == 1 && v.chunkSize == ChunkedAttachmentCipher.chunkSize)
        #expect(Set(v.cases.map(\.plaintextLength)) == [0, 1, 65_536, 65_537])
        #expect(v.cases.allSatisfy { $0.plaintextSeed == "i mod 251" && $0.keyHex.count == 64 })
    }

    #if canImport(CryptoKit)
    @Test func appCipherGivesTheVectorDigest() throws {
        let v = try load()
        let aead = CryptoKitChunkAEAD()
        for c in v.cases {
            let key = Data(stride(from: 0, to: 64, by: 2).map { i in
                UInt8(c.keyHex[c.keyHex.index(c.keyHex.startIndex, offsetBy: i)..<c.keyHex.index(c.keyHex.startIndex, offsetBy: i + 2)], radix: 16)!
            })
            let plain = Data((0..<c.plaintextLength).map { UInt8($0 % 251) })
            var sealed = Data()
            var index: UInt64 = 0
            var offset = 0
            while true {
                let end = min(offset + v.chunkSize, plain.count)
                let last = end == plain.count
                sealed += try aead.seal(
                    plain.subdata(in: offset..<end), key: key, nonce: ChunkedAttachmentCipher.nonce(index: index),
                    aad: ChunkedAttachmentCipher.aad(last: last))
                if last { break }
                offset = end
                index += 1
            }
            let digest = Data(SHA256.hash(data: sealed))
            #expect(digest.map { String(format: "%02x", $0) }.joined() == c.ciphertextSha256Hex, "\(c.name)")
            #expect(digest.base64EncodedString() == c.digestB64, "\(c.name)")
        }
    }
    #endif
}
