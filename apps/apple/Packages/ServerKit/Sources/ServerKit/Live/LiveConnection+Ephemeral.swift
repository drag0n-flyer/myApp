import Foundation

extension LiveConnection {
    /// Sends a typing or presence signal (an MLS application message, at most 4 KiB) to the conversation's other
    /// connected devices. Returns when the server acknowledged it. The server drops the excess of its rate limits
    /// silently with the normal acknowledgement; `not_found` means this device is not a member; `bad_request` a
    /// missing field or a ciphertext that is empty or over 4 KiB.
    public func sendEphemeral(conversationID: String, epoch: UInt64, ciphertext: Data) async throws {
        struct Frame: Encodable, Sendable {
            let conversationID: String
            let epoch: UInt64
            let ciphertext: Base64URLData
        }
        _ = try await send("conv.ephemeral", Frame(conversationID: conversationID, epoch: epoch, ciphertext: Base64URLData(ciphertext)))
    }
}
