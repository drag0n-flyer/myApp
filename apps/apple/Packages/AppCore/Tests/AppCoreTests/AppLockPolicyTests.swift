import Foundation
import Testing
@testable import AppCore

private let base = ContinuousClock.now
private func t(_ seconds: Int) -> LockInstant { base.advanced(by: .seconds(seconds)) }
private func unlocked(_ seconds: Int) -> AppLockPolicy {
    var p = AppLockPolicy(lockAfterSeconds: seconds)
    p.unlocked()
    return p
}

@Test func lockStartsLockedWhenOnAndUnlockedWhenOff() {
    #expect(AppLockPolicy(lockAfterSeconds: 60).locked)
    #expect(!AppLockPolicy(lockAfterSeconds: 60, enabled: false).locked)
    #expect(unlocked(60).locked == false)
}

@Test func lockImmediatelyLocksOnEveryReturn() {
    var p = unlocked(0)
    for i in 1...3 {
        p.onBackground(at: t(i * 1000))
        let r1 = p.onForeground(at: t(i * 1000))
        #expect(r1)
        p.unlocked()
    }
}

@Test func lockImmediatelyLocksAtTheMomentOfLeaving() {
    var p = unlocked(0)
    p.onBackground(at: t(1))
    #expect(p.locked)   // before the app returns, nothing may be waiting behind the screen
}

@Test func lockOneMinuteStaysUnlockedAt59AndLocksAt60() {
    var a = unlocked(60)
    a.onBackground(at: t(10))
    let r2 = a.onForeground(at: t(69))
    #expect(!r2)
    var b = unlocked(60)
    b.onBackground(at: t(10))
    let r3 = b.onForeground(at: t(70))
    #expect(r3)
}

@Test func lockABackwardsClockLocks() {
    var p = unlocked(3600)
    p.onBackground(at: t(50))
    let r4 = p.onForeground(at: t(49))
    #expect(r4)
    #expect(AppLockPolicy.isLocked(enabled: true, leftAt: t(50), now: t(49), delay: 3600, expectedReturn: false))
}

@Test func lockALockedAppStaysLocked() {
    var p = AppLockPolicy(lockAfterSeconds: 3600)
    p.onBackground(at: t(0))
    let r5 = p.onForeground(at: t(1))
    #expect(r5)
}

@Test func lockReturningWithoutLeavingChangesNothing() {
    var p = unlocked(0)
    let r6 = p.onForeground(at: t(5))
    #expect(!r6)
    #expect(!p.locked)
}

@Test func lockTimeAwayCountsFromTheFirstLeave() {
    var p = unlocked(60)
    p.onBackground(at: t(0))
    p.onBackground(at: t(50))
    let r7 = p.onForeground(at: t(60))
    #expect(r7)
}

@Test func lockEachAbsenceIsMeasuredOnItsOwn() {
    var p = unlocked(60)
    p.onBackground(at: t(0))
    let r8 = p.onForeground(at: t(30))
    #expect(!r8)
    p.onBackground(at: t(100))
    let r9 = p.onForeground(at: t(130))
    #expect(!r9)
}

@Test func lockTurningItOffUnlocksAndNothingLocksAfter() {
    var p = AppLockPolicy(lockAfterSeconds: 0)
    #expect(p.locked)
    p.setEnabled(false)
    #expect(!p.locked)
    p.onBackground(at: t(0))
    let r10 = p.onForeground(at: t(1))
    #expect(!r10)
}

@Test func lockTurningItOnLeavesTheAppUnlockedForNow() {
    var p = AppLockPolicy(lockAfterSeconds: 60, enabled: false)
    p.setEnabled(true)
    #expect(!p.locked)
    p.onBackground(at: t(0))
    let r11 = p.onForeground(at: t(60))
    #expect(r11)
}

@Test func lockOddDelayCountsAsTheDefault() {
    #expect(AppLockPolicy.delays == [0, 60, 300, 3600])
    #expect(AppLockPolicy.validDelay(7) == 60)
    #expect(AppLockPolicy.validDelay(0) == 0)
    #expect(AppLockPolicy.validDelay(3600) == 3600)
    #expect(AppLockPolicy.validDelay(-1) == 60)
    #expect(AppLockPolicy(lockAfterSeconds: 7).lockAfterSeconds == 60)
}

@Test func lockExpectedReturnImmediatelyStaysUnlockedAfterTwoMinutes() {
    var p = unlocked(0)
    p.onBackground(at: t(0), expectedReturn: true)
    #expect(!p.locked)
    let r12 = p.onForeground(at: t(120))
    #expect(!r12)
}

@Test func lockExpectedReturnLocksAfterSixMinutes() {
    var p = unlocked(0)
    p.onBackground(at: t(0), expectedReturn: true)
    let r13 = p.onForeground(at: t(360))
    #expect(r13)
}

@Test func lockWithoutExpectedReturnImmediatelyLocks() {
    var p = unlocked(0)
    p.onBackground(at: t(0))
    let r14 = p.onForeground(at: t(1))
    #expect(r14)
}

@Test func lockAllowanceDoesNotCarryToASecondAbsence() {
    var p = unlocked(0)
    p.onBackground(at: t(0), expectedReturn: true)
    let r15 = p.onForeground(at: t(10))
    #expect(!r15)
    p.onBackground(at: t(20))
    #expect(p.locked)
    let r16 = p.onForeground(at: t(21))
    #expect(r16)
}

@Test func lockLongerSettingIsKept() {
    #expect(AppLockPolicy.effectiveDelay(setting: 3600, expectedReturn: true) == 3600)
    #expect(AppLockPolicy.effectiveDelay(setting: 60, expectedReturn: true) == 300)
    #expect(AppLockPolicy.effectiveDelay(setting: 60, expectedReturn: false) == 60)
}

@Test func lockBackwardsClockStillLocksWithAnExpectedReturn() {
    var p = unlocked(0)
    p.onBackground(at: t(50), expectedReturn: true)
    let r17 = p.onForeground(at: t(49))
    #expect(r17)
}

@Test func lockIsLockedPureRule() {
    #expect(!AppLockPolicy.isLocked(enabled: false, leftAt: t(0), now: t(9999), delay: 0, expectedReturn: false))
    #expect(!AppLockPolicy.isLocked(enabled: true, leftAt: nil, now: t(9999), delay: 0, expectedReturn: false))
    #expect(AppLockPolicy.isLocked(enabled: true, leftAt: t(0), now: t(60), delay: 60, expectedReturn: false))
    #expect(!AppLockPolicy.isLocked(enabled: true, leftAt: t(0), now: t(299), delay: 60, expectedReturn: true))
}
