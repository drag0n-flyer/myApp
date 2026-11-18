/// The rules around one deliberate "Sign out": one request at a time, and once the person signed out on purpose the
/// server's own end-of-session signals (a 4003 close, a late 401) are ignored until the next sign-in, so they do not
/// start a second reset or show "This device was removed".
public struct SignOutGate: Equatable, Sendable {
    public private(set) var inFlight = false
    public private(set) var userSignedOut = false
    /// The server ended the session while the removal was still in flight.
    public private(set) var sessionEndedMeanwhile = false

    public init() {}

    /// Starts a sign-out. `false` when one is already running: the second request is ignored.
    public mutating func begin() -> Bool {
        guard !inFlight else { return false }
        inFlight = true
        userSignedOut = true
        sessionEndedMeanwhile = false
        return true
    }

    /// Nothing was wiped (no connection, server error): the person is still signed in and may retry.
    public mutating func failed() {
        inFlight = false
        userSignedOut = false
        sessionEndedMeanwhile = false
    }

    /// The wipe was started. Server-ended-session signals stay ignored until `didSignIn()`.
    public mutating func finished() {
        inFlight = false
    }

    public mutating func didSignIn() {
        self = SignOutGate()
    }

    /// A server-ended-session signal arrived. Returns `true` when it should be handled as usual, `false` when it is
    /// part of the user's own sign-out and must be ignored.
    public mutating func serverEndedSession() -> Bool {
        guard userSignedOut else { return true }
        if inFlight { sessionEndedMeanwhile = true }
        return false
    }
}
