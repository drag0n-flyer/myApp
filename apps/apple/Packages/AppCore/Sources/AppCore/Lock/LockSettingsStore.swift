import Foundation

/// The two stored lock settings. Nothing secret.
public struct LockSettings: Equatable, Sendable {
    public var enabled: Bool
    public var lockAfterSeconds: Int

    public init(enabled: Bool, lockAfterSeconds: Int = AppLockPolicy.defaultSeconds) {
        self.enabled = enabled
        self.lockAfterSeconds = AppLockPolicy.validDelay(lockAfterSeconds)
    }
}

@MainActor
public protocol LockSettingsStore: AnyObject {
    /// The stored settings; nil when they cannot be read (the app then counts as locked).
    func load() -> LockSettings?
    func save(_ settings: LockSettings)
    /// Back to off and the default delay (sign-out).
    func clear()
}

/// Keeps the settings in `UserDefaults`. The live app passes the app group's defaults so the notification extension
/// can read the switch too.
@MainActor
public final class DefaultsLockSettingsStore: LockSettingsStore {
    public static let enabledKey = "appLock.enabled"
    public static let delayKey = "appLock.lockAfterSeconds"
    private let defaults: UserDefaults

    public init(defaults: UserDefaults) { self.defaults = defaults }

    public func load() -> LockSettings? {
        let enabled: Bool
        switch defaults.object(forKey: Self.enabledKey) {
        case nil: enabled = false                     // never set: off
        case let value as Bool: enabled = value
        default: return nil                           // something else is stored there: unknown, so locked
        }
        let delay = (defaults.object(forKey: Self.delayKey) as? Int) ?? AppLockPolicy.defaultSeconds
        return LockSettings(enabled: enabled, lockAfterSeconds: delay)
    }

    public func save(_ settings: LockSettings) {
        defaults.set(settings.enabled, forKey: Self.enabledKey)
        defaults.set(settings.lockAfterSeconds, forKey: Self.delayKey)
    }

    public func clear() {
        defaults.removeObject(forKey: Self.enabledKey)
        defaults.removeObject(forKey: Self.delayKey)
    }
}

/// Sample data and tests: nothing is written to disk.
@MainActor
public final class MemoryLockSettingsStore: LockSettingsStore {
    public var stored: LockSettings?
    public init(_ stored: LockSettings? = LockSettings(enabled: false)) { self.stored = stored }
    public func load() -> LockSettings? { stored }
    public func save(_ settings: LockSettings) { stored = settings }
    public func clear() { stored = LockSettings(enabled: false) }
}
