/// What "Sign out" does once the server has answered the call that removes this device. Local data is wiped only
/// when the server confirmed the removal; with no connection nothing is wiped, so the person can retry.
public enum SignOutDecision: Equatable, Sendable {
    /// The server removed this device: wipe local data and return to the welcome screen.
    case wipe
    /// The device could not reach the server: say "No connection", keep everything, allow a retry.
    case noConnection
    /// The server answered with an error: show it, keep everything, allow a retry.
    case failed

    /// - Parameter revokeError: the error of the device-removal call; `nil` when it succeeded.
    public static func after(revokeError: AccountError?) -> SignOutDecision {
        switch revokeError {
        case nil: .wipe
        case .network?: .noConnection
        case .some: .failed
        }
    }
}
