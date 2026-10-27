import Foundation

public enum CallServerEvent: Sendable, Equatable {
    case ring(callID: String, fromAccountID: String, fromDeviceID: String, video: Bool, payload: Data)
    case answered(callID: String, deviceID: String, payload: Data)
    case signal(callID: String, payload: Data)
    case ended(callID: String, reason: String)
}
public enum CallStatus: Sendable, Equatable { case ringing, answered, ended(reason: String), unknown }
public enum CallError: Error, Equatable { case disabled, fingerprintMismatch, notFound }

public protocol CallSignaling: Sendable {
    var events: AsyncStream<CallServerEvent> { get }
    func invite(callID: String, to accountID: String, video: Bool, payload: Data) async throws
    func answer(callID: String, payload: Data) async throws
    func signal(callID: String, payload: Data) async throws
    func decline(callID: String) async throws
    func hangup(callID: String) async throws
    func status(callID: String) async throws -> CallStatus
    func iceServers() async throws -> [IceServer]
}
