import AppCore

public enum RelayDecision {
    /// Each side applies its own setting: hiding protects my address.
    public static func policy(_ hiding: IPHiding, isContact: Bool) -> IceTransportPolicy {
        hiding.requiresRelay(isContact: isContact) ? .relay : .all
    }
}
