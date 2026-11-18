import Foundation
import Observation

/// The scene's phase, as the app's root reports it (mapped from SwiftUI's `ScenePhase`).
public enum LockPhase: Equatable, Sendable {
    case active, inactive, background
}

/// How a request to switch the lock on or off ended.
public enum LockSwitchOutcome: Equatable, Sendable {
    case changed
    /// Nothing to do: already in that state.
    case unchanged
    /// The prompt was not passed (cancelled, failed).
    case notPassed
    /// Turning on, and the device has no screen lock to ask with.
    case unavailable
    /// Another prompt is open.
    case busy
    /// The account was signed out while the prompt was open: nothing changed.
    case stale
}

/// The app lock's state for the whole process: whether the root may show anything but the cover.
///
/// Decisions: `AppLockPolicy` (the delay, on a monotonic clock) and this type (what is covered, which prompt results
/// count). It starts locked when the setting is on, and also when the setting could not be read (unknown = locked).
/// Only a success from `DeviceAuth` unlocks; every other result, error or cancel leaves the cover. The one exception
/// is a device with no passcode any more (`DeviceAuthAvailability.passcodeNotSet`, and nothing else). Holds no secret.
@MainActor
@Observable
public final class AppLock {
    private let auth: any DeviceAuth
    @ObservationIgnored private let store: any LockSettingsStore
    @ObservationIgnored private let now: @MainActor () -> LockInstant
    private let coversWhenInactive: Bool
    private let inactiveCountsAsLeaving: Bool
    @ObservationIgnored private var policy: AppLockPolicy

    /// The stored settings; nil while unknown (counts as locked).
    public private(set) var settings: LockSettings?
    public private(set) var isLocked: Bool
    public private(set) var phase: LockPhase = .active
    /// Counts the times the cover should open the prompt by itself: at start, and on each return while locked.
    public private(set) var promptRequests: Int
    public private(set) var promptOpen = false
    /// The last Unlock found the system unable to check (not "no passcode"): the cover says so.
    public private(set) var cannotCheck = false
    /// A chat from a notification tap, kept until unlock.
    @ObservationIgnored private var heldChatID: String?
    @ObservationIgnored private var promptsAnswered = 0
    @ObservationIgnored private var left = false
    @ObservationIgnored private var expectedAt: LockInstant?
    /// Which prompt is open; sign-out clears it so a late answer changes nothing.
    @ObservationIgnored private var activePrompt: Int?
    @ObservationIgnored private var promptCounter = 0
    @ObservationIgnored private var epoch = 0

    /// Runs once, right after the switch was turned on and stored (A5: replace delivered notifications by locked ones).
    @ObservationIgnored public var onLockSwitchedOn: @MainActor () -> Void = {}
    /// Gets the chat of a held notification tap once, when the app unlocks.
    @ObservationIgnored public var onHeldChatReleased: @MainActor (String) -> Void = { _ in }

    /// - Parameters:
    ///   - coversWhenInactive: iPhone: a cover is shown at once when the scene goes inactive, so the app switcher
    ///     snapshot has no content. Mac: only while locked.
    ///   - inactiveCountsAsLeaving: Mac has no background phase for a hidden app: losing focus is leaving.
    public init(store: any LockSettingsStore, auth: any DeviceAuth, coversWhenInactive: Bool, inactiveCountsAsLeaving: Bool,
                now: @escaping @MainActor () -> LockInstant = { ContinuousClock.now }) {
        self.store = store
        self.auth = auth
        self.now = now
        self.coversWhenInactive = coversWhenInactive
        self.inactiveCountsAsLeaving = inactiveCountsAsLeaving
        let loaded = store.load()
        self.settings = loaded
        // Unknown settings: locked, with the default delay.
        let start = AppLockPolicy(lockAfterSeconds: loaded?.lockAfterSeconds ?? AppLockPolicy.defaultSeconds,
                                  enabled: loaded?.enabled ?? true)
        self.policy = start
        self.isLocked = start.locked
        self.promptRequests = start.locked ? 1 : 0
    }

    // MARK: - What the views read

    /// App lock is on, or not known yet. A plain read for code that must not block (notifications).
    public var appLockOn: Bool { settings?.enabled ?? true }

    public var lockAfterSeconds: Int { settings?.lockAfterSeconds ?? AppLockPolicy.defaultSeconds }

    /// The root shows only the cover (and the call screen) while this is true. No chat view exists then.
    public var coversContent: Bool { appLockOn && isLocked }

    /// iPhone: the scene is not active (app switcher, Notification Center, an alert, the whole time away): a separate
    /// privacy window covers everything and the screens keep their state. Up while locked too, so the root's own cover
    /// is rendered underneath before the window hides.
    public var showsPrivacyCover: Bool { appLockOn && coversWhenInactive && phase != .active }

    /// Changes whenever the cover should try the automatic prompt: a new request, or the scene becoming active.
    public var promptKey: Int { promptRequests * 2 + (phase == .active ? 1 : 0) }

    public var availability: DeviceAuthAvailability { auth.availability() }

    // MARK: - Scene

    public func phaseChanged(_ new: LockPhase) {
        guard new != phase else { return }
        let old = phase
        phase = new
        switch new {
        case .active: didBecomeActive()
        case .inactive:
            let returning = old == .background
            willResignActive()
            // Back from the background: decide now, while the privacy window still covers, so the root has rendered
            // its cover before the window hides on `.active`.
            if returning { lockIfDue() }
        case .background: didEnterBackground()
        }
    }

    public func willResignActive() {
        phase = .inactive
        // The system prompt itself makes a Mac app inactive: that is not leaving.
        if inactiveCountsAsLeaving && activePrompt == nil { leave() }
    }

    public func didEnterBackground() {
        phase = .background
        leave()
    }

