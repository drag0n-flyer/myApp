import Foundation
import AppCore

/// Stands in until plan 7's `LiveChatStore` provides the MLS sealer. It fails closed: nothing is ever sent
/// unencrypted, every call ends as failed, and every incoming payload is treated as undecryptable.
public struct UnavailableCallSignalSealer: SenderRecoveringSealer {
    public init() {}
    public func seal(_ payload: CallSignalPayload, to contactID: String) async throws -> Data { throw CallSealError.undecryptable }
    public func open(_ data: Data, from contactID: String) async throws -> (payload: CallSignalPayload, senderDeviceID: String) { throw CallSealError.undecryptable }
    public func openRecoveringSender(_ data: Data) async throws -> (payload: CallSignalPayload, senderAccountID: String, senderDeviceID: String) {
        throw CallSealError.undecryptable
    }
}
