import AppCore

/// What a running call does when the device's network changes.
public enum NetworkAction: Equatable, Sendable {
    /// Same network, nothing that matters: do nothing.
    case nothing
    /// No connection: remember it only. Nothing can be offered over it, and an offer made now would block the restart
    /// that the network's return needs.
    case record
    /// The network type changed but the path did not matter: tell the call its context moved, no restart.
    case contextOnly
    /// The path changed under the same network type: restart ICE.
    case pathOnly
    /// The network type and the path changed: the new context and the ICE restart go out together.
    case restart
}

public enum NetworkChange {
    /// `meaningful` is the path monitor's verdict that a running call should restart ICE. Coming back from no connection
    /// always counts as meaningful.
    public static func action(from old: NetworkType, to new: NetworkType, meaningful: Bool) -> NetworkAction {
        if new == .offline { return old == .offline ? .nothing : .record }
        let restarts = meaningful || old == .offline
        if old == new { return restarts ? .pathOnly : .nothing }
        return restarts ? .restart : .contextOnly
    }
}
