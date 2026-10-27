import Foundation
import ServerKit

struct FakeClosed: Error {}

/// A scripted socket: the test feeds server frames and close codes, and reads `sent`.
public actor FakeWebSocket: WebSocketChannel {
    private enum Item { case text(String), close(Int) }

    public private(set) var sent: [String] = []
    public private(set) var clientCloseCode: Int?
    public var hangPings = false
    public private(set) var pingCount = 0

    private var incoming: [Item] = []
    private var serverCode: Int?
    private var closed = false
    private var receiver: CheckedContinuation<String, Error>?
    private var pinger: CheckedContinuation<Void, Error>?

    public init() {}

    public func setHangPings(_ v: Bool) { hangPings = v }

    public func serverSends(_ text: String) { push(.text(text)) }
    public func serverCloses(code: Int) { push(.close(code)) }

    private func push(_ item: Item) {
        incoming.append(item)
        if let r = receiver { receiver = nil; deliver(to: r) }
    }

    private func deliver(to r: CheckedContinuation<String, Error>) {
        switch incoming.removeFirst() {
        case .text(let t): r.resume(returning: t)
        case .close(let c): serverCode = c; closed = true; r.resume(throwing: FakeClosed())
        }
    }

    public func send(_ text: String) async throws {
        if closed { throw FakeClosed() }
        sent.append(text)
    }

    public func receive() async throws -> String {
        if !incoming.isEmpty {
            switch incoming.removeFirst() {
            case .text(let t): return t
            case .close(let c): serverCode = c; closed = true; throw FakeClosed()
            }
        }
        if closed { throw FakeClosed() }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<String, Error>) in
                if Task.isCancelled { c.resume(throwing: CancellationError()); return }
                receiver = c
            }
        } onCancel: {
            Task { await self.cancelReceiver() }
        }
    }

    private func cancelReceiver() {
        if let r = receiver { receiver = nil; r.resume(throwing: CancellationError()) }
    }

    public func ping() async throws {
        pingCount += 1
        if closed { throw FakeClosed() }
        guard hangPings else { return }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                if Task.isCancelled { c.resume(throwing: CancellationError()); return }
                pinger = c
            }
        } onCancel: {
            Task { await self.cancelPinger() }
        }
    }

    private func cancelPinger() {
        if let p = pinger { pinger = nil; p.resume(throwing: CancellationError()) }
    }

    public func close(code: Int) async {
        guard !closed else { return }
        clientCloseCode = code
        closed = true
        if let r = receiver { receiver = nil; r.resume(throwing: FakeClosed()) }
        if let p = pinger { pinger = nil; p.resume(throwing: FakeClosed()) }
    }

    public var closeCode: Int? { serverCode ?? clientCloseCode }
}

/// Hands out queued sockets in order; when the queue is empty it creates a fresh one.
public actor FakeWebSocketTransport: WebSocketTransport {
    public private(set) var sockets: [FakeWebSocket] = []
    public private(set) var requests: [(url: URL, headers: [String: String])] = []
    private var queue: [FakeWebSocket] = []
    private var connectErrors: [any Error] = []

    public init() {}

    /// The next `connect` calls throw these errors, in order, before any socket is handed out.
    public func failConnects(with errors: [any Error]) { connectErrors += errors }

    public func enqueue(_ s: FakeWebSocket) { queue.append(s) }

    public func connect(url: URL, headers: [String: String]) async throws -> any WebSocketChannel {
        requests.append((url, headers))
        if !connectErrors.isEmpty { throw connectErrors.removeFirst() }
        let s = queue.isEmpty ? FakeWebSocket() : queue.removeFirst()
        sockets.append(s)
        return s
    }
}
