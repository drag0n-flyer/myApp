import Foundation

public enum PushCallEffect: Equatable, Sendable {
    /// Hand the ringing call to the engine.
    case ring
    /// The user answered while the payload was still opening: hand the call over and answer it.
    case answerNow
    /// The user declined while the payload was still opening: send the decline; never ring.
    case decline
    /// The engine already has this call (the WebSocket ring came first): end the push-created CallKit call silently.
    case endDuplicate
    /// CallKit refused the report: no system call exists, nothing goes to the engine.
    case finish
}

public enum PushUserAction: Equatable, Sendable { case answer, end }

public enum PushActionRoute: Equatable, Sendable {
    /// Still resolving: remembered, applied when the payload opens.
    case recorded
    /// Resolved: send to the engine under this server call ID.
    case forward(callID: String)
    /// Not a push-created call.
    case passthrough
}

/// Pure bookkeeping for calls created by a VoIP push: the CallKit UUID is made at push time, the server call ID
/// is known only once the payload opens. Tracks user actions taken in between, refused CallKit reports, and
/// duplicates between the push and WebSocket paths.
public struct PushCallTracker: Sendable {
    private enum Report { case pending, accepted, refused }
    private enum Phase { case ringing, answered, ended }
    private struct Entry {
        var report = Report.pending
        var callID: String?
        var engineKnows = false
        var pending: PushUserAction?
        var phase: Phase?          // set once settled and handed over
    }
    private var entries: [UUID: Entry] = [:]

    public init() {}

    public mutating func begin(kit: UUID) { entries[kit] = Entry() }

    public mutating func reportFinished(kit: UUID, refused: Bool) -> PushCallEffect? {
        guard var e = entries[kit] else { return nil }
        e.report = refused ? .refused : .accepted
        entries[kit] = e
        return settle(kit)
    }

    public mutating func resolved(kit: UUID, callID: String, engineKnows: Bool) -> PushCallEffect? {
        guard var e = entries[kit] else { return nil }
        e.callID = callID
        e.engineKnows = engineKnows || entries.contains { $0.key != kit && $0.value.callID == callID }
        entries[kit] = e
        return settle(kit)
    }

    public mutating func userAction(_ action: PushUserAction, kit: UUID) -> PushActionRoute {
        guard var e = entries[kit] else { return .passthrough }
        if e.phase == nil { e.pending = action; entries[kit] = e; return .recorded }
        if e.phase == .ringing { e.phase = action == .answer ? .answered : .ended; entries[kit] = e }
        return .forward(callID: e.callID ?? "")
    }

    /// A WebSocket ring arrived: true when the app should report a CallKit call, false when a push already did.
    public mutating func webSocketRing(callID: String) -> Bool {
        !entries.values.contains { $0.callID == callID }
    }

    public func callID(forKit kit: UUID) -> String? { entries[kit]?.callID }
    public func isRinging(kit: UUID) -> Bool { entries[kit]?.phase == .ringing }
    public mutating func finish(kit: UUID) { entries[kit] = nil }

    /// A late `call.status` result says the call is over. Acts only on a call that is still ringing for the engine,
    /// and only once; answered, declined and already-ended calls return nil.
    public mutating func endRinging(kit: UUID, kind: CallKitEndKind) -> PushLateEnd? {
        guard var e = entries[kit], e.phase == .ringing, let id = e.callID else { return nil }
        e.phase = .ended; entries[kit] = e
        return PushLateEnd(callID: id, kind: kind)
    }

    /// The overall ring cap fired. Armed at push time, so it also covers a payload that never opens.
    public mutating func capReached(kit: UUID) -> PushCapEffect {
        guard let e = entries[kit] else { return .none }
        if e.phase == nil { entries[kit] = nil; return .endUnsettled }
        if let late = endRinging(kit: kit, kind: .unanswered) { return .endRinging(late) }
        return .none
    }

    /// How a `call.status` result ends the CallKit call; nil while the call is still ringing or unknown.
    public static func lateEndKind(status: CallStatus) -> CallKitEndKind? {
        switch status {
        case .answered: .answeredElsewhere
        case .ended(let why): why == "missed" ? .unanswered : why == "answered_elsewhere" ? .answeredElsewhere : .remoteEnded
        case .ringing, .unknown: nil
        }
    }

    private mutating func settle(_ kit: UUID) -> PushCallEffect? {
        guard var e = entries[kit], e.report != .pending, e.callID != nil else { return nil }
        if e.report == .refused { entries[kit] = nil; return .finish }
        if e.engineKnows { entries[kit] = nil; return .endDuplicate }
        switch e.pending {
        case .answer?: e.phase = .answered; entries[kit] = e; return .answerNow
        case .end?: entries[kit] = nil; return .decline
        case nil: e.phase = .ringing; entries[kit] = e; return .ring
        }
    }
}

/// How a CallKit call ends. `EndReason` stays as briefed (failed, remoteEnded, unanswered); the extra cases are
/// mapped only at the CallKit boundary.
public enum CallKitEndKind: Equatable, Sendable {
    case failed, remoteEnded, unanswered, answeredElsewhere
    public init(_ r: EndReason) {
        switch r { case .failed: self = .failed; case .remoteEnded: self = .remoteEnded; case .unanswered: self = .unanswered }
    }
}

/// A ringing call the engine already has must be ended: tell the engine (once) and end the CallKit call.
public struct PushLateEnd: Equatable, Sendable {
    public var callID: String
    public var kind: CallKitEndKind
    public init(callID: String, kind: CallKitEndKind) { self.callID = callID; self.kind = kind }
}

public enum PushCapEffect: Equatable, Sendable {
    case endRinging(PushLateEnd)
    /// Never settled (payload did not open in time): end the CallKit call as unanswered; the engine knows nothing.
    case endUnsettled
    case none
}
