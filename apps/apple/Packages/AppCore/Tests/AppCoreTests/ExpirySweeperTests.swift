import Foundation
import Testing
@testable import AppCore

@MainActor
private final class FakeStore: PeopleStore {
    var due: [String: Date] = [:]
    func sweepExpired(now: Date) -> [String] {
        let gone = due.filter { $0.value <= now }.keys.sorted()
        for id in gone { due[id] = nil }
        return gone
    }
    func nextExpiry() -> Date? { due.values.min() }
}

@MainActor
private final class Clock {
    var now: Date
    init(_ seconds: TimeInterval) { now = Date(timeIntervalSince1970: seconds) }
}

@Suite struct ExpiryPlanTests {
    private let now = Date(timeIntervalSince1970: 1_000)

    @Test func nothingDueChecksAgainInAMinute() {
        #expect(ExpiryPlan.delay(next: nil, now: now) == 60)
    }

    @Test func soonIsWaitedForExactly() {
        #expect(ExpiryPlan.delay(next: now.addingTimeInterval(12.5), now: now) == 12.5)
    }

    @Test func farOffIsCheckedAgainInAMinute() {
        #expect(ExpiryPlan.delay(next: now.addingTimeInterval(86_400), now: now) == 60)
    }

    @Test func alreadyDueNeverSpins() {
        #expect(ExpiryPlan.delay(next: now.addingTimeInterval(-5), now: now) == 0.05)
        #expect(ExpiryPlan.delay(next: now, now: now) == 0.05)
    }

    @Test func bothDevicesCountFromTheSendTimeNotArrival() {
        // The sender stores `at + ttl`; the receiver does the same however late the message arrives.
        let sent = Expiry.expiresAt(atMs: 5_000, ttlSeconds: 60)
        #expect(sent == 65_000)
        #expect(Expiry.expiresAt(atMs: 5_000, ttlSeconds: 60) == sent)
    }
}

@MainActor
@Suite struct ExpirySweeperTests {
    @Test func aMessageGoesAtItsSecondNotBefore() {
        let store = FakeStore()
        store.due["m1"] = Date(timeIntervalSince1970: 2_000)
        var removed: [[String]] = []
        let clock = Clock(1_999.999)
        let sweeper = ExpirySweeper(store: store, now: { clock.now }, sleep: { _ in }, removeNotifications: { removed.append($0) })
        _ = sweeper.tick()
        #expect(removed.isEmpty)
        #expect(store.due["m1"] != nil)
        clock.now = Date(timeIntervalSince1970: 2_000)
        _ = sweeper.tick()
        #expect(removed == [["m1"]])
        #expect(store.due.isEmpty)
    }

    @Test func notificationsAreOnlyRemovedForSweptIDs() {
        let store = FakeStore()
        store.due = ["a": Date(timeIntervalSince1970: 10), "b": Date(timeIntervalSince1970: 20), "c": Date(timeIntervalSince1970: 30)]
        var removed: [[String]] = []
        let clock = Clock(25)
        let sweeper = ExpirySweeper(store: store, now: { clock.now }, sleep: { _ in }, removeNotifications: { removed.append($0) })
        let delay = sweeper.tick()
        #expect(removed == [["a", "b"]])
        #expect(delay == 5)
    }

    @Test func tickReturnsTheWaitForTheNextExpiry() {
        let store = FakeStore()
        store.due["m"] = Date(timeIntervalSince1970: 1_030)
        let clock = Clock(1_000)
        let sweeper = ExpirySweeper(store: store, now: { clock.now }, sleep: { _ in }, removeNotifications: { _ in })
        #expect(sweeper.tick() == 30)
    }

    private struct Stop: Error {}

    @Test func theLoopWakesForEachExpiryAndSeesNewOnes() async {
        let store = FakeStore()
        store.due = ["m1": Date(timeIntervalSince1970: 1_010)]
        let clock = Clock(1_000)
        var removed: [[String]] = []
        var waits: [TimeInterval] = []
        var sweeper: ExpirySweeper!
        sweeper = ExpirySweeper(
            store: store, now: { clock.now },
            sleep: { delay in
                waits.append(delay)
                clock.now = clock.now.addingTimeInterval(delay)
                // A message that arrives while the app is open, due 5 seconds after the first wake.
                if waits.count == 1 { store.due["m2"] = clock.now.addingTimeInterval(5) }
                if waits.count == 3 { throw Stop() }
            },
            removeNotifications: { removed.append($0) })
        await sweeper.run()
        #expect(waits == [10, 5, 60])
        #expect(removed == [["m1"], ["m2"]])
    }
}
