import Foundation

public enum ServerEvent: Sendable, Equatable {
    case inbox(InboxItem)
    case deviceRevoked(String)
    case callRing(callID: String, fromAccountID: String, fromDeviceID: String, video: Bool, payload: Data)
    case callAnswered(callID: String, deviceID: String, payload: Data)
    case callSignal(callID: String, fromDeviceID: String, payload: Data)
    case callEnded(callID: String, reason: String)
}
