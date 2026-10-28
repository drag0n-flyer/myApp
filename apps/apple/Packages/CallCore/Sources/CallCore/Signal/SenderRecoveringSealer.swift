import Foundation
import AppCore

/// A sealer that can open a payload without being told who sent it. A VoIP push carries only the sealed
/// bytes (no sender, no call ID), so the receiver must recover the sender from the MLS message itself:
/// decrypting an application message yields the sender identity ("account/device"), and the account part
/// is returned here. Extends, and does not change, `CallSignalSealer`; `LiveChatStore` conforms.
public protocol SenderRecoveringSealer: CallSignalSealer {
    /// Decrypts and returns the payload with the sender's account ID. Throws `CallSealError.undecryptable`.
    func openRecoveringSender(_ data: Data) async throws -> (payload: CallSignalPayload, senderAccountID: String)
}

extension PlainCallSignalSealer: SenderRecoveringSealer {
    public func openRecoveringSender(_ data: Data) async throws -> (payload: CallSignalPayload, senderAccountID: String) {
        guard let sender = senderOf(data),
              let p = try? JSONDecoder().decode(CallSignalPayload.self, from: data) else {
            throw CallSealError.undecryptable
        }
        return (p, sender)
    }
}
