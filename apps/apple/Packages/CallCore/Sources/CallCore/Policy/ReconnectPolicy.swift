import Foundation

public enum ReconnectAction: Equatable, Sendable { case showReconnecting, restartIce, requestRestart, recovered, giveUp }

/// Decides when to restart ICE and when to give up. Time is passed in, so it is deterministic.
/// The caller restarts ICE; the callee asks for a restart.
public struct ReconnectPolicy: Sendable {
    public static let restartAfter: TimeInterval = 2.5
    public static let retryEvery: TimeInterval = 5
    public static let giveUpAfter: TimeInterval = 30   // = SimulatedCallEngine.reconnectTimeout

    private let isCaller: Bool
    private var disconnectedSince: Date?
    private var lastRestart: Date?
    private var gaveUp = false
    private var closed = false

    public init(isCaller: Bool) { self.isCaller = isCaller }

    private var restart: ReconnectAction { isCaller ? .restartIce : .requestRestart }

    public mutating func ice(_ s: IceState, at t: Date) -> [ReconnectAction] {
        guard !gaveUp, !closed else { return [] }
        switch s {
        case .disconnected, .failed:
            var out: [ReconnectAction] = []
            if disconnectedSince == nil {
                disconnectedSince = t
                lastRestart = nil
                out.append(.showReconnecting)
            }
            if s == .failed, lastRestart.map({ t.timeIntervalSince($0) >= Self.retryEvery }) ?? true {
                lastRestart = t
                out.append(restart)
            }
            return out
        case .connected, .completed:
            guard disconnectedSince != nil else { return [] }
            disconnectedSince = nil
            lastRestart = nil
            return [.recovered]
        case .closed:
            closed = true
            disconnectedSince = nil
            lastRestart = nil
            return []
        case .new, .checking:
            return []
        }
    }

    public mutating func pathChanged(at t: Date) -> [ReconnectAction] {
        guard !gaveUp, !closed else { return [] }
        if disconnectedSince != nil { lastRestart = t }
        return [restart]
    }

    public mutating func tick(at t: Date) -> [ReconnectAction] {
        guard !gaveUp, !closed, let since = disconnectedSince else { return [] }
        if t.timeIntervalSince(since) >= Self.giveUpAfter {
            gaveUp = true
            return [.giveUp]
        }
        let due: Bool
        if let last = lastRestart {
            due = t.timeIntervalSince(last) >= Self.retryEvery
        } else {
            due = t.timeIntervalSince(since) >= Self.restartAfter
        }
        guard due else { return [] }
        lastRestart = t
        return [restart]
    }
}
