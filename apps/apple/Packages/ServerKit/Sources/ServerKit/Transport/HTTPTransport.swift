import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
    /// Like `send`, and tells `progress` how much of the request body has gone out (0...1). A transport that cannot
    /// tell just sends.
    func send(_ request: URLRequest, progress: (@Sendable (Double) -> Void)?) async throws -> (Data, HTTPURLResponse)
}

extension HTTPTransport {
    public func send(_ request: URLRequest, progress: (@Sendable (Double) -> Void)?) async throws -> (Data, HTTPURLResponse) {
        try await send(request)
    }
}

public struct URLSessionTransport: HTTPTransport {
    public init() {}

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        var request = request
        request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }

    public func send(_ request: URLRequest, progress: (@Sendable (Double) -> Void)?) async throws -> (Data, HTTPURLResponse) {
        #if canImport(FoundationNetworking)
        return try await send(request)   // no per-task delegate on Linux: no progress there
        #else
        guard let progress else { return try await send(request) }
        var request = request
        request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request, delegate: UploadProgressDelegate(progress))
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
        #endif
    }
}

#if !canImport(FoundationNetworking)
/// Reports `didSendBodyData` of one request as a fraction.
final class UploadProgressDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let report: @Sendable (Double) -> Void

    init(_ report: @escaping @Sendable (Double) -> Void) { self.report = report }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard totalBytesExpectedToSend > 0 else { return }
        report(min(max(Double(totalBytesSent) / Double(totalBytesExpectedToSend), 0), 1))
    }
}
#endif
