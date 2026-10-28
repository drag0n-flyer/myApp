/// One step of a sign-out the server caused, in the order the app runs them.
public enum SignOutStep: Equatable, Sendable {
    /// The durable wipe marker: if the app is killed or a step fails, the next launch wipes.
    case markWipePending
    /// Stop the live connection and the messaging graph: nothing can write afterwards.
    case stop
    case wipe
}

/// What the app does when the server ends the session (ruling 2026-10-28): only the server's proof wipes.
public enum SignOutPlan {
    /// - Parameters:
    ///   - revoked: a 4003 close, or a 401 that `GET /v1/me` confirmed.
    ///   - deviceRecorded: the local data records its device, so the next sign-in (always a new device) wipes it
    ///     before opening it. Without it that sign-in could adopt the data, so it is wiped now.
    public static func steps(revoked: Bool, deviceRecorded: Bool) -> [SignOutStep] {
        revoked || !deviceRecorded ? [.markWipePending, .stop, .wipe] : [.stop]
    }
}
