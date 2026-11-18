import Foundation

public enum ServerEvent: Sendable, Equatable {
    case inbox(InboxItem)
    case deviceRevoked(String)
    case callRing(callID: String, fromAccountID: String, fromDeviceID: String, video: Bool, payload: Data)
    case callAnswered(callID: String, deviceID: String, payload: Data)
    case callSignal(callID: String, fromDeviceID: String, payload: Data)
    case callEnded(callID: String, reason: String)
    /// A typing or presence signal from another member device, connected right now: an MLS application message
    /// the server cannot read. Never stored, never pushed; an offline device gets nothing later.
    case conversationEphemeral(conversationID: String, epoch: UInt64, fromAccountID: String, fromDeviceID: String, ciphertext: Data)
}
