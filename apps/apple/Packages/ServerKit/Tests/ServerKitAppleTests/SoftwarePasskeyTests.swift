#if canImport(CryptoKit)
import CryptoKit
import Foundation
import Testing
@testable import ServerKit
import ServerKitTesting

@Suite struct SoftwarePasskeyTests {
    @Test func softwareCBOR() {
        let cose = SoftwarePasskey.coseKey(x: Data(repeating: 1, count: 32), y: Data(repeating: 2, count: 32))
        #expect(Array(cose.prefix(10)) == [0xa5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20])
        #expect(cose.count == 10 + 32 + 3 + 32)
    }

    @Test func softwareAssertionVerifies() async throws {
        let pk = SoftwarePasskey(origin: "https://chat.test")
        let creation = CreationOptions(challenge: Data(0..<16), rpID: "chat.test", rpName: "Chat",
                                       userID: Data(16..<32), userName: "ana", userDisplayName: "ana")
        let reg = try await pk.register(creation)
        #expect(!reg.attestationObject.isEmpty)
        let options = RequestOptions(challenge: Data(32..<48), rpID: "chat.test")
        let a = try await pk.assert(options)
        #expect(a.userHandle == creation.userID)
        let signed = a.authenticatorData + Data(SHA256.hash(data: a.clientDataJSON))
        let sig = try P256.Signing.ECDSASignature(derRepresentation: a.signature)
        let pub = try #require(await pk.publicKey)
        #expect(pub.isValidSignature(sig, for: signed))
        #expect(a.authenticatorData[32] == 0x05)
        #expect(Data(a.authenticatorData.prefix(32)) == Data(SHA256.hash(data: Data("chat.test".utf8))))
    }
}
#endif
