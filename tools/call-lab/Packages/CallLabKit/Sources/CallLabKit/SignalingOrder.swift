import Foundation

/// Holds outbound items (local ICE candidates) until the frame that must precede them
/// (`call.invite` or `call.answer`) has been sent.
public struct OutboundGate<T: Sendable>: Sendable {
    private var isOpen = false
    private var queue: [T] = []
    public init() {}

    /// Items to send now: the item itself when open, nothing when it was queued.
    public mutating func submit(_ item: T) -> [T] {
        if isOpen { return [item] }
        queue.append(item)
        return []
    }

    /// Opens the gate; returns the queued items in order.
    public mutating func open() -> [T] {
        isOpen = true
        defer { queue = [] }
        return queue
    }

    public mutating func reset() { isOpen = false; queue = [] }
}

/// Routes inbound ICE candidates. Before the peer device is known (a caller before `call.answered`;
/// server design 7.3 lets several callee devices signal early) every device is accepted but queued.
/// `lock(to:)` fixes the peer and discards other devices' candidates. Candidates are applied only
/// once the remote description is set.
public struct CandidateRouter<C: Sendable>: Sendable {
    public enum Route: Equatable, Sendable { case apply, queued, dropped }

    private var peer: String?
    private var remoteReady = false
    private var queue: [(dev: String, c: C)] = []
    public init() {}

    public func accepts(from dev: String) -> Bool { peer == nil || peer == dev }

    public mutating func lock(to dev: String) {
        peer = dev
        queue.removeAll { $0.dev != dev }
    }

    public mutating func route(_ c: C, from dev: String) -> Route {
        guard accepts(from: dev) else { return .dropped }
        if peer == dev && remoteReady { return .apply }
        queue.append((dev, c))
        return .queued
    }

    /// Call after the remote description is set: returns the queued candidates of the locked peer.
    public mutating func markRemoteReady() -> [C] {
        remoteReady = true
        guard let peer else { return [] }
        defer { queue = [] }
        return queue.filter { $0.dev == peer }.map(\.c)
    }
}

/// Perfect-negotiation decisions for ICE-restart offers. The caller is impolite, the callee polite.
public struct OfferGlare: Sendable {
    public enum Decision: Equatable, Sendable { case ignore, accept(rollback: Bool) }

    public let polite: Bool
    private var makingOffer = false
    private var epoch = 0
    public init(polite: Bool) { self.polite = polite }

    public func canMakeOffer(signalingStable: Bool) -> Bool { !makingOffer && signalingStable }

    /// Returns a token; the offer is only sent while `isCurrent(token)`.
    public mutating func beginLocalOffer() -> Int { makingOffer = true; epoch += 1; return epoch }
    public mutating func endLocalOffer() { makingOffer = false }
    public func isCurrent(_ token: Int) -> Bool { token == epoch }

    public mutating func incomingOffer(signalingStable: Bool) -> Decision {
        let collision = makingOffer || !signalingStable
        if collision && !polite { return .ignore }
        if collision { epoch += 1 }   // drops any local offer still in flight
        return .accept(rollback: !signalingStable)
    }
}
