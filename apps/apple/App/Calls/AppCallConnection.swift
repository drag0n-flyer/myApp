import CallCore

/// `CallConnection` over AppModel's live connection (a closure pair keeps it free of AppModel's isolation).
struct AppCallConnection: CallConnection {
    let isConnectedNow: @Sendable () async -> Bool
    let connectNow: @Sendable () async -> Void

    init(isConnected: @escaping @Sendable () async -> Bool, connect: @escaping @Sendable () async -> Void) {
        isConnectedNow = isConnected; connectNow = connect
    }

    var isConnected: Bool { get async { await isConnectedNow() } }
    func connect() async { await connectNow() }
}
