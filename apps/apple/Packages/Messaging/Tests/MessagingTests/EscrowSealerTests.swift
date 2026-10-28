import Foundation
import ServerKit
import Testing
@testable import Messaging
#if canImport(CryptoKit)
import CryptoKit
#endif

@Suite("TestEscrow") struct TestEscrow {
    #if canImport(CryptoKit)
    private struct Vector: Decodable {
        let privateKey: String, publicKey: String, plaintext: String, sealed: String
    }

    private func hex(_ s: String) -> Data {
        Data(stride(from: 0, to: s.count, by: 2).map { i in
            UInt8(s[s.index(s.startIndex, offsetBy: i)..<s.index(s.startIndex, offsetBy: i + 2)], radix: 16)!
        })
    }

    private func open(_ sealed: Data, privateKey: Data) throws -> Data {
        var recipient = try HPKE.Recipient(
            privateKey: Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey), ciphersuite: Escrow.suite,
            info: Escrow.info, encapsulatedKey: sealed.prefix(32))
        return try recipient.open(sealed.dropFirst(32))
    }

    @Test func swiftOpensGoVector() throws {
        let url = try #require(Bundle.module.url(forResource: "escrow-vector", withExtension: "json", subdirectory: "Resources"))
        let v = try JSONDecoder().decode(Vector.self, from: Data(contentsOf: url))
        let sealed = try #require(Base64URLData(encoded: v.sealed)).data
        #expect(try open(sealed, privateKey: hex(v.privateKey)) == Data(v.plaintext.utf8))
    }

    @Test func roundTrip() throws {
        let key = Curve25519.KeyAgreement.PrivateKey()
        let plaintext = Data("escrow round trip".utf8)
        let sealed = try Escrow.seal(plaintext, serverKey: key.publicKey.rawRepresentation)
        #expect(sealed.count == 32 + plaintext.count + 16)
        #expect(try open(sealed, privateKey: key.rawRepresentation) == plaintext)
        #expect(try CryptoKitEscrowSealer().seal(plaintext, to: key.publicKey.rawRepresentation).count == sealed.count)
    }

    @Test func rejectsBadKey() {
        #expect(throws: EscrowError.badKey) { try Escrow.seal(Data("x".utf8), serverKey: Data(repeating: 1, count: 31)) }
    }
    #else
    @Test func failsClosedWithoutCryptoKit() {
        #expect(throws: EscrowError.unavailable) { try Escrow.seal(Data("x".utf8), serverKey: Data(repeating: 1, count: 32)) }
    }
    #endif
}
