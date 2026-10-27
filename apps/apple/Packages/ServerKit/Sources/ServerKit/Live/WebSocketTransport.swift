import Foundation

public protocol WebSocketChannel: Sendable {
    func send(_ text: String) async throws
    func receive() async throws -> String
    func ping() async throws
    func close(code: Int) async
    var closeCode: Int? { get async }
}

public protocol WebSocketTransport: Sendable {
    func connect(url: URL, headers: [String: String]) async throws -> any WebSocketChannel
}

public enum ConnectionState: Equatable, Sendable {
    case idle, connecting, connected
    case waiting(seconds: Double)
    case replaced, revoked
}
