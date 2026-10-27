import Foundation
import ServerKit

public enum EscrowKeyError: Error, Equatable, Sendable {
    /// The server publishes no escrow key (or an undecodable one).
    case unavailable
}

/// The server's escrow public key: fetched once from `GET /v1/server`, cached, base64url decoded.
/// One provider is shared by everything that seals escrow copies (the outbox and lane A's
/// conversation manager). `invalidate()` drops the cache, for example after the server refuses a copy.
public actor EscrowKeyProvider {
    private let server: ServerClient
    private var cached: Data?

    public init(server: ServerClient) { self.server = server }

    /// Throws `ServerError` on a failed request and `EscrowKeyError.unavailable` when there is no key.
    public func key() async throws -> Data {
        if let cached { return cached }
        let info = try await server.serverInfo()
        guard let encoded = info.encryption.escrowKey, let data = Base64URLData(encoded: encoded)?.data,
              !data.isEmpty else { throw EscrowKeyError.unavailable }
        cached = data
        return data
    }

    public func invalidate() { cached = nil }

    /// The seam lane A's `ConversationManager` injects as `escrowKey`.
    public nonisolated var closure: @Sendable () async throws -> Data { { try await self.key() } }
}
