/// What "Sign out" does once the server has answered the call that removes this device. Local data is wiped only
/// when the server confirmed the removal (or ended the session itself); with no connection nothing is wiped, so the
/// person can retry. A confirmed sign-out always wipes: the person was told their messages would be removed.
public enum SignOutDecision: Equatable, Sendable {
    /// Wipe local data and return to the welcome screen.
    case wipe
    /// The device could not reach the server: say "No connection", keep everything, allow a retry.
    case noConnection
    /// The server answered with an error: show it, keep everything, allow a retry.
    case failed

    /// - Parameters:
    ///   - revokeError: the error of the device-removal call; `nil` when it succeeded.
    ///   - sessionEndedMeanwhile: the server ended the session while the call ran (it is gone either way).
    public static func after(revokeError: AccountError?, sessionEndedMeanwhile: Bool = false) -> SignOutDecision {
        if sessionEndedMeanwhile { return .wipe }
        switch revokeError {
        case nil: return .wipe
        case .network?: return .noConnection
        case .some: return .failed
        }
    }
}
