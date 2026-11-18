import Foundation
import Testing
@testable import AppCore

/// A prompt that stays open until the test answers it.
@MainActor
private final class GatedAuth: DeviceAuth {
    var state: DeviceAuthAvailability = .ready
    private var waiting: CheckedContinuation<AuthResult, Never>?
    var isOpen: Bool { waiting != nil }
    func availability() -> DeviceAuthAvailability { state }
    func authenticate(reason: String) async -> AuthResult {
        await withCheckedContinuation { waiting = $0 }
    }
    func answer(_ result: AuthResult) { waiting?.resume(returning: result); waiting = nil }
    /// Waits (at most about 2 seconds) until a prompt is open.
    func untilOpen() async {
        for _ in 0..<200 where !isOpen { try? await Task.sleep(for: .milliseconds(10)) }
    }
}

@MainActor
private final class Clock {
    var instant = ContinuousClock.now
    func advance(_ seconds: Int) { instant = instant.advanced(by: .seconds(seconds)) }
}

@MainActor
private func make(enabled: Bool = true, delay: Int = 60, stored: LockSettings?? = nil, auth: any DeviceAuth = SampleDeviceAuth(),
                  inactiveCover: Bool = true, inactiveLeaves: Bool = false, clock: Clock = Clock())
    -> (AppLock, MemoryLockSettingsStore, Clock) {
    let store = MemoryLockSettingsStore(stored ?? LockSettings(enabled: enabled, lockAfterSeconds: delay))
    let lock = AppLock(store: store, auth: auth, coversWhenInactive: inactiveCover, inactiveCountsAsLeaving: inactiveLeaves,
                       now: { clock.instant })
    return (lock, store, clock)
}

@MainActor @Test func appLockStartsLockedWhenOnAndCoversContent() {
    let (lock, _, _) = make()
    #expect(lock.isLocked && lock.coversContent && lock.appLockOn)
}

@MainActor @Test func appLockStartsOpenWhenOff() {
    let (lock, _, _) = make(enabled: false)
    #expect(!lock.isLocked && !lock.coversContent && !lock.appLockOn)
}

@MainActor @Test func appLockUnknownSettingsCountAsLocked() async {
    let (lock, _, _) = make(stored: .some(nil))
    #expect(lock.settings == nil && lock.isLocked && lock.coversContent && lock.appLockOn)
    await lock.unlockWithPrompt(reason: "x")
    #expect(!lock.isLocked)
}

@MainActor @Test func appLockOnlySuccessUnlocks() async {
    let auth = SampleDeviceAuth(results: [.cancelled, .unavailable, .success])
    let (lock, _, _) = make(auth: auth)
    await lock.unlockWithPrompt(reason: "x")
    #expect(lock.isLocked)
    await lock.unlockWithPrompt(reason: "x")
    #expect(lock.isLocked)
    await lock.unlockWithPrompt(reason: "x")
    #expect(!lock.isLocked && !lock.coversContent)
}

@MainActor @Test func appLockWhenTheSystemCannotCheckTheCoverStaysAndSaysSoUntilRetried() async {
    let auth = SampleDeviceAuth(state: .unavailable)
    let (lock, _, _) = make(auth: auth)
    await lock.unlockWithPrompt(reason: "x")
    #expect(lock.isLocked && auth.requests == 0 && lock.cannotCheck)
    auth.state = .ready
    await lock.unlockWithPrompt(reason: "x")   // Unlock stays tappable: a retry works
    #expect(!lock.isLocked && !lock.cannotCheck)
}

@MainActor @Test func appLockNoPasscodeLetsThePersonIn() async {
    let auth = SampleDeviceAuth(state: .passcodeNotSet)
    let (lock, _, _) = make(auth: auth)
    await lock.unlockWithPrompt(reason: "x")
    #expect(!lock.isLocked && auth.requests == 0)
}

