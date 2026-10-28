import Foundation

public struct IceServer: Codable, Equatable, Sendable {
    public let urls: [String]
    public let username: String
    public let credential: String
}

public struct TurnCredentials: Codable, Equatable, Sendable {
    public let iceServers: [IceServer]
    public let ttl: Int
}
