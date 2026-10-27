#if canImport(CryptoKit)
import AppCore
import CryptoKit
import Foundation
import ServerKit

/// Test-only authenticator (never linked into an app target). P-256, attestation `none`.
public actor SoftwarePasskey: PasskeyAuthenticating {
    private let origin: String
    private var key: P256.Signing.PrivateKey?
    private var credentialID = Data()
    private var userID = Data()
    private var signCount: UInt32 = 0

    public init(origin: String) { self.origin = origin }

    public var publicKey: P256.Signing.PublicKey? { key?.publicKey }

    public func register(_ options: CreationOptions) async throws(AccountError) -> RegistrationCredential {
        let k = P256.Signing.PrivateKey()
        key = k
        credentialID = Data((0..<16).map { _ in UInt8.random(in: .min ... .max) })
        userID = options.userID
        let raw = k.publicKey.rawRepresentation   // 0x04-less x ‖ y, 64 bytes
        let cose = Self.coseKey(x: raw.prefix(32), y: raw.suffix(32))
        var credData = Data(repeating: 0, count: 16)   // AAGUID
        credData += [UInt8(credentialID.count >> 8), UInt8(credentialID.count & 0xff)]
        credData += credentialID
        credData += cose
        let authData = Self.authData(rpID: options.rpID, flags: 0x45, signCount: 0) + credData
        let clientData = Self.clientDataJSON(type: "webauthn.create", challenge: options.challenge, origin: origin)
        var att = CBOR.map(3)
        att += CBOR.text("fmt") + CBOR.text("none")
        att += CBOR.text("attStmt") + CBOR.map(0)
        att += CBOR.text("authData") + CBOR.bytes(authData)
        return RegistrationCredential(id: credentialID, clientDataJSON: clientData, attestationObject: att)
    }

    public func assert(_ options: RequestOptions) async throws(AccountError) -> AssertionCredential {
        guard let key else { throw .passkeyUnavailable }
        signCount += 1
        let authData = Self.authData(rpID: options.rpID, flags: 0x05, signCount: signCount)
        let clientData = Self.clientDataJSON(type: "webauthn.get", challenge: options.challenge, origin: origin)
        let signed = authData + Data(SHA256.hash(data: clientData))
        guard let sig = try? key.signature(for: signed) else { throw .passkeyUnavailable }
        return AssertionCredential(id: credentialID, clientDataJSON: clientData, authenticatorData: authData,
                                   signature: sig.derRepresentation, userHandle: userID)
    }

    // MARK: Encoding

    static func clientDataJSON(type: String, challenge: Data, origin: String) -> Data {
        Data(#"{"type":"\#(type)","challenge":"\#(Base64URLData(challenge).encoded)","origin":"\#(origin)"}"#.utf8)
    }

    static func authData(rpID: String, flags: UInt8, signCount: UInt32) -> Data {
        var d = Data(SHA256.hash(data: Data(rpID.utf8)))
        d.append(flags)
        d += withUnsafeBytes(of: signCount.bigEndian) { Data($0) }
        return d
    }

    /// COSE_Key `{1:2, 3:-7, -1:1, -2:x, -3:y}` (EC2, ES256, P-256).
    public static func coseKey(x: Data, y: Data) -> Data {
        var d = CBOR.map(5)
        d += CBOR.uint(1) + CBOR.uint(2)
        d += CBOR.uint(3) + CBOR.negative(7)    // -7
        d += CBOR.negative(1) + CBOR.uint(1)    // -1: 1
        d += CBOR.negative(2) + CBOR.bytes(x)   // -2: x
        d += CBOR.negative(3) + CBOR.bytes(y)   // -3: y
        return d
    }
}

/// Just enough CBOR for WebAuthn.
enum CBOR {
    private static func head(_ major: UInt8, _ n: Int) -> Data {
        switch n {
        case 0..<24: return Data([major << 5 | UInt8(n)])
        case 24..<256: return Data([major << 5 | 24, UInt8(n)])
        default: return Data([major << 5 | 25, UInt8(n >> 8), UInt8(n & 0xff)])
        }
    }
    static func uint(_ n: Int) -> Data { head(0, n) }
    /// Encodes the value -n (n >= 1).
    static func negative(_ n: Int) -> Data { head(1, n - 1) }
    static func bytes(_ b: Data) -> Data { head(2, b.count) + b }
    static func text(_ s: String) -> Data { head(3, s.utf8.count) + Data(s.utf8) }
    static func map(_ count: Int) -> Data { head(5, count) }
}
#endif
