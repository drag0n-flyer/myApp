import Foundation

extension ServerClient {
    /// `wss://<host>/v1/ws` and the bearer header; nil when signed out.
    func liveEndpoint() -> (url: URL, headers: [String: String])? {
        guard let s = session.current,
              var c = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else { return nil }
        c.scheme = (c.scheme == "https") ? "wss" : "ws"
        c.path = "/v1/ws"
        guard let url = c.url else { return nil }
        return (url, ["Authorization": "Bearer \(s.token)"])
    }

    /// Close code 4003: same effect as a 401 on an authenticated call.
    func handleRevoked() async {
        await handleUnauthorized(sentAt: session.generation)
    }
}
