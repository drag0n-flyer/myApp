import AppCore
import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif
#if canImport(LocalAuthentication)
import LocalAuthentication
#endif

/// Device linking crypto: the new device makes an X25519 key pair for the QR code; the approver seals
/// the link snapshot to that key (HPKE, X25519 / HKDF-SHA256 / ChaCha20-Poly1305, `info = "link-v1"`,
/// result `encapsulatedKey ‖ ciphertext`, the framing Go's `hpke.Seal` produces).
public protocol LinkCrypto: Sendable {
    func makeKeyPair() throws(AccountError) -> (publicKey: Data, privateKey: Data)
    func seal(_ snapshot: Data, to publicKey: Data) throws(AccountError) -> Data
    func open(_ sealed: Data, privateKey: Data) throws(AccountError) -> Data
}

public struct DeviceLinkCrypto: LinkCrypto {
    public static let info = Data("link-v1".utf8)

    public init() {}

    #if canImport(CryptoKit)
    public func makeKeyPair() throws(AccountError) -> (publicKey: Data, privateKey: Data) {
        let k = Curve25519.KeyAgreement.PrivateKey()
        return (k.publicKey.rawRepresentation, k.rawRepresentation)
    }

    public func seal(_ snapshot: Data, to publicKey: Data) throws(AccountError) -> Data {
        do {
            let key = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKey)
            var sender = try HPKE.Sender(recipientKey: key, ciphersuite: .Curve25519_SHA256_ChachaPoly, info: Self.info)
            let ciphertext = try sender.seal(snapshot)
            return sender.encapsulatedKey + ciphertext
        } catch {
            throw .server
        }
    }

    public func open(_ sealed: Data, privateKey: Data) throws(AccountError) -> Data {
        do {
            let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
            var r = try HPKE.Recipient(privateKey: key, ciphersuite: .Curve25519_SHA256_ChachaPoly,
                                       info: Self.info, encapsulatedKey: sealed.prefix(32))
            return try r.open(sealed.dropFirst(32))
        } catch {
            throw .server
        }
    }
    #else
    public func makeKeyPair() throws(AccountError) -> (publicKey: Data, privateKey: Data) { throw .server }
    public func seal(_ snapshot: Data, to publicKey: Data) throws(AccountError) -> Data { throw .server }
    public func open(_ sealed: Data, privateKey: Data) throws(AccountError) -> Data { throw .server }
    #endif
}

/// Asks the person to confirm with Face ID, Touch ID or the device passcode.
public protocol BiometricConfirming: Sendable {
    func confirm(reason: String) async throws(AccountError)
}

public struct DeviceBiometrics: BiometricConfirming {
    public init() {}

    public func confirm(reason: String) async throws(AccountError) {
        #if canImport(LocalAuthentication)
        let context = LAContext()
        var probe: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &probe) else { throw .biometricsUnavailable }
        let ok: Bool
        do {
            ok = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
        } catch {
            if let e = error as? LAError, [.userCancel, .systemCancel, .appCancel].contains(e.code) { throw .cancelled }
            throw .biometricsUnavailable
        }
        guard ok else { throw .biometricsUnavailable }
        #else
        throw .biometricsUnavailable
        #endif
    }
}
