import Foundation
import AppCore

public protocol CallSignalSealer: Sendable {
    /// MLS-encrypts `payload` in the 1:1 conversation with `contactID`.
    func seal(_ payload: CallSignalPayload, to contactID: String) async throws -> Data
    /// Decrypts; throws `CallSealError.wrongSender` unless the MLS sender's account is `contactID`.
    func open(_ data: Data, from contactID: String) async throws -> CallSignalPayload
}
