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

    /// `2023-11-14T22:13:20.000Z` for 1700000000000. Integer arithmetic only (UTC civil date from days since
    /// 1970), so no platform date formatter can round the milliseconds.
    public static func rfc3339(milliseconds: Int64) -> String {
        func floorDiv(_ a: Int64, _ b: Int64) -> Int64 { a >= 0 ? a / b : -((-a + b - 1) / b) }
        let days = floorDiv(milliseconds, 86_400_000)
        let msOfDay = milliseconds - days * 86_400_000
        // Howard Hinnant's civil_from_days.
        let z = days + 719_468
        let era = floorDiv(z, 146_097)
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let day = doy - (153 * mp + 2) / 5 + 1
        let month = mp < 10 ? mp + 3 : mp - 9
        let year = yoe + era * 400 + (month <= 2 ? 1 : 0)
        func pad(_ v: Int64, _ n: Int) -> String { String(repeating: "0", count: max(0, n - String(v).count)) + String(v) }
        return "\(pad(year, 4))-\(pad(month, 2))-\(pad(day, 2))T\(pad(msOfDay / 3_600_000, 2)):"
            + "\(pad(msOfDay / 60_000 % 60, 2)):\(pad(msOfDay / 1000 % 60, 2)).\(pad(msOfDay % 1000, 3))Z"
    }

    #if canImport(CryptoKit)
    static let suite = HPKE.Ciphersuite(kem: .Curve25519_HKDF_SHA256, kdf: .HKDF_SHA256, aead: .chaChaPoly)
    #endif
}
