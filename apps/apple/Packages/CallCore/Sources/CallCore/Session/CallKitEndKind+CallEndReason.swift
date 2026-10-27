import AppCore

extension CallKitEndKind {
    /// How the system call UI should show a call that ended for `reason`.
    public init(_ reason: CallEndReason) {
        switch reason {
        case .hungUp, .declined: self = .remoteEnded
        case .missed: self = .unanswered
        case .answeredElsewhere: self = .answeredElsewhere
        case .connectionLost, .failed: self = .failed
        }
    }
}