    public func didBecomeActive() {
        phase = .active
        guard left else { return }
        left = false
        expectedAt = nil
        if policy.onForeground(at: now()), appLockOn {
            isLocked = true
            promptRequests += 1
        }
    }

    /// The app is about to open a picker, the camera or a viewer itself and the person will come back from it. Call
    /// right before the launch. The next absence, if it starts within `AppLockPolicy.expectedReturnSeconds`, is
    /// measured with the longer allowance; a launch that never takes the app off the screen expires.
    public func expectReturn() { expectedAt = now() }

    /// The picker, camera or viewer closed without the app leaving the screen: no allowance for the next absence.
    public func cancelExpectedReturn() { expectedAt = nil }

    /// While the app is away: how long until the lock applies (the Mac timer's delay); nil when not away or off.
    public var secondsUntilDue: Int? { left && appLockOn ? policy.secondsUntilDue : nil }

    /// Called by the Mac timer: locks a window left visible behind other apps once the delay has passed.
    public func lockIfDue() {
        guard left else { return }
        policy.lockIfDue(at: now())
        if policy.locked && appLockOn { isLocked = true }
    }

    private func leave() {
        guard !left else { return }
        left = true
        let at = now()
        let asked = expectedAt
        expectedAt = nil
        let expected = asked.map { at >= $0 && at - $0 <= .seconds(AppLockPolicy.expectedReturnSeconds) } ?? false
        policy.onBackground(at: at, expectedReturn: expected)
        if policy.locked { isLocked = true }
    }

    // MARK: - Unlocking

    /// The cover's automatic prompt: once per request, only while the scene is active. A cancel leaves the cover.
    public func promptAutomatically(reason: String) async {
        guard isLocked, phase == .active, promptRequests != promptsAnswered, activePrompt == nil else { return }
        promptsAnswered = promptRequests
        await unlockWithPrompt(reason: reason)
    }

    /// The cover's button.
    public func unlockWithPrompt(reason: String) async {
        guard isLocked, activePrompt == nil, phase == .active else { return }
        switch auth.availability() {
        case .passcodeNotSet:
            cannotCheck = false
            unlock()   // nothing protects the device any more: let the person in
        case .unavailable:
            cannotCheck = true   // fails closed: the cover stays, and Unlock can be tried again
        case .ready:
            cannotCheck = false
            let id = beginPrompt()
            let result = await auth.authenticate(reason: reason)
            guard activePrompt == id else { return }   // signed out meanwhile
            activePrompt = nil
            promptOpen = false
            if result == .success { unlock() }
        }
    }

    private func beginPrompt() -> Int {
        promptCounter += 1
        activePrompt = promptCounter
        promptOpen = true
        return promptCounter
    }

    /// Only called after a success from the system prompt, or with no passcode on the device.
    private func unlock() {
        policy.unlocked()
        isLocked = false
        left = false
        expectedAt = nil
        releaseHeldChat()
    }

    // MARK: - Settings

    /// Switches the lock on or off. Needs one passed prompt either way; with no passcode on the device the switch may
    /// be turned off without one. A prompt answered after `reset()` changes nothing.
    public func requestSetEnabled(_ on: Bool, reason: String) async -> LockSwitchOutcome {
        guard on != appLockOn || settings == nil else { return .unchanged }
        guard activePrompt == nil else { return .busy }
        let startEpoch = epoch
        switch auth.availability() {
        case .unavailable:
            return .unavailable   // fails closed, on or off
        case .passcodeNotSet:
            // Only this exact case: nothing to protect; it cannot be turned on, and turning it off needs no prompt.
            if on { return .unavailable }
        case .ready:
            let id = beginPrompt()
            let result = await auth.authenticate(reason: reason)
            guard activePrompt == id, epoch == startEpoch else { return .stale }
            activePrompt = nil
            promptOpen = false
            guard result == .success else { return .notPassed }
        }
        guard epoch == startEpoch else { return .stale }
        apply(enabled: on)
        return .changed
    }

    /// "Lock after". Needs no prompt; an invalid value counts as the default.
    public func setLockAfter(_ seconds: Int) {
        let value = AppLockPolicy.validDelay(seconds)
        var next = settings ?? LockSettings(enabled: true)
        next.lockAfterSeconds = value
        settings = next
        policy.lockAfterSeconds = value
        store.save(next)
    }

    private func apply(enabled: Bool) {
        let next = LockSettings(enabled: enabled, lockAfterSeconds: settings?.lockAfterSeconds ?? AppLockPolicy.defaultSeconds)
        settings = next
        store.save(next)
        policy.lockAfterSeconds = next.lockAfterSeconds
        policy.setEnabled(enabled)
        isLocked = false
        left = false
        if enabled { onLockSwitchedOn() } else { releaseHeldChat() }
    }

    /// Sign-out: both settings go back to off and the default delay, and a prompt still open changes nothing.
    public func reset() {
        epoch += 1
        activePrompt = nil
        promptOpen = false
        heldChatID = nil
        store.clear()
        settings = LockSettings(enabled: false)
        policy = AppLockPolicy(lockAfterSeconds: AppLockPolicy.defaultSeconds, enabled: false)
        isLocked = false
        left = false
        expectedAt = nil
    }

    // MARK: - Held notification tap

    /// A notification tap wants to open `chatID`. Returns it when it may open now; while covered it is kept (only the
    /// newest one) and handed to `onHeldChatReleased` once, after unlock.
    public func openChatWhenUnlocked(_ chatID: String) -> String? {
        if isLocked {
            heldChatID = chatID
            return nil
        }
        return chatID
    }

    private func releaseHeldChat() {
        guard let id = heldChatID else { return }
        heldChatID = nil
        onHeldChatReleased(id)
    }
}
