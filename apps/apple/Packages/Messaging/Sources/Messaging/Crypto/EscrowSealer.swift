#if canImport(CryptoKit)
import Foundation

/// The production `EscrowSealer`: `Escrow.seal` with the server's key.
public struct CryptoKitEscrowSealer: EscrowSealer {
    public init() {}
    public func seal(_ plaintext: Data, to recipientKey: Data) throws -> Data {
        try Escrow.seal(plaintext, serverKey: recipientKey)
    }
}
#endif
