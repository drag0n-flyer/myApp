import Testing
import Foundation
@testable import CallCore

@Suite struct MacRingTrackerTests {
    let t0 = Date(timeIntervalSince1970: 1_000)

    private func route(_ t: inout MacRingTracker, _ a: MacNotificationAction, _ id: String = "c1",
                       status: CallStatus? = .ringing, at: TimeInterval = 3) -> MacRouteOutcome {
        t.route(a, callID: id, ringedAt: t0, status: status, now: t0.addingTimeInterval(at))
    }

    // MARK: Basics

    @Test func firstRingStartsDuplicateDoesNot() {
        var t = MacRingTracker()
        let first = t.ring(callID: "c1", at: t0)
        let dup = t.ring(callID: "c1", at: t0)
        #expect(first)
        #expect(!dup)
        #expect(t.ringingCallID == "c1")
    }
    @Test func secondCallWhileRingingDoesNotStart() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        let second = t.ring(callID: "c2", at: t0)
        #expect(!second)
        #expect(t.ringingCallID == "c1")
    }
    @Test func endStopsOnlyTheRingingCall() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        let other = t.ended(callID: "other")
        let own = t.ended(callID: "c1")
        let again = t.ended(callID: "c1")
        let reRing = t.ring(callID: "c1", at: t0)
        #expect(!other)
        #expect(own)
        #expect(!again)
        #expect(!reRing)
        #expect(t.ringingCallID == nil)
    }
    @Test func endBeforeRingSuppressesTheRing() {
        var t = MacRingTracker()
        let e = t.ended(callID: "c1")
        let r = t.ring(callID: "c1", at: t0)
        #expect(!e)
        #expect(!r)
    }

    // MARK: Cap (monotonic timer is authoritative)

    @Test func capDelayUsesInjectedClock() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        #expect(t.capDelay(callID: "c1", now: t0) == 45)
        let late = t.capDelay(callID: "c1", now: t0.addingTimeInterval(44.99))
        #expect(late != nil && abs(late! - 0.01) < 1e-6)
        #expect(t.capDelay(callID: "c1", now: t0.addingTimeInterval(100)) == 0)   // never negative
        #expect(t.capDelay(callID: "other", now: t0) == nil)
    }
    @Test func timerFiringCapsEvenWhenWallClockReads4499() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        // The wall clock says 44.99 s when the monotonic timer fires: the timer wins.
        #expect(t.capDelay(callID: "c1", now: t0.addingTimeInterval(44.99)) != 0)
        let fired = t.capFired(callID: "c1")
        let twice = t.capFired(callID: "c1")
        #expect(fired)
        #expect(!twice)
        #expect(t.ringingCallID == nil)
    }
    @Test func capForAnotherCallChangesNothing() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        let other = t.capFired(callID: "c2")
        #expect(!other)
        #expect(t.ringingCallID == "c1")
    }
    @Test func capAfterEndOrActionIsNoop() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        _ = t.ended(callID: "c1")
        let a = t.capFired(callID: "c1")
        #expect(!a)
        var u = MacRingTracker()
        _ = u.ring(callID: "c1", at: t0)
        _ = route(&u, .answer)
        let b = u.capFired(callID: "c1")
        #expect(!b)
    }
    @Test func afterCapEverythingIsIgnored() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        _ = t.capFired(callID: "c1")
        let ans = route(&t, .answer, at: 50)
        let dec = route(&t, .decline, at: 50)
        let end = t.ended(callID: "c1")
        let ring = t.ring(callID: "c1", at: t0)
        #expect(ans == MacRouteOutcome(route: .ignore, stopsRinging: false))
        #expect(dec == MacRouteOutcome(route: .ignore, stopsRinging: false))
        #expect(!end)
        #expect(!ring)
    }

    // MARK: Actions

    @Test func answerWhileFreshAndRinging() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        let a = route(&t, .answer)
        let again = route(&t, .answer, at: 4)
        #expect(a == MacRouteOutcome(route: .answer(callID: "c1"), stopsRinging: true))
        #expect(again.route == .ignore)
        #expect(t.ringingCallID == nil)
    }
    @Test func answerThenDeclineAndDeclineThenAnswerSettleOnce() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        let a = route(&t, .answer)
        let d = route(&t, .decline)
        #expect(a.route == .answer(callID: "c1"))
        #expect(d.route == .ignore)
        var u = MacRingTracker()
        _ = u.ring(callID: "c1", at: t0)
        let d2 = route(&u, .decline)
        let a2 = route(&u, .answer)
        #expect(d2.route == .decline(callID: "c1"))
        #expect(a2.route == .ignore)
    }
    @Test func answerAfterAppLaunchWithUnknownStatusAnswers() {
        var t = MacRingTracker()
        let r = route(&t, .answer, "c9", status: nil, at: 5)
        #expect(r == MacRouteOutcome(route: .answer(callID: "c9"), stopsRinging: false))
    }
    @Test func staleAnswerIsExpired() {
        var t = MacRingTracker()
        let r = route(&t, .answer, status: .ringing, at: 45)
        #expect(r.route == .expired(callID: "c1", kind: .unanswered))
    }
    @Test func answerOfEndedCallIsExpired() {
        var t = MacRingTracker()
        let a = route(&t, .answer, "c1", status: .ended(reason: "missed"), at: 0)
        let b = route(&t, .answer, "c2", status: .ended(reason: "hangup"), at: 0)
        let c = route(&t, .answer, "c3", status: .answered, at: 0)
        #expect(a.route == .expired(callID: "c1", kind: .unanswered))
        #expect(b.route == .expired(callID: "c2", kind: .remoteEnded))
        #expect(c.route == .expired(callID: "c3", kind: .answeredElsewhere))
    }
    @Test func declineAlwaysForwardsOnce() {
        var t = MacRingTracker()
        _ = t.ring(callID: "c1", at: t0)
        let d = route(&t, .decline, status: .ended(reason: "missed"), at: 60)
        let again = route(&t, .decline, status: nil, at: 0)
        #expect(d.route == .decline(callID: "c1"))
        #expect(again.route == .ignore)
    }
    @Test func actionForAnotherCallWhileOneRingsLeavesTheRingAlone() {
        var t = MacRingTracker()
        _ = t.ring(callID: "B", at: t0)
        let staleAnswer = route(&t, .answer, "A", status: .ringing, at: 3)
        let staleDecline = route(&t, .decline, "A2", status: nil, at: 3)
        let repeatA = route(&t, .decline, "A", status: nil, at: 3)
        #expect(!staleAnswer.stopsRinging)
        #expect(!staleDecline.stopsRinging)
        #expect(repeatA == MacRouteOutcome(route: .ignore, stopsRinging: false))
        #expect(t.ringingCallID == "B")
        let b = route(&t, .answer, "B")
        #expect(b == MacRouteOutcome(route: .answer(callID: "B"), stopsRinging: true))
    }

    // MARK: Every order of ring, answer, decline, remote end, cap

    private enum Ev: CaseIterable { case ring, answer, decline, end, cap }

    private static func permutations(_ items: [Ev]) -> [[Ev]] {
        guard items.count > 1 else { return [items] }
        return items.indices.flatMap { i -> [[Ev]] in
            var rest = items; let head = rest.remove(at: i)
            return permutations(rest).map { [head] + $0 }
        }
    }

    @Test func everyOrderSettlesAtMostOnceAndExactlyOnceWhenRingFirst() {
        let orders = Self.permutations(Ev.allCases)
        #expect(orders.count == 120)
        for order in orders {
            var t = MacRingTracker()
            var settled = 0          // outcomes that end the ring: end true, cap fired, action routed
            var rang = false
            var closedBeforeRing = false
            for ev in order {
                switch ev {
                case .ring:
                    if t.ring(callID: "c1", at: t0) { rang = true }
                case .end:
                    if t.ended(callID: "c1") { settled += 1 } else if !rang { closedBeforeRing = true }
                case .cap:
                    if t.capFired(callID: "c1") { settled += 1 }
                case .answer, .decline:
                    let o = route(&t, ev == .answer ? .answer : .decline)
                    if o.route != .ignore {
                        settled += 1
                        if !rang { closedBeforeRing = true }
                        #expect(o.stopsRinging == rang, "\(order)")
                    }
                }
            }
            #expect(settled <= 1 || closedBeforeRing, "\(order)")
            if order.first == .ring {
                #expect(rang, "\(order)")
                #expect(settled == 1, "\(order)")
                #expect(t.ringingCallID == nil, "\(order)")
            }
            // A ring that started is settled exactly once; nothing leaves it ringing.
            if rang { #expect(t.ringingCallID == nil, "\(order)") }
        }
    }
}