@Test func appLockAvailabilityOnlyPasscodeNotSetIsNoPasscode() {
    #expect(DeviceAuthAvailability.from(canEvaluate: true, passcodeNotSet: false) == .ready)
    #expect(DeviceAuthAvailability.from(canEvaluate: false, passcodeNotSet: true) == .passcodeNotSet)
    #expect(DeviceAuthAvailability.from(canEvaluate: false, passcodeNotSet: false) == .unavailable)
}

@MainActor @Test func appLockInactiveCoversAtOnceOnThePhoneAndOnlyWhileLockedOnTheMac() async {
    let (phone, _, _) = make(enabled: true)
    await phone.unlockOnly()
    phone.phaseChanged(.inactive)
    // Not locked: the content stays (state kept); a separate privacy window covers it.
    #expect(!phone.coversContent && phone.showsPrivacyCover && !phone.isLocked)
    phone.phaseChanged(.active)
    #expect(!phone.coversContent && !phone.showsPrivacyCover)
    let (mac, _, _) = make(enabled: true, inactiveCover: false)
    await mac.unlockOnly()
    mac.phaseChanged(.inactive)
    #expect(!mac.coversContent && !mac.showsPrivacyCover)
}

@MainActor @Test func appLockInactiveCoverNeverShowsWhenTheSettingIsOff() {
    let (lock, _, _) = make(enabled: false)
    lock.phaseChanged(.inactive)
    #expect(!lock.coversContent && !lock.showsPrivacyCover)
}

@MainActor @Test func appLockImmediatelyLocksInTheBackgroundAndAsksOnReturn() async {
    let (lock, _, _) = make(delay: 0)
    await lock.unlockOnly()
    let before = lock.promptRequests
    lock.phaseChanged(.inactive)
    lock.phaseChanged(.background)
    #expect(lock.isLocked)
    lock.phaseChanged(.inactive)
    lock.phaseChanged(.active)
    #expect(lock.isLocked && lock.promptRequests == before + 1)
}

@MainActor @Test func appLockInactiveBlipOnThePhoneDoesNotLock() async {
    let (lock, _, clock) = make(delay: 0)
    await lock.unlockOnly()
    lock.phaseChanged(.inactive)   // Control Centre, a system alert
    clock.advance(5)
    lock.phaseChanged(.active)
    #expect(!lock.isLocked)
}

@MainActor @Test func appLockDelayUsesTheMonotonicClock() async {
    let (lock, _, clock) = make(delay: 60)
    await lock.unlockOnly()
    lock.phaseChanged(.background)
    clock.advance(59)
    lock.phaseChanged(.active)
    #expect(!lock.isLocked)
    lock.phaseChanged(.background)
    clock.advance(60)
    lock.phaseChanged(.active)
    #expect(lock.isLocked)
}

@MainActor @Test func appLockClockBehindLocks() async {
    let (lock, _, clock) = make(delay: 3600)
    await lock.unlockOnly()
    lock.phaseChanged(.background)
    clock.advance(-5)
    lock.phaseChanged(.active)
    #expect(lock.isLocked)
}

@MainActor @Test func appLockExpectedReturnGivesOneAbsenceTheLongerAllowance() async {
    let (lock, _, clock) = make(delay: 0)
    await lock.unlockOnly()
    lock.expectReturn()
    lock.phaseChanged(.background)
    #expect(!lock.isLocked)
    clock.advance(120)
    lock.phaseChanged(.active)
    #expect(!lock.isLocked)
    lock.phaseChanged(.background)   // the next absence has no allowance
    #expect(lock.isLocked)
}

@MainActor @Test func appLockAStaleExpectedReturnIsIgnored() async {
    let (lock, _, clock) = make(delay: 0)
    await lock.unlockOnly()
    lock.expectReturn()
    clock.advance(301)   // the launch never took the app off screen
    lock.phaseChanged(.background)
    #expect(lock.isLocked)
}

