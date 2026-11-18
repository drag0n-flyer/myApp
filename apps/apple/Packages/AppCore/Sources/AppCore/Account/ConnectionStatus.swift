/// What the chat list strip and Settings > Server say about the connection to the server.
public enum ConnectionStatus: Equatable, Sendable {
    case connected, connecting, offline

    /// No network is offline whatever the socket says; a network without a socket is still connecting (or waiting to
    /// retry); both is connected.
    public static func from(socketConnected: Bool, hasNetwork: Bool) -> ConnectionStatus {
        guard hasNetwork else { return .offline }
        return socketConnected ? .connected : .connecting
    }
}
