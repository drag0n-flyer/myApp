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

    /// Design 10: `{"conversationID","senderAccountID","sentAt","body":<envelope>}`. `sentAt` is an RFC 3339 UTC
    /// string with milliseconds, derived from the envelope's `at`.
    public static func plaintext(for envelope: ContentEnvelope, conversationID: String, senderAccountID: String) throws -> Data {
        struct Plain: Encodable {
            let conversationID: String
            let senderAccountID: String
            let sentAt: String
            let body: ContentEnvelope
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(Plain(
            conversationID: conversationID, senderAccountID: senderAccountID, sentAt: rfc3339(milliseconds: envelope.at),
            body: envelope))
    }

    /// `2023-11-14T22:13:20.000Z` for 1700000000000.
    public static func rfc3339(milliseconds: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.string(from: Date(timeIntervalSince1970: Double(milliseconds) / 1000))
    }

    #if canImport(CryptoKit)
    static let suite = HPKE.Ciphersuite(kem: .Curve25519_HKDF_SHA256, kdf: .HKDF_SHA256, aead: .chaChaPoly)
    #endif
}
