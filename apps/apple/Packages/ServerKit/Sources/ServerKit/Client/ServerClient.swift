import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum Auth: Sendable { case none, session }

public actor ServerClient {
    let baseURL: URL
    let session: SessionStore
    private let transport: HTTPTransport
    private var onUnauthorized: (@Sendable () async -> Void)?
    /// Set once a 401 has been handled; stays set until the next `SessionStore.save`.
    private var signedOutAtGeneration: Int?

    public init(host: String, session: SessionStore, transport: HTTPTransport = URLSessionTransport()) {
        var c = URLComponents()
        c.scheme = "https"
        c.host = host
        self.baseURL = c.url ?? URL(string: "https://invalid.invalid")!
        self.session = session
        self.transport = transport
    }

    #if DEBUG
    @_spi(Testing) public init(baseURL: URL, session: SessionStore, transport: HTTPTransport) {
        self.baseURL = baseURL
        self.session = session
        self.transport = transport
    }
    #endif

    public func setOnUnauthorized(_ f: @escaping @Sendable () async -> Void) {
        onUnauthorized = f
    }

    func request<Response: Decodable>(
        _ method: String, _ path: String, body: (some Encodable)?, auth: Auth
    ) async throws(ServerError) -> Response {
        let payload = try Self.encode(body)
        let (data, _) = try await perform(method, path, body: payload, contentType: "application/json", headers: [:], auth: auth)
        do {
            return try ServerJSON.decoder().decode(Response.self, from: data)
        } catch {
            throw ServerError.decoding()
        }
    }

    func request<Response: Decodable>(
        _ method: String, _ path: String, auth: Auth
    ) async throws(ServerError) -> Response {
        try await request(method, path, body: Optional<Data>.none, auth: auth)
    }

    func requestNoContent(
        _ method: String, _ path: String, body: (some Encodable)?, auth: Auth
    ) async throws(ServerError) {
        let payload = try Self.encode(body)
        _ = try await perform(method, path, body: payload, contentType: "application/json", headers: [:], auth: auth)
    }

    func requestNoContent(_ method: String, _ path: String, auth: Auth) async throws(ServerError) {
        try await requestNoContent(method, path, body: Optional<Data>.none, auth: auth)
    }

    func requestRaw(
        _ method: String, _ path: String, body: Data, contentType: String,
        headers: [String: String], auth: Auth
    ) async throws(ServerError) -> (Data, HTTPURLResponse) {
        try await perform(method, path, body: body, contentType: contentType, headers: headers, auth: auth)
    }

    // MARK: - Pipeline

    private static func encode(_ body: (some Encodable)?) throws(ServerError) -> Data? {
        guard let body else { return nil }
        do { return try ServerJSON.encoder().encode(body) } catch { throw ServerError.decoding() }
    }

    private func perform(
        _ method: String, _ path: String, body: Data?, contentType: String,
        headers: [String: String], auth: Auth
    ) async throws(ServerError) -> (Data, HTTPURLResponse) {
        guard let url = URL(string: path, relativeTo: baseURL)?.absoluteURL else { throw ServerError.network }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            req.httpBody = body
            req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        if auth == .session {
            guard let s = session.current else { throw ServerError(status: 401, code: "unauthorized") }
            req.setValue("Bearer \(s.token)", forHTTPHeaderField: "Authorization")
        }

        let sentAt = session.generation
        let data: Data
        let response: HTTPURLResponse
        do {
            (data, response) = try await transport.send(req)
        } catch is CancellationError {
            throw ServerError.cancelled
        } catch let e as URLError where e.code == .cancelled {
            throw ServerError.cancelled
        } catch {
            throw ServerError.network
        }

        if (200..<300).contains(response.statusCode) { return (data, response) }

        let err = Self.mapError(data: data, response: response)
        if response.statusCode == 401 && auth == .session { await handleUnauthorized(sentAt: sentAt) }
        throw err
    }

    func handleUnauthorized(sentAt: Int) async {
        // A login (save) since the request was sent means the 401 is about an old token.
        guard session.generation == sentAt else { return }
        if signedOutAtGeneration == sentAt { return }
        signedOutAtGeneration = sentAt
        // If the storage delete fails the cache is kept consistent with disk; the app is still
        // told to sign out and may retry clear().
        try? session.clear()
        await onUnauthorized?()
    }

    private static func mapError(data: Data, response: HTTPURLResponse) -> ServerError {
        let retry = response.value(forHTTPHeaderField: "Retry-After").flatMap { Int($0.trimmingCharacters(in: .whitespaces)) }
        if let env = try? JSONDecoder().decode(ErrorEnvelope.self, from: data) {
            return ServerError(status: response.statusCode, code: env.error.code, retryAfter: retry, details: env.error.details)
        }
        return ServerError(status: response.statusCode, code: "unknown", retryAfter: retry, details: nil)
    }
}
