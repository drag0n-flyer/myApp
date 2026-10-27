#if canImport(Darwin)
import Foundation

/// `URLSessionWebSocketTask` behind `WebSocketTransport`. Not compiled on Linux; verified in CI.
public struct URLSessionWebSocketTransport: WebSocketTransport {
    public init() {}

    public func connect(url: URL, headers: [String: String]) async throws -> any WebSocketChannel {
        var request = URLRequest(url: url)
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        let task = URLSession.shared.webSocketTask(with: request)
        let channel = URLSessionWebSocketChannel(task: task)
        task.resume()
        // A pong only comes back once the handshake succeeded; a failed handshake throws here.
        try await channel.ping()
        return channel
    }
}

final class URLSessionWebSocketChannel: WebSocketChannel, @unchecked Sendable {
    private let task: URLSessionWebSocketTask

    init(task: URLSessionWebSocketTask) { self.task = task }

    func send(_ text: String) async throws { try await task.send(.string(text)) }

    func receive() async throws -> String {
        switch try await task.receive() {
        case .string(let s): return s
        case .data(let d): return String(decoding: d, as: UTF8.self)
        @unknown default: throw URLError(.cannotParseResponse)
        }
    }

    func ping() async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            task.sendPing { error in
                if let error { c.resume(throwing: error) } else { c.resume() }
            }
        }
    }

    func close(code: Int) async {
        task.cancel(with: URLSessionWebSocketTask.CloseCode(rawValue: code) ?? .goingAway, reason: nil)
    }

    var closeCode: Int? {
        let c = task.closeCode.rawValue
        return c == 0 ? nil : c
    }
}
#endif