@MainActor @Test func appLockAutomaticPromptIsAskedOncePerRequestAndOnlyWhileActive() async {
    let auth = SampleDeviceAuth(results: [.cancelled])
    let (lock, _, _) = make(auth: auth)
    lock.phaseChanged(.inactive)
    await lock.promptAutomatically(reason: "x")
    #expect(auth.requests == 0)
    lock.phaseChanged(.active)
    await lock.promptAutomatically(reason: "x")
    #expect(auth.requests == 1 && lock.isLocked)
    await lock.promptAutomatically(reason: "x")
    #expect(auth.requests == 1)   // a cancel leaves the cover; no second prompt by itself
    await lock.unlockWithPrompt(reason: "x")   // the button asks again
    #expect(auth.requests == 2 && !lock.isLocked)
}

@MainActor @Test func appLockMacPromptDoesNotLockItself() async {
    let gate = GatedAuth()
    let (lock, _, clock) = make(delay: 0, auth: gate, inactiveCover: false, inactiveLeaves: true)
    let task = Task { await lock.unlockWithPrompt(reason: "x") }
    await gate.untilOpen()
    lock.phaseChanged(.inactive)   // the system sheet takes focus
    clock.advance(2)
    lock.phaseChanged(.active)
    gate.answer(.success)
    await task.value
    #expect(!lock.isLocked)
    lock.phaseChanged(.inactive)   // really leaving
    #expect(lock.isLocked)
}

@MainActor @Test func appLockSwitchOnNeedsAPassedPromptAndRunsTheHookOnce() async {
    let auth = SampleDeviceAuth(results: [.cancelled, .success])
    let (lock, store, _) = make(enabled: false, auth: auth)
    var hooks = 0
    lock.onLockSwitchedOn = { hooks += 1 }
    #expect(await lock.requestSetEnabled(true, reason: "x") == .notPassed)
    #expect(!lock.appLockOn && store.stored == LockSettings(enabled: false) && hooks == 0)
    #expect(await lock.requestSetEnabled(true, reason: "x") == .changed)
    #expect(lock.appLockOn && !lock.isLocked && hooks == 1)
    #expect(store.stored == LockSettings(enabled: true, lockAfterSeconds: 60))
    #expect(await lock.requestSetEnabled(true, reason: "x") == .unchanged && hooks == 1)
}

@MainActor @Test func appLockSwitchOffNeedsAPassedPrompt() async {
    let auth = SampleDeviceAuth(results: [.success, .cancelled, .success])
    let (lock, store, _) = make(auth: auth)
    await lock.unlockOnly()
    #expect(await lock.requestSetEnabled(false, reason: "x") == .notPassed)
    #expect(lock.appLockOn)
    #expect(await lock.requestSetEnabled(false, reason: "x") == .changed)
    #expect(!lock.appLockOn && store.stored?.enabled == false)
}

@MainActor @Test func appLockSwitchOnIsRefusedWithoutAScreenLock() async {
    for state in [DeviceAuthAvailability.passcodeNotSet, .unavailable] {
        let auth = SampleDeviceAuth(state: state)
        let (lock, _, _) = make(enabled: false, auth: auth)
        #expect(await lock.requestSetEnabled(true, reason: "x") == .unavailable)
        #expect(!lock.appLockOn && auth.requests == 0)
    }
}

@MainActor @Test func appLockSwitchOffWithoutAPasscodeNeedsNoPromptButAnUnavailableSystemDoesNot() async {
    let none = SampleDeviceAuth(state: .passcodeNotSet)
    let (a, _, _) = make(auth: none)
    #expect(await a.requestSetEnabled(false, reason: "x") == .changed)
    #expect(!a.appLockOn && none.requests == 0)
    let other = SampleDeviceAuth(state: .unavailable)
    let (b, _, _) = make(auth: other)
    #expect(await b.requestSetEnabled(false, reason: "x") == .unavailable)   // fails closed: still on
    #expect(b.appLockOn && other.requests == 0)
}

