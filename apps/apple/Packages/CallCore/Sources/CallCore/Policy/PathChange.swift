/// A reduced view of `NWPath`, so the rule is testable without Network.framework.
public struct PathSnapshot: Equatable, Sendable {
    public var interface: String   // "wifi", "cellular", "wired", "other"
    public var isSatisfied: Bool
    public init(interface: String, isSatisfied: Bool) { self.interface = interface; self.isSatisfied = isSatisfied }
}

/// True when the usable network changed: it came or went, or the primary interface type changed.
public func isMeaningfulChange(from: PathSnapshot, to: PathSnapshot) -> Bool {
    if from.isSatisfied != to.isSatisfied { return true }
    guard to.isSatisfied else { return false }
    return from.interface != to.interface
}
