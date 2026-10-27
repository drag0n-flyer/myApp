import Foundation

extension ServerClient {
    public func serverInfo() async throws(ServerError) -> ServerInfo {
        try await request("GET", "/v1/server", auth: .none)
    }
}