@MainActor @Test func appLockAPromptAnsweredAfterSignOutChangesNothing() async {
    let gate = GatedAuth()
    let (lock, store, _) = make(enabled: false, auth: gate)
    var hooks = 0
    lock.onLockSwitchedOn = { hooks += 1 }
    let task = Task { await lock.requestSetEnabled(true, reason: "x") }
    await gate.untilOpen()
    lock.reset()
    gate.answer(.success)
    #expect(await task.value == .stale)
    #expect(!lock.appLockOn && store.stored == LockSettings(enabled: false) && hooks == 0)
}

@MainActor @Test func appLockSignOutResetsBothSettings() {
    let (lock, store, _) = make(delay: 3600)
    lock.reset()
    #expect(store.stored == LockSettings(enabled: false, lockAfterSeconds: 60))
    #expect(!lock.appLockOn && !lock.isLocked && lock.lockAfterSeconds == 60)
    lock.phaseChanged(.background)
    lock.phaseChanged(.active)
    #expect(!lock.isLocked)
}

@MainActor @Test func appLockLockAfterIsStoredAndOddValuesCountAsTheDefault() {
    let (lock, store, _) = make(enabled: false)
    lock.setLockAfter(300)
    #expect(lock.lockAfterSeconds == 300 && store.stored?.lockAfterSeconds == 300)
    lock.setLockAfter(7)
    #expect(lock.lockAfterSeconds == 60)
}

@MainActor @Test func appLockANotificationTapIsHeldUntilUnlockAndOpensOnce() async {
    let (lock, _, _) = make()
    var opened: [String] = []
    lock.onHeldChatReleased = { opened.append($0) }
    #expect(lock.openChatWhenUnlocked("a") == nil)
    #expect(lock.openChatWhenUnlocked("b") == nil)   // only the newest is kept
    #expect(opened.isEmpty)
    await lock.unlockWithPrompt(reason: "x")
    #expect(opened == ["b"])
    lock.phaseChanged(.background)
    lock.phaseChanged(.active)
    #expect(opened == ["b"])
    #expect(lock.openChatWhenUnlocked("c") == "c")   // not locked: open now
}

@MainActor @Test func appLockATapWhileOnlyThePrivacyCoverShowsOpensAtOnce() async {
    let (lock, _, _) = make()
    await lock.unlockOnly()
    lock.phaseChanged(.inactive)
    #expect(lock.openChatWhenUnlocked("a") == "a")
}

@MainActor @Test func appLockExpectedReturnCancelledLeavesNoAllowance() async {
    let (lock, _, _) = make(delay: 0)
    await lock.unlockOnly()
    lock.expectReturn()
    lock.cancelExpectedReturn()   // the picker closed without the app leaving
    lock.phaseChanged(.background)
    #expect(lock.isLocked)
}

@MainActor @Test func appLockExpectedReturnWhileTheViewerIsOpenKeepsTheAllowance() async {
    let (lock, _, clock) = make(delay: 0)
    await lock.unlockOnly()
    lock.expectReturn()
    lock.phaseChanged(.background)
    clock.advance(200)
    lock.phaseChanged(.active)
    #expect(!lock.isLocked)
}

@MainActor @Test func appLockLockIfDueLocksAWindowLeftVisibleAfterTheDelay() async {
    let (lock, _, clock) = make(delay: 60, inactiveCover: false, inactiveLeaves: true)
    await lock.unlockOnly()
    lock.phaseChanged(.inactive)
    #expect(lock.secondsUntilDue == 60)
    clock.advance(59)
    lock.lockIfDue()
    #expect(!lock.isLocked)
    clock.advance(1)
    lock.lockIfDue()
    #expect(lock.isLocked && lock.coversContent)
    lock.phaseChanged(.active)   // on return it stays locked and asks again
    #expect(lock.isLocked)
}

