import Foundation

/// A reading of the monotonic clock (`ContinuousClock`, which keeps counting while the device sleeps). Never wall time.
public typealias LockInstant = ContinuousClock.Instant

/// When the app asks for Face ID, Touch ID or the device passcode again (design 6.3). Pure: the caller passes a
/// monotonic clock reading with every call. Same rules as `AppLockPolicy.kt` on Android.
///
/// It starts locked when enabled, so a restarted process is locked. A clock that reads earlier on return than on
/// leaving cannot say how long the app was away, so it locks.
public struct AppLockPolicy: Sendable {
    /// Immediately, 1 minute, 5 minutes, 1 hour.
    public static let delays = [0, 60, 300, 3600]
    public static let defaultSeconds = 60
    /// How long the app's own picker, camera or viewer may keep the person away before the lock applies.
    public static let expectedReturnSeconds = 300

    /// `seconds` if it is one of `delays`, else the default: stored data from another version must not disable the lock.
    public static func validDelay(_ seconds: Int) -> Int { delays.contains(seconds) ? seconds : defaultSeconds }

    /// The delay for one absence: the setting, or at least `expectedReturnSeconds` when the app itself sent the person away.
    public static func effectiveDelay(setting: Int, expectedReturn: Bool) -> Int {
        expectedReturn ? max(setting, expectedReturnSeconds) : setting
    }

    /// Whether an absence from `leftAt` to `now` locks the app. No absence (`leftAt` nil) or not enabled: no.
    public static func isLocked(enabled: Bool, leftAt: LockInstant?, now: LockInstant, delay: Int, expectedReturn: Bool) -> Bool {
        guard enabled, let leftAt else { return false }
        if now < leftAt { return true }
        return now - leftAt >= .seconds(effectiveDelay(setting: delay, expectedReturn: expectedReturn))
    }

    public var lockAfterSeconds: Int
    public private(set) var enabled: Bool
    /// True until `unlocked()`. Always false when not enabled.
    public private(set) var locked: Bool
    private var leftAt: LockInstant?
    private var expectedReturn = false

    public init(lockAfterSeconds: Int, enabled: Bool = true) {
        self.lockAfterSeconds = Self.validDelay(lockAfterSeconds)
        self.enabled = enabled
        self.locked = enabled
    }

    /// The app left the screen. With "Immediately" the lock is set now, so nothing waits behind the screen on return.
    /// `expectedReturn`: the app itself opened a picker, camera or viewer, so this one absence gets the longer allowance.
    public mutating func onBackground(at now: LockInstant, expectedReturn: Bool = false) {
        guard enabled else { return }
        if leftAt == nil {
            leftAt = now
            self.expectedReturn = expectedReturn
        }
        if Self.effectiveDelay(setting: lockAfterSeconds, expectedReturn: self.expectedReturn) <= 0 { locked = true }
    }

    /// The app came back. Returns whether it is locked. Without a matching `onBackground` nothing changes.
    @discardableResult
    public mutating func onForeground(at now: LockInstant) -> Bool {
        let left = leftAt
        leftAt = nil
        if Self.isLocked(enabled: enabled, leftAt: left, now: now, delay: lockAfterSeconds, expectedReturn: expectedReturn) {
            locked = true
        }
        expectedReturn = false
        return locked
    }

    /// How long after leaving the lock applies; nil when the app is not away or the lock is off.
    public var secondsUntilDue: Int? {
        guard enabled, leftAt != nil else { return nil }
        return Self.effectiveDelay(setting: lockAfterSeconds, expectedReturn: expectedReturn)
    }

    /// Called by a timer while the app is away (a Mac window left visible behind other apps): locks when the delay has
    /// passed, without waiting for a return. Does not end the absence.
    public mutating func lockIfDue(at now: LockInstant) {
        if Self.isLocked(enabled: enabled, leftAt: leftAt, now: now, delay: lockAfterSeconds, expectedReturn: expectedReturn) {
            locked = true
        }
    }

    /// The person passed the device authentication.
    public mutating func unlocked() {
        locked = false
        leftAt = nil
        expectedReturn = false
    }

    /// The person chose Lock Now: locked at once (only when enabled).
    public mutating func lockNow() {
        guard enabled else { return }
        locked = true
        leftAt = nil
        expectedReturn = false
    }

    /// The setting changed. Turning it on happens after a passed prompt, so the app stays unlocked; off unlocks.
    public mutating func setEnabled(_ on: Bool) {
        enabled = on
        locked = false
        leftAt = nil
        expectedReturn = false
    }
}
