#if canImport(CryptoKit)
import CryptoKit
import Foundation
import Testing
@testable import ServerKit

private struct Vector: Decodable {
    let privateKey: String
    let publicKey: String
    let plaintext: String
    let sealed: String
}

private func data(hex: String) -> Data {
    var d = Data()
    var i = hex.startIndex
    while i < hex.endIndex {
        let j = hex.index(i, offsetBy: 2)
        d.append(UInt8(hex[i..<j], radix: 16)!)
        i = j
    }
    return d
}

@Suite struct PushSealTests {
    @Test func opensGoSealedVector() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<7 { url.deleteLastPathComponent() }
        url.append(path: "docs/protocol/vectors/push-handle-v1.json")
        let v = try JSONDecoder().decode(Vector.self, from: Data(contentsOf: url))
        let sealed = try #require(Base64URLData(encoded: v.sealed)).data
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: data(hex: v.privateKey))
        #expect(priv.publicKey.rawRepresentation == data(hex: v.publicKey))
        var r = try HPKE.Recipient(privateKey: priv, ciphersuite: .Curve25519_SHA256_ChachaPoly,
                                   info: PushHandleSealer.info, encapsulatedKey: sealed.prefix(32))
        let pt = try r.open(sealed.dropFirst(32))
        #expect(String(decoding: pt, as: UTF8.self) == v.plaintext)
    }

    @Test func sealRoundTrip() throws {
        let priv = Curve25519.KeyAgreement.PrivateKey()
        let h = PushHandle(t: "00ff", k: "voip", e: "production", b: "org.example.app")
        let sealed = try PushHandleSealer.seal(h, gatewayKey: priv.publicKey.rawRepresentation)
        var r = try HPKE.Recipient(privateKey: priv, ciphersuite: .Curve25519_SHA256_ChachaPoly,
                                   info: PushHandleSealer.info, encapsulatedKey: sealed.prefix(32))
        let pt = try r.open(sealed.dropFirst(32))
        #expect(try JSONDecoder().decode(PushHandle.self, from: pt) == h)
    }

    @Test func handleJSONKeys() throws {
        let json = try JSONEncoder().encode(PushHandle(t: "a", k: "alert", e: "sandbox", b: "x"))
        let obj = try #require(JSONSerialization.jsonObject(with: json) as? [String: Any])
        #expect(obj.keys.sorted() == ["b", "e", "k", "t"])
        #expect(PushHandle.hex(Data([0, 255])) == "00ff")
    }
}
#endif