@MainActor @Test func appLockLockIfDueDoesNothingWhenNotAwayOrOff() async {
    let (lock, _, clock) = make(delay: 0)
    await lock.unlockOnly()
    clock.advance(9999)
    lock.lockIfDue()
    #expect(!lock.isLocked && lock.secondsUntilDue == nil)
    let (off, _, c2) = make(enabled: false, inactiveCover: false, inactiveLeaves: true)
    off.phaseChanged(.inactive)
    c2.advance(9999)
    off.lockIfDue()
    #expect(!off.isLocked && off.secondsUntilDue == nil)
}

@MainActor @Test func appLockLockIfDueUsesTheExpectedReturnAllowance() async {
    let (lock, _, clock) = make(delay: 0, inactiveCover: false, inactiveLeaves: true)
    await lock.unlockOnly()
    lock.expectReturn()
    lock.phaseChanged(.inactive)
    #expect(lock.secondsUntilDue == 300)
    clock.advance(299)
    lock.lockIfDue()
    #expect(!lock.isLocked)
    clock.advance(1)
    lock.lockIfDue()
    #expect(lock.isLocked)
}

@MainActor @Test func lockSettingsDefaultsStoreRoundTripsAndDistrustsOddData() {
    let name = "applock-test-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: name)!
    defer { defaults.removePersistentDomain(forName: name) }
    let store = DefaultsLockSettingsStore(defaults: defaults)
    #expect(store.load() == LockSettings(enabled: false))
    store.save(LockSettings(enabled: true, lockAfterSeconds: 300))
    #expect(store.load() == LockSettings(enabled: true, lockAfterSeconds: 300))
    defaults.set(7, forKey: DefaultsLockSettingsStore.delayKey)
    #expect(store.load() == LockSettings(enabled: true, lockAfterSeconds: 60))
    defaults.set("yes", forKey: DefaultsLockSettingsStore.enabledKey)
    #expect(store.load() == nil)   // unreadable: the app counts as locked
    store.clear()
    #expect(store.load() == LockSettings(enabled: false, lockAfterSeconds: 60))
}

@MainActor @Test func lockSampleAuthDefaultsToSuccessAndFollowsItsQueue() async {
    let auth = SampleDeviceAuth(results: [.cancelled])
    #expect(await auth.authenticate(reason: "x") == .cancelled)
    #expect(await auth.authenticate(reason: "x") == .success)
    #expect(auth.available)
}

@MainActor private extension AppLock {
    /// Test helper: pass the (sample) prompt, so the app is unlocked and tracking its absences.
    func unlockOnly() async { await unlockWithPrompt(reason: "x") }
}

@MainActor @Test func appLockPrivacyCoverStaysUpForTheWholeAbsenceEvenWhenLocked() {
    let (lock, _, _) = make()   // starts locked
    lock.phaseChanged(.inactive)
    #expect(lock.isLocked && lock.showsPrivacyCover && lock.coversContent)
    lock.phaseChanged(.active)
    #expect(!lock.showsPrivacyCover && lock.coversContent)
}

@MainActor @Test func appLockIsDecidedOnBackgroundToInactiveBeforeTheSceneIsActive() async {
    let (lock, _, clock) = make(delay: 60)
    await lock.unlockOnly()
    lock.phaseChanged(.inactive)
    lock.phaseChanged(.background)
    clock.advance(61)
    lock.phaseChanged(.inactive)   // the cover is rendered under the privacy window before .active
    #expect(lock.isLocked && lock.showsPrivacyCover)
    lock.phaseChanged(.active)
    #expect(lock.isLocked)
}

@MainActor @Test func appLockBackgroundToInactiveBeforeTheDelayStaysUnlocked() async {
    let (lock, _, clock) = make(delay: 60)
    await lock.unlockOnly()
    lock.phaseChanged(.background)
    clock.advance(30)
    lock.phaseChanged(.inactive)
    #expect(!lock.isLocked)
}
