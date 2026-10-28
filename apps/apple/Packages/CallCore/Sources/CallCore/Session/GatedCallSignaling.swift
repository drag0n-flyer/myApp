import Foundation

/// The server connection, as calls need it. A suspended iPhone woken by a VoIP push starts disconnected.
public protocol CallConnection: Sendable {
    var isConnected: Bool { get async }
    /// Starts the connection if it is not running. Returns at once; `isConnected` turns true later.
    func connect() async
}

/// Wraps a `CallSignaling` so every request first makes sure the connection is up, waiting up to `timeout`
/// (queued behind the connect, never sent on a dead socket). Events pass through untouched.
public struct GatedCallSignaling: CallSignaling {
    private let base: any CallSignaling
    private let connection: any CallConnection
    private let timeout: Duration

    public init(base: any CallSignaling, connection: any CallConnection, timeout: Duration = .seconds(10)) {
        self.base = base; self.connection = connection; self.timeout = timeout
    }

    public var events: AsyncStream<CallServerEvent> { base.events }

    private func ready() async throws {
        if await connection.isConnected { return }
        await connection.connect()
        let step = Duration.milliseconds(25)
        var waited = Duration.zero
        while waited < timeout {
            if await connection.isConnected { return }
            try await Task.sleep(for: step)
            waited += step
        }
        if await connection.isConnected { return }
        throw CallError.notConnected
    }

    public func invite(callID: String, to accountID: String, video: Bool, payload: Data) async throws {
        try await ready(); try await base.invite(callID: callID, to: accountID, video: video, payload: payload)
    }
    public func answer(callID: String, payload: Data) async throws { try await ready(); try await base.answer(callID: callID, payload: payload) }
    public func signal(callID: String, payload: Data) async throws { try await ready(); try await base.signal(callID: callID, payload: payload) }
    public func decline(callID: String) async throws { try await ready(); try await base.decline(callID: callID) }
    public func hangup(callID: String) async throws { try await ready(); try await base.hangup(callID: callID) }
    public func status(callID: String) async throws -> CallStatus { try await ready(); return try await base.status(callID: callID) }
    /// TURN credentials are an HTTP request, not a socket request.
    public func iceServers() async throws -> [IceServer] { try await base.iceServers() }
}

/// Remembers that the system call UI (CallKit) ended the current call, so the engine does not report the end back.
/// Per call: `begin()` clears it, and an end with no live call is ignored, so it can never hide a later call's end.
public struct SystemEndLatch: Sendable {
    private var endedBySystem = false
    public init() {}
    public mutating func begin() { endedBySystem = false }
    public mutating func systemEnded(liveCall: Bool) { if liveCall { endedBySystem = true } }
    /// True when the end must be reported to the system UI. Consumes the flag.
    public mutating func shouldReportEnd() -> Bool {
        defer { endedBySystem = false }
        return !endedBySystem
    }
}
