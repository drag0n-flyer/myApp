import Foundation

extension ServerClient {
    /// `wss://<host>/v1/ws` and the bearer header; nil when signed out.
    func liveEndpoint() -> (url: URL, headers: [String: String], generation: Int)? {
        guard let s = session.current,
              var c = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else { return nil }
        c.scheme = (c.scheme == "https") ? "wss" : "ws"
        c.path = "/v1/ws"
        guard let url = c.url else { return nil }
        return (url, ["Authorization": "Bearer \(s.token)"], session.generation)
    }

    /// Close code 4003: the server revoked this device.
    func handleRevoked(generation: Int) async {
        await handleUnauthorized(sentAt: generation, serverError: true, revoked: true)
    }

    /// A 401 on the WebSocket upgrade (its body is not read): revoked only if a second call confirms it.
    func handleUpgradeUnauthorized(generation: Int) async -> SignOutReason {
        await handleUnauthorized(sentAt: generation, serverError: true)
    }
}
