/// The profile actions that change the account. Only one runs at a time.
public enum AccountAction: Equatable, Sendable {
    case signingOut, deleting, savingName
}

/// Save name, sign out and delete account run one at a time; a request made while another runs is ignored.
public struct AccountActionGate: Equatable, Sendable {
    public private(set) var running: AccountAction?

    public init() {}

    /// `false` when an action is already running.
    public mutating func begin(_ action: AccountAction) -> Bool {
        guard running == nil else { return false }
        running = action
        return true
    }

    public mutating func finish() { running = nil }

    /// The "Edit name" editor stays open only while no action runs: it closes when a save, a sign-out or a delete starts.
    public static func keepsEditorOpen(running: AccountAction?) -> Bool { running == nil }
}

/// Which name, if any, to store as the person's own after reading it from the server when a session opens.
public enum OwnNameSeed {
    /// The cleaned server name, or `nil` when nothing may be written: a name is already stored (it is never
    /// overwritten), the session is not the one the read began in (signed out, or another account), or nothing
    /// visible is left after cleaning.
    public static func nameToStore(stored: String, fetched: String, sameSession: Bool) -> String? {
        guard stored.isEmpty, sameSession else { return nil }
        let clean = DisplayNameRule.forDisplay(fetched, fallback: "")
        return clean.isEmpty ? nil : clean
    }
}
