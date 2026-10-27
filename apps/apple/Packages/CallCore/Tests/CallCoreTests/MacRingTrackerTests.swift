import Testing
import Foundation
@testable import CallCore

@Suite struct MacRingTrackerTests {
    let t0 = Date(timeIntervalSince1970: 1_000)

    @Test func firstRingStartsDuplicateDoesNot() {
        var t = MacRingTracker()
        let r1 = t.ring(callID: "c1", at: t0)
        #expect(r1)
        let r2 = t.ring(callID: "c1", at: t0)
        #expect(!r2)
        #expect(t.ringingCallID == "c1")
    }
    @Test func secondCallWhileRingingDoesNotStart() {
        var t = MacRingTracker()
        let r3 = t.ring(callID: "c1", at: t0)
        #expect(r3)
        let r4 = t.ring(callID: "c2", at: t0)
        #expect(!r4)
    }
    @Test func endStopsOnlyTheRingingCall() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        let r5 = t.ended(callID: "other")
        #expect(!r5)
        let r6 = t.ended(callID: "c1")
        #expect(r6)
        let r7 = t.ended(callID: "c1")
        #expect(!r7)
        #expect(t.ringingCallID == nil)
        let again = t.ring(callID: "c1", at: t0)
        #expect(!again)   // an ended call never rings again
    }
    @Test func ringCapFiresOnce() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        let r8 = t.capReached(callID: "c1", now: t0.addingTimeInterval(44))
        #expect(!r8)
        let r9 = t.capReached(callID: "c1", now: t0.addingTimeInterval(45))
        #expect(r9)
        let r10 = t.capReached(callID: "c1", now: t0.addingTimeInterval(46))
        #expect(!r10)
    }
    @Test func answerWhileFreshAndRinging() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        let r11 = t.route(.answer, callID: "c1", ringedAt: t0, status: .ringing, now: t0.addingTimeInterval(3))
        #expect(r11 == .answer(callID: "c1"))
        #expect(t.ringingCallID == nil)
        let r12 = t.route(.answer, callID: "c1", ringedAt: t0, status: .ringing, now: t0.addingTimeInterval(4))
        #expect(r12 == .ignore)
    }
    @Test func answerAfterAppLaunchWithUnknownStatusAnswers() {
        var t = MacRingTracker()
        let r13 = t.route(.answer, callID: "c9", ringedAt: t0, status: nil, now: t0.addingTimeInterval(5))
        #expect(r13 == .answer(callID: "c9"))
    }
    @Test func staleAnswerIsExpired() {
        var t = MacRingTracker()
        let r14 = t.route(.answer, callID: "c1", ringedAt: t0, status: .ringing, now: t0.addingTimeInterval(45))
        #expect(r14 == .expired(callID: "c1", kind: .unanswered))
    }
    @Test func answerOfEndedCallIsExpired() {
        var t = MacRingTracker()
        let r15 = t.route(.answer, callID: "c1", ringedAt: t0, status: .ended(reason: "missed"), now: t0)
        #expect(r15 == .expired(callID: "c1", kind: .unanswered))
        let r16 = t.route(.answer, callID: "c2", ringedAt: t0, status: .ended(reason: "hangup"), now: t0)
        #expect(r16 == .expired(callID: "c2", kind: .remoteEnded))
        let r17 = t.route(.answer, callID: "c3", ringedAt: t0, status: .answered, now: t0)
        #expect(r17 == .expired(callID: "c3", kind: .answeredElsewhere))
    }
    @Test func declineAlwaysForwardsOnce() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        let r18 = t.route(.decline, callID: "c1", ringedAt: t0, status: .ended(reason: "missed"), now: t0.addingTimeInterval(60))
        #expect(r18 == .decline(callID: "c1"))
        let r19 = t.route(.decline, callID: "c1", ringedAt: t0, status: nil, now: t0)
        #expect(r19 == .ignore)
    }
}
