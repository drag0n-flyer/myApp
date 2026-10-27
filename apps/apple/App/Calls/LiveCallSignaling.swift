import Foundation
import CallCore
import ServerKit

/// `CallSignaling` over the server's WebSocket and `GET /v1/turn`.
/// `LiveConnection.events` has one consumer (AppModel), so AppModel forwards each event here with `ingest(_:)`.
final class LiveCallSignaling: CallSignaling {
    let events: AsyncStream<CallServerEvent>
    private let continuation: AsyncStream<CallServerEvent>.Continuation
    private let connection: LiveConnection
    private let client: ServerClient
    private let turn: TurnCache

    init(connection: LiveConnection, client: ServerClient, now: @escaping @Sendable () -> Date = { Date() }) {
        (events, continuation) = AsyncStream.makeStream(of: CallServerEvent.self)
        self.connection = connection
        self.client = client
        self.turn = TurnCache(now: now)
    }

    deinit { continuation.finish() }

    /// Maps call events and yields them; other events are ignored.
    func ingest(_ event: ServerEvent) {
        if let mapped = FrameMapping.event(Self.wire(event)) { continuation.yield(mapped) }
    }

    static func wire(_ e: ServerEvent) -> CallWireEvent {
        switch e {
        case let .callRing(callID, from, device, video, payload):
            return .ring(callID: callID, fromAccountID: from, fromDeviceID: device, video: video, payload: payload)
        case let .callAnswered(callID, device, payload): return .answered(callID: callID, deviceID: device, payload: payload)
        case let .callSignal(callID, device, payload): return .signal(callID: callID, fromDeviceID: device, payload: payload)
        case let .callEnded(callID, reason): return .ended(callID: callID, reason: reason)
        case .inbox, .deviceRevoked: return .other
        }
    }

    func invite(callID: String, to accountID: String, video: Bool, payload: Data) async throws {
        let f = FrameMapping.frame(invite: callID, to: accountID, video: video, payload: payload)
        try await send(f)
    }

    func answer(callID: String, payload: Data) async throws { try await send(FrameMapping.frame(answer: callID, payload: payload)) }
    func signal(callID: String, payload: Data) async throws { try await send(FrameMapping.frame(signal: callID, payload: payload)) }
    func decline(callID: String) async throws { try await send(FrameMapping.frame("call.decline", callID: callID)) }
    func hangup(callID: String) async throws { try await send(FrameMapping.frame("call.hangup", callID: callID)) }

    func status(callID: String) async throws -> CallStatus {
        let f = FrameMapping.frame("call.status", callID: callID)
        do {
            guard let ack = try await connection.send(f.type, f.data) else { return .unknown }
            return FrameMapping.status(fromAck: ack)
        } catch let e as ServerError {
            if e.code == "network" { throw e }
            return FrameMapping.status(fromErrorCode: e.code)
        }
    }

    func iceServers() async throws -> [CallCore.IceServer] {
        if let cached = await turn.valid() { return cached }
        do {
            let creds = try await client.turnCredentials()
            let servers = creds.iceServers.map { CallCore.IceServer(urls: $0.urls, username: $0.username, credential: $0.credential) }
            await turn.store(servers, ttl: creds.ttl)
            return servers
        } catch {
            throw Self.callError(error)
        }
    }

    private func send(_ f: (type: String, data: [String: FrameValue])) async throws {
        do { _ = try await connection.send(f.type, f.data) } catch { throw Self.callError(error) }
    }

    private static func callError(_ error: Error) -> Error {
        guard let e = error as? ServerError else { return error }
        switch e.code {
        case "calls_disabled": return CallError.disabled
        case "not_found": return CallError.notFound
        default: return e
        }
    }
}

/// Holds TURN servers until 10 minutes before their `ttl` runs out.
private actor TurnCache {
    private let now: @Sendable () -> Date
    private var servers: [CallCore.IceServer]?
    private var refreshAt = Date.distantPast
    init(now: @escaping @Sendable () -> Date) { self.now = now }

    func valid() -> [CallCore.IceServer]? { now() < refreshAt ? servers : nil }

    func store(_ s: [CallCore.IceServer], ttl: Int) {
        servers = s
        refreshAt = now().addingTimeInterval(TimeInterval(ttl) - 600)
    }
}
