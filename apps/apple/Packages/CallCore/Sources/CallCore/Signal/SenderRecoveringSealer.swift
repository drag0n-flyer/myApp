import Foundation
import AppCore

/// A sealer that can open a payload without being told who sent it. A VoIP push carries only the sealed
/// bytes (no sender, no call ID), so the receiver must recover the sender from the MLS message itself:
/// decrypting an application message yields the sender identity ("account/device"), and both parts are
/// returned here. Extends, and does not change, `CallSignalSealer`; `LiveChatStore` conforms.
public protocol SenderRecoveringSealer: CallSignalSealer {
    /// Decrypts and returns the payload with the sender's account and device IDs from the MLS credential.
    /// Throws `CallSealError.wrongSender` when the identity names no device, otherwise `.undecryptable`.
    func openRecoveringSender(_ data: Data) async throws
        -> (payload: CallSignalPayload, senderAccountID: String, senderDeviceID: String)
}

extension PlainCallSignalSealer: SenderRecoveringSealer {
    public func openRecoveringSender(_ data: Data) async throws
        -> (payload: CallSignalPayload, senderAccountID: String, senderDeviceID: String) {
        guard let identity = identity(of: data),
              let p = try? JSONDecoder().decode(CallSignalPayload.self, from: data) else {
            throw CallSealError.undecryptable
        }
        let sender = try Self.parse(identity)
        return (p, sender.account, sender.device)
    }
}
