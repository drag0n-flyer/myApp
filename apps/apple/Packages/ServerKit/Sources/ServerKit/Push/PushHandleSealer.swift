#if canImport(CryptoKit)
import CryptoKit
import Foundation

public enum PushHandleSealer {
    public static let info = Data("push-handle-v1".utf8)

    /// HPKE single-shot seal to the gateway key: X25519, HKDF-SHA256, ChaCha20-Poly1305, empty AAD.
    /// Returns `encapsulatedKey ‖ ciphertext`, the framing Go's `hpke.Seal` produces.
    public static func seal(_ h: PushHandle, gatewayKey: Data) throws -> Data {
        let key = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: gatewayKey)
        var sender = try HPKE.Sender(recipientKey: key, ciphersuite: .Curve25519_SHA256_ChachaPoly, info: info)
        let plaintext = try JSONEncoder().encode(h)
        let ciphertext = try sender.seal(plaintext)
        return sender.encapsulatedKey + ciphertext
    }
}
#endif
