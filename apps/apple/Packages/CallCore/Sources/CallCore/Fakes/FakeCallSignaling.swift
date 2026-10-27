import Foundation

public final class FakeCallSignaling: CallSignaling, @unchecked Sendable {
    public let events: AsyncStream<CallServerEvent>
    private let continuation: AsyncStream<CallServerEvent>.Continuation
    private let lock = NSLock()
    private var _sent: [(frame: String, callID: String, payload: Data?)] = []
    private var _ice: [IceServer] = []
    private var _statusResult: CallStatus = .ringing

    public init() {
        (events, continuation) = AsyncStream.makeStream(of: CallServerEvent.self)
    }
    public var sent: [(frame: String, callID: String, payload: Data?)] { lock.lock(); defer { lock.unlock() }; return _sent }
    public var ice: [IceServer] {
        get { lock.lock(); defer { lock.unlock() }; return _ice }
        set { lock.lock(); _ice = newValue; lock.unlock() }
    }
    public var statusResult: CallStatus {
        get { lock.lock(); defer { lock.unlock() }; return _statusResult }
        set { lock.lock(); _statusResult = newValue; lock.unlock() }
    }
    public func emit(_ event: CallServerEvent) { continuation.yield(event) }
    private func record(_ frame: String, _ callID: String, _ payload: Data?) {
        lock.lock(); _sent.append((frame, callID, payload)); lock.unlock()
    }

    public func invite(callID: String, to accountID: String, video: Bool, payload: Data) async throws { record("call.invite", callID, payload) }
    public func answer(callID: String, payload: Data) async throws { record("call.answer", callID, payload) }
    public func signal(callID: String, payload: Data) async throws { record("call.signal", callID, payload) }
    public func decline(callID: String) async throws { record("call.decline", callID, nil) }
    public func hangup(callID: String) async throws { record("call.hangup", callID, nil) }
    public func status(callID: String) async throws -> CallStatus { statusResult }
    public func iceServers() async throws -> [IceServer] { ice }
}
