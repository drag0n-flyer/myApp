import Foundation

/// Decides when the lab sends an ICE-restart offer: after N seconds in `.disconnected`
/// (once per disconnection), or at once on a network interface change.
public struct IceRestartPolicy: Sendable {
    public enum State: Sendable { case connected, disconnected }

    private let afterMs: Int?
    private var disconnectedAt: Int?
    private var restarted = false

    public init(afterSeconds: Int?) { afterMs = afterSeconds.map { $0 * 1000 } }

    public mutating func stateChanged(_ s: State, atMs: Int) {
        switch s {
        case .disconnected:
            if disconnectedAt == nil { disconnectedAt = atMs; restarted = false }
        case .connected:
            disconnectedAt = nil; restarted = false
        }
    }

    /// Call about once a second. True means: send a restart offer now.
    public mutating func tick(nowMs: Int) -> Bool {
        guard let afterMs, let t0 = disconnectedAt, !restarted, nowMs - t0 >= afterMs else { return false }
        restarted = true
        return true
    }

    /// True when a path change should restart ICE right away.
    public mutating func interfaceChanged() -> Bool { afterMs != nil }
}
