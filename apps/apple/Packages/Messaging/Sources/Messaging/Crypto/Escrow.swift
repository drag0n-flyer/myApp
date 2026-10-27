import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif

public enum EscrowError: Error, Equatable, Sendable {
    /// CryptoKit is not available on this platform, so sealing fails closed.
    case unavailable
    /// The server key is not a 32-byte X25519 public key.
    case badKey
}

/// Escrow sealing (Design 10, server design "escrow-v1"): HPKE base mode with DHKEM(X25519,
/// HKDF-SHA256), HKDF-SHA256 and ChaCha20-Poly1305, `info = "escrow-v1"`.
/// Output: `encapsulatedKey (32) || ciphertext`, the framing Go's `hpke.Open` expects.
/// Owner review: cryptography.
public enum Escrow {
    public static let info = Data("escrow-v1".utf8)

    public static func seal(_ plaintext: Data, serverKey: Data) throws -> Data {
        #if canImport(CryptoKit)
        guard serverKey.count == 32, let key = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: serverKey) else {
            throw EscrowError.badKey
        }
        var sender = try HPKE.Sender(recipientKey: key, ciphersuite: suite, info: info)
        let ciphertext = try sender.seal(plaintext)
        return sender.encapsulatedKey + ciphertext
        #else
        throw EscrowError.unavailable
        #endif
    }

    #if canImport(CryptoKit)
    static let suite = HPKE.Ciphersuite(kem: .Curve25519_HKDF_SHA256, kdf: .HKDF_SHA256, aead: .chaChaPoly)
    #endif
}
