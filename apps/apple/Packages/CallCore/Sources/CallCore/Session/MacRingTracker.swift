import Foundation

public enum MacNotificationAction: Equatable, Sendable { case answer, decline }

public enum MacActionRoute: Equatable, Sendable {
    /// Hand the call to the engine and answer it.
    case answer(callID: String)
    case decline(callID: String)
    /// The call is already over (stale, ended, answered elsewhere): do not answer; show it as missed or ended.
    case expired(callID: String, kind: CallKitEndKind)
    /// A repeat or an action for a call already handled.
    case ignore
}

/// Pure bookkeeping for the Mac ring (no CallKit): one ring at a time, a 45 s cap, and notification actions
/// applied at most once per call. Reuses `CallKitEndKind` and `PushCallTracker.lateEndKind` for how a finished call is described.
public struct MacRingTracker: Sendable {
    private struct Ring { var callID: String; var at: Date }
    private var current: Ring?
    private var done: Set<String> = []

    public init() {}

    public var ringingCallID: String? { current?.callID }

    /// A `call.ring` arrived. True when the app should start ringing; false for a duplicate, a call that already
    /// finished, or a second call while one rings (the server tells the caller the callee is busy).
    public mutating func ring(callID: String, at: Date) -> Bool {
        guard current == nil, !done.contains(callID) else { return false }
        current = Ring(callID: callID, at: at)
        return true
    }

    /// The call ended (hangup, answered elsewhere, declined here). True when this was the ringing call, so the sound should stop.
    public mutating func ended(callID: String) -> Bool {
        defer { done.insert(callID) }
        guard current?.callID == callID else { return false }
        current = nil
        return true
    }

    /// How long the ring timer should sleep for the ringing call: the cap minus its age, never negative.
    /// nil when `callID` is not the ringing call. The caller sleeps on a monotonic clock for this long.
    public func capDelay(callID: String, now: Date) -> TimeInterval? {
        guard let c = current, c.callID == callID else { return nil }
        return max(0, CallNotificationText.staleAfter - now.timeIntervalSince(c.at))
    }

    /// The cap timer fired. The timer is authoritative (no wall-clock check, so clock drift cannot leave a ring
    /// without a cap). True once, only while `callID` is the ringing call.
    public mutating func capFired(callID: String) -> Bool {
        guard current?.callID == callID else { return false }
        current = nil
        done.insert(callID)
        return true
    }

    /// Maps a tap on the notification's Answer or Decline. The app may have been launched by the tap, so the
    /// ring time comes from the notification and `status` from `call.status` (nil when it could not be fetched).
    /// `stopsRinging` is true only when the action settled the call that is ringing right now.
    public mutating func route(_ action: MacNotificationAction, callID: String, ringedAt: Date,
                               status: CallStatus?, now: Date) -> MacRouteOutcome {
        guard !done.contains(callID) else { return MacRouteOutcome(route: .ignore, stopsRinging: false) }
        done.insert(callID)
        let stops = current?.callID == callID
        if stops { current = nil }
        let route: MacActionRoute
        switch action {
        case .decline:
            route = .decline(callID: callID)
        case .answer:
            if let status, let kind = PushCallTracker.lateEndKind(status: status) {
                route = .expired(callID: callID, kind: kind)
            } else if now.timeIntervalSince(ringedAt) >= CallNotificationText.staleAfter {
                route = .expired(callID: callID, kind: .unanswered)
            } else {
                route = .answer(callID: callID)
            }
        }
        return MacRouteOutcome(route: route, stopsRinging: stops)
    }
}

public struct MacRouteOutcome: Equatable, Sendable {
    public var route: MacActionRoute
    public var stopsRinging: Bool
    public init(route: MacActionRoute, stopsRinging: Bool) { self.route = route; self.stopsRinging = stopsRinging }
}
