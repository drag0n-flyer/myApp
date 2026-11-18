import Foundation

extension GroupMyState {
    /// "You're no longer in this group": the composer is replaced by that line.
    public var isReadOnly: Bool { self != .member }
}
