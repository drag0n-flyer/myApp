import Foundation
import ServerKit
import Testing
@testable import Messaging
#if canImport(CryptoKit)
import CryptoKit
#endif

/// Swift side of the shared protocol vectors in docs/protocol/test-vectors (README there).
/// Runs on Linux: `push-padding.json`. Apple only (CryptoKit HPKE): `sealed-handle.json`, `escrow.json`
/// and the optional `swift-sealed.json` writer.
private enum VectorFiles {
    static func url(_ name: String) -> URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<7 { url.deleteLastPathComponent() }
        return url.appending(path: "docs/protocol/test-vectors/\(name)")
    }

    static func load<T: Decodable>(_ name: String, as: T.Type = T.self) throws -> T {
        try JSONDecoder().decode(T.self, from: Data(contentsOf: url(name)))
    }

    static func data(hex: String) -> Data {
        var d = Data()
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            d.append(UInt8(hex[i..<j], radix: 16)!)
            i = j
        }
        return d
    }
}

@Suite("TestPushPaddingVectors") struct TestPushPaddingVectors {
    private struct Case: Decodable { let name: String; let payloadHex: String; let paddedHex: String }
    private struct File: Decodable { let version: Int; let cases: [Case] }

    @Test func paddingMatchesVectors() throws {
        let f: File = try VectorFiles.load("push-padding.json")
        #expect(f.version == 1)
        #expect(f.cases.count >= 7)
        for c in f.cases {
            let padded = VectorFiles.data(hex: c.paddedHex)
            // The over-the-limit case: the server drops the payload and pads an empty one.
            let expected = c.name.hasPrefix("over the limit") ? Data() : VectorFiles.data(hex: c.payloadHex)
            #expect(try Padding.unpad(padded) == expected, "\(c.name)")
        }
    }

    @Test func overTheLimitCaseIsAnEmptyPayload() throws {
        let f: File = try VectorFiles.load("push-padding.json")
        let over = try #require(f.cases.first { $0.name.hasPrefix("over the limit") })
        #expect(over.payloadHex.count == 3071 * 2)
        #expect(try Padding.unpad(VectorFiles.data(hex: over.paddedHex)).isEmpty)
    }
}

#if canImport(CryptoKit)
@Suite("TestSealedVectors") struct TestSealedVectors {
    private struct Case: Decodable { let plaintextJSON: String; let sealedB64: String }
    private struct Negative: Decodable { let name: String; let sealedB64: String; let info: String }
    private struct File: Decodable {
        let version: Int, info: String, recipientPrivateKeyHex: String, recipientPublicKeyB64: String
        let cases: [Case], negative: [Negative]
    }
    private struct Out: Codable { let plaintextJSON: String; let sealedB64: String }

    private static let suite = HPKE.Ciphersuite.Curve25519_SHA256_ChachaPoly

    private static func open(_ sealed: Data, key: Curve25519.KeyAgreement.PrivateKey, info: Data) throws -> Data {
        guard sealed.count >= 32 else { throw PaddingError.malformed }
        var r = try HPKE.Recipient(privateKey: key, ciphersuite: suite, info: info, encapsulatedKey: sealed.prefix(32))
        return try r.open(sealed.dropFirst(32))
    }

    private static func b64url(_ s: String) throws -> Data { try #require(Base64URLData(encoded: s)).data }

    /// Both files: header, key pair, every Go-sealed case opens, every negative case throws.
    private func checkGoSealed(_ name: String, info: String) throws {
        let f: File = try VectorFiles.load(name)
        #expect(f.version == 1)
        #expect(f.info == info)
        let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: VectorFiles.data(hex: f.recipientPrivateKeyHex))
        #expect(key.publicKey.rawRepresentation == (try Self.b64url(f.recipientPublicKeyB64)))
        #expect(!f.cases.isEmpty && f.negative.count >= 3)
        for c in f.cases {
            let pt = try Self.open(Self.b64url(c.sealedB64), key: key, info: Data(info.utf8))
            #expect(String(decoding: pt, as: UTF8.self) == c.plaintextJSON)
        }
        for n in f.negative {
            #expect(throws: (any Error).self, "\(n.name)") {
                try Self.open(Self.b64url(n.sealedB64), key: key, info: Data(n.info.utf8))
            }
        }
    }

    @Test func goSealedHandlesOpenInSwift() throws { try checkGoSealed("sealed-handle.json", info: "push-handle-v1") }
    @Test func goSealedEscrowOpensInSwift() throws { try checkGoSealed("escrow.json", info: "escrow-v1") }

    private func swiftSealedHandle() throws -> Out {
        let f: File = try VectorFiles.load("sealed-handle.json")
        let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: VectorFiles.data(hex: f.recipientPrivateKeyHex))
        let handle = PushHandle(t: "00ff", k: "alert", e: "sandbox", b: "org.example.app")
        let sealed = try PushHandleSealer.seal(handle, gatewayKey: try Self.b64url(f.recipientPublicKeyB64))
        let pt = try Self.open(sealed, key: key, info: PushHandleSealer.info)
        #expect(sealed.count == 32 + pt.count + 16)
        #expect(try JSONDecoder().decode(PushHandle.self, from: pt) == handle)
        return Out(plaintextJSON: String(decoding: pt, as: UTF8.self), sealedB64: Base64URLData(sealed).encoded)
    }

    private func swiftSealedEscrow() throws -> Out {
        let f: File = try VectorFiles.load("escrow.json")
        let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: VectorFiles.data(hex: f.recipientPrivateKeyHex))
        let plaintext = Data(try #require(f.cases.first).plaintextJSON.utf8)
        let sealed = try Escrow.seal(plaintext, serverKey: try Self.b64url(f.recipientPublicKeyB64))
        #expect(sealed.count == 32 + plaintext.count + 16)
        #expect(try Self.open(sealed, key: key, info: Escrow.info) == plaintext)
        return Out(plaintextJSON: String(decoding: plaintext, as: UTF8.self), sealedB64: Base64URLData(sealed).encoded)
    }

    @Test func swiftSealedHandleOpensWithVectorKey() throws { _ = try swiftSealedHandle() }
    @Test func swiftSealedEscrowOpensWithVectorKey() throws { _ = try swiftSealedEscrow() }

    /// Writes docs/protocol/test-vectors/swift-sealed.json for Go's `TestSwiftSealedOpensInGo`.
    /// Only with WRITE_SWIFT_VECTORS=1; the file is a CI artifact and is not committed by default.
    @Test func swiftSealedOutputForGo() throws {
        guard ProcessInfo.processInfo.environment["WRITE_SWIFT_VECTORS"] == "1" else { return }
        struct Swift: Codable { let handle: Out; let escrow: Out }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(Swift(handle: swiftSealedHandle(), escrow: swiftSealedEscrow()))
        try (data + Data("\n".utf8)).write(to: VectorFiles.url("swift-sealed.json"))
    }
}
#endif
