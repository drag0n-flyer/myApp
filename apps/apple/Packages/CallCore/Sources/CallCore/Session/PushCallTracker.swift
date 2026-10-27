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
    private enum Phase { case ringing, answered }
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
        e.callID = callID; e.engineKnows = engineKnows
        entries[kit] = e
        return settle(kit)
    }

    public mutating func userAction(_ action: PushUserAction, kit: UUID) -> PushActionRoute {
        guard var e = entries[kit] else { return .passthrough }
        if e.phase == nil { e.pending = action; entries[kit] = e; return .recorded }
        if action == .answer { e.phase = .answered; entries[kit] = e }
        return .forward(callID: e.callID ?? "")
    }

    /// A WebSocket ring arrived: true when the app should report a CallKit call, false when a push already did.
    public mutating func webSocketRing(callID: String) -> Bool {
        !entries.values.contains { $0.callID == callID }
    }

    public func callID(forKit kit: UUID) -> String? { entries[kit]?.callID }
    public func isRinging(kit: UUID) -> Bool { entries[kit]?.phase == .ringing }
    public mutating func finish(kit: UUID) { entries[kit] = nil }

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
