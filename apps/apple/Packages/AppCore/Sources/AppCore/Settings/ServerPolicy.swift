/// Settings the server administrator controls.
public struct ServerPolicy: Equatable, Sendable {
    public var defaultPreference: ModePreference
    public var maximum: DataMode
    public var defaultIPHiding: IPHiding
    public var requiresInvite: Bool
    public var host: String

    public init(defaultPreference: ModePreference, maximum: DataMode, defaultIPHiding: IPHiding, requiresInvite: Bool, host: String) {
        self.defaultPreference = defaultPreference
        self.maximum = maximum
        self.defaultIPHiding = defaultIPHiding
        self.requiresInvite = requiresInvite
        self.host = host
    }
}

/// "Hide my IP address in calls".
public enum IPHiding: CaseIterable, Codable, Sendable {
    case never, nonContacts, always
}
