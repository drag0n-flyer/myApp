import Foundation

extension ServerClient {
    /// One `iceServers` entry per relay. Throws `calls_disabled` when the server has calls off.
    public func turnCredentials() async throws(ServerError) -> TurnCredentials {
        try await request("GET", "/v1/turn", auth: .session)
    }
}
