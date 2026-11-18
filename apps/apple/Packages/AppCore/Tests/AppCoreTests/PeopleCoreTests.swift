import Testing
@testable import AppCore

@Test func expiryCountsFromTheSendTime() {
    #expect(Expiry.expiresAt(atMs: 1_000, ttlSeconds: 60) == 61_000)
    #expect(Expiry.expiresAt(atMs: 1_000, ttlSeconds: nil) == nil)
    #expect(Expiry.expiresAt(atMs: 1_000, ttlSeconds: 0) == nil)
    #expect(Expiry.expiresAt(atMs: 1_000, ttlSeconds: -5) == nil)
    let want: Int64 = 1_731_622_400_000
    #expect(Expiry.expiresAt(atMs: 1_700_000_000_000, ttlSeconds: 31_622_400) == want)
}

@Test func aMessageIsExpiredFromItsLastSecondOn() {
    #expect(!Expiry.isExpired(nil, nowMs: 5_000))
    #expect(!Expiry.isExpired(61_000, nowMs: 60_999))
    #expect(Expiry.isExpired(61_000, nowMs: 61_000))
    #expect(Expiry.isExpired(61_000, nowMs: 61_001))
}

@Test func timerChoicesHaveFixedSeconds() {
    #expect(TimerChoice.off.seconds == 0)
    #expect(TimerChoice.day.seconds == 86_400)
    #expect(TimerChoice.week.seconds == 604_800)
    #expect(TimerChoice.quarter.seconds == 7_776_000)
    #expect(TimerChoice.allCases == [.off, .day, .week, .quarter])
}

@Test func timerChoiceFromSecondsKnowsOnlyTheFixedValues() {
    #expect(TimerChoice.from(seconds: 0) == .off)
    #expect(TimerChoice.from(seconds: 86_400) == .day)
    #expect(TimerChoice.from(seconds: 604_800) == .week)
    #expect(TimerChoice.from(seconds: 7_776_000) == .quarter)
    #expect(TimerChoice.from(seconds: 60) == nil)
    #expect(TimerChoice.from(seconds: -1) == nil)
    #expect(TimerChoice.from(seconds: 86_401) == nil)
}

@Test func aProfileIsAcceptedOnlyWithAStrictlyHigherVersion() {
    #expect(ProfileMerge.accept(current: nil, incoming: 1))
    #expect(ProfileMerge.accept(current: 1, incoming: 2))
    #expect(!ProfileMerge.accept(current: 2, incoming: 2))
    #expect(!ProfileMerge.accept(current: 3, incoming: 2))
    #expect(!ProfileMerge.accept(current: nil, incoming: 0))
}
