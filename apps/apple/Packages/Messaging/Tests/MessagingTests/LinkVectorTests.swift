import Foundation
import Testing
@testable import Messaging
#if canImport(CryptoKit)
import CryptoKit
#endif

/// docs/protocol/test-vectors/link-v1.json: the sealed link snapshot. The plaintext must be exactly what
/// `DeviceSync.exportSnapshot` writes (sorted keys, standard base64 for `Data`); on Apple platforms the
/// sealed bytes must open with the published recipient key.
@Suite("TestLinkVector") struct TestLinkVector {
    private struct Case: Decodable { let plaintextJSON: String; let sealedB64: String }
    private struct Vector: Decodable {
        let version: Int, info: String, recipientPrivateKeyHex: String, recipientPublicKeyB64: String
        let cases: [Case]
    }

    private static func snapshot() -> LinkSnapshot {
        LinkSnapshot(
            v: 1,
            contacts: [
                .init(accountID: "a0000000-0000-4000-8000-000000000002", username: "bob", displayName: "Bob Ünal",
                      isContact: true, addedAt: 1_793_093_400_000),
                .init(accountID: "a0000000-0000-4000-8000-000000000003", username: "carol", displayName: "Carol",
                      isContact: false, addedAt: 1_793_093_465_000),
            ],
            chats: [
                .init(id: "c0000000-0000-4000-8000-000000000001", isRequest: false, muted: true),
                .init(id: "c0000000-0000-4000-8000-000000000002", isRequest: true, muted: false),
            ],
            folders: [
                .init(id: "f0000000-0000-4000-8000-000000000001", name: "Family", position: 0,
                      chatIDs: ["c0000000-0000-4000-8000-000000000001"]),
            ],
            blocked: ["a0000000-0000-4000-8000-000000000009"],
            settings: ["setting.readReceipts": Data("false".utf8)])
    }

    private static func encoded(_ s: LinkSnapshot) throws -> String {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return String(decoding: try e.encode(s), as: UTF8.self)
    }

    private func load() throws -> Vector {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("docs/protocol/test-vectors/link-v1.json")
        return try JSONDecoder().decode(Vector.self, from: Data(contentsOf: url))
    }

    @Test func plaintextIsWhatExportSnapshotWrites() throws {
        let v = try load()
        #expect(v.version == 1 && v.info == "link-v1" && !v.cases.isEmpty)
        let expectedJSON = try Self.encoded(Self.snapshot())
        #expect(v.cases[0].plaintextJSON == expectedJSON)
        for c in v.cases {
            let decoded = try JSONDecoder().decode(LinkSnapshot.self, from: Data(c.plaintextJSON.utf8))
            #expect(decoded.v == 1)
            #expect(try Self.encoded(decoded) == c.plaintextJSON)
        }
    }

    #if canImport(CryptoKit)
    @Test func swiftOpensGoVector() throws {
        let v = try load()
        let raw = Data(stride(from: 0, to: v.recipientPrivateKeyHex.count, by: 2).map { i in
            let s = v.recipientPrivateKeyHex
            return UInt8(s[s.index(s.startIndex, offsetBy: i)..<s.index(s.startIndex, offsetBy: i + 2)], radix: 16)!
        })
        let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw)
        for c in v.cases {
            var b64 = c.sealedB64.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            while b64.count % 4 != 0 { b64 += "=" }
            let sealed = try #require(Data(base64Encoded: b64))
            var r = try HPKE.Recipient(
                privateKey: key, ciphersuite: .Curve25519_SHA256_ChachaPoly, info: Data(v.info.utf8),
                encapsulatedKey: sealed.prefix(32))
            #expect(String(decoding: try r.open(sealed.dropFirst(32)), as: UTF8.self) == c.plaintextJSON)
        }
    }
    #endif
}
