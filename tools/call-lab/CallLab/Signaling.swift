import CallLabKit
import Foundation

/// WebSocket to `/v1/ws` (server design 7.3). Frames are encoded and parsed by CallLabKit.
final class Signaling: @unchecked Sendable {
    private let task: URLSessionWebSocketTask
    private let lock = NSLock()
    private var nextID = 0
    let events: AsyncStream<LabServerEvent>
    private let continuation: AsyncStream<LabServerEvent>.Continuation

    init?(host: String, token: String) {
        guard let url = LabEndpoint.wsURL(host: host) else { return nil }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        task = URLSession.shared.webSocketTask(with: req)
        (events, continuation) = AsyncStream.makeStream()
    }

    func connect() {
        task.resume()
        receive()
    }

    private func receive() {
        task.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure:
                self.continuation.yield(.error(id: nil, code: "socket_closed"))
                self.continuation.finish()
            case .success(let msg):
                let data: Data?
                switch msg {
                case .string(let s): data = Data(s.utf8)
                case .data(let d): data = d
                @unknown default: data = nil
                }
                if let data, let ev = try? LabServerEvent.parse(data) { self.continuation.yield(ev) }
                self.receive()
            }
        }
    }

    func send(_ frame: LabClientFrame) async throws {
        let id: String = lock.withLock { nextID += 1; return String(nextID) }
        let data = try frame.json(id: id)
        try await task.send(.string(String(decoding: data, as: UTF8.self)))
    }

    func close() {
        task.cancel(with: .goingAway, reason: nil)
        continuation.finish()
    }

    /// `GET /v1/turn`.
    static func fetchTurn(host: String, token: String) async throws -> TurnConfig {
        guard let url = LabEndpoint.turnURL(host: host) else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        return try JSONDecoder().decode(TurnConfig.self, from: data)
    }
}
