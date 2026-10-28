import Foundation
import Testing
@testable import Messaging

/// Counts reconcile runs; can be told to fail, or to take a while. Records the most runs ever in flight.
private actor Runs {
    private(set) var count = 0
    private(set) var inFlight = 0
    private(set) var maxInFlight = 0
    var fail = false
    var slow = false
    func run() async throws {
        count += 1
        inFlight += 1
        maxInFlight = max(maxInFlight, inFlight)
        defer { inFlight -= 1 }
        if slow { try? await Task.sleep(for: .milliseconds(20)) }
        if fail { throw URLError(.notConnectedToInternet) }
    }
    func setFail(_ value: Bool) { fail = value }
    func setSlow(_ value: Bool) { slow = value }
}

/// A sleep the test ends: `fire()` lets every pending sleep return. Cancellation ends a sleep with an error.
private actor Sleeper {
    private(set) var requested: [Duration] = []
    private var fired = 0
    func sleep(_ duration: Duration) async throws {
        requested.append(duration)
        let index = requested.count
        while fired < index {
            try Task.checkCancellation()
            try await Task.sleep(for: .milliseconds(1))
        }
    }
    func fire() { fired = requested.count }
}

@Suite("TestReconcileScheduler") struct TestReconcileScheduler {
    private func make() -> (ReconcileScheduler, Runs, Sleeper) {
        let runs = Runs(), sleeper = Sleeper()
        let scheduler = ReconcileScheduler(
            deviceID: "me", reconcile: { try await runs.run() }, sleep: { try await sleeper.sleep($0) })
        return (scheduler, runs, sleeper)
    }

    // device.revoked is unsigned and the server never sends it to the revoked device (its socket closes with 4003).
    // Only a 4003 or a confirmed 401 may wipe this device; that is the client's sign-out path, not this one.

    @Test func eventNamingThisDeviceNeverSignsOut() async throws {
        let (scheduler, runs, sleeper) = make()
        await scheduler.deviceRevoked("me")
        #expect(await runs.count == 0)
        #expect(await sleeper.requested.isEmpty)
    }

    @Test func siblingEventOnlyRunsReconcile() async throws {
        let (scheduler, runs, sleeper) = make()
        await scheduler.deviceRevoked("other")
        #expect(await runs.count == 1)
        #expect(await sleeper.requested.isEmpty, "the event schedules no re-run of its own")
    }

    @Test func reconcileAndRevokedHandlingNeverOverlap() async throws {
        let (scheduler, runs, sleeper) = make()
        await runs.setSlow(true)
        await withTaskGroup(of: Void.self) { group in
            group.addTask { await scheduler.connected() }
            for _ in 0..<4 { group.addTask { await scheduler.deviceRevoked("other") } }
        }
        await scheduler.waitForSleep(sleeper)
        await sleeper.fire()
        await scheduler.settle()
        #expect(await runs.count == 6)   // connect, four events, the re-run
        #expect(await runs.maxInFlight == 1)
    }

    @Test func rerunWaitsLongerThanTheRemovalRule() {
        #expect(ReconcileScheduler.rerunDelay == .seconds(11 * 60))
        #expect(ReconcileScheduler.rerunDelay > .milliseconds(DeviceSync.missingFor))
    }

    @Test func connectRunsNowAndOnceMoreElevenMinutesLater() async throws {
        let (scheduler, runs, sleeper) = make()
        await scheduler.connected()
        #expect(await runs.count == 1)
        await scheduler.waitForSleep(sleeper)
        #expect(await sleeper.requested == [ReconcileScheduler.rerunDelay])
        await sleeper.fire()
        await scheduler.settle()
        #expect(await runs.count == 2)
        // One re-run per connection: nothing else is scheduled.
        #expect(await sleeper.requested.count == 1)
    }

    @Test func disconnectCancelsTheRerun() async throws {
        let (scheduler, runs, sleeper) = make()
        await scheduler.connected()
        await scheduler.waitForSleep(sleeper)
        await scheduler.disconnected()
        await sleeper.fire()
        await scheduler.settle()
        #expect(await runs.count == 1)
    }

    @Test func reconnectReplacesThePendingRerun() async throws {
        let (scheduler, runs, sleeper) = make()
        await scheduler.connected()
        await scheduler.waitForSleep(sleeper)
        await scheduler.connected()
        #expect(await runs.count == 2)
        await scheduler.waitForSleep(sleeper, count: 2)
        await sleeper.fire()
        await scheduler.settle()
        #expect(await runs.count == 3, "only the newest connection's re-run runs")
    }

    @Test func failedRunStillSchedulesTheRerun() async throws {
        let (scheduler, runs, sleeper) = make()
        await runs.setFail(true)
        await scheduler.connected()
        await scheduler.waitForSleep(sleeper)
        await runs.setFail(false)
        await sleeper.fire()
        await scheduler.settle()
        #expect(await runs.count == 2)
    }
}

extension ReconcileScheduler {
    /// Waits until the re-run task has asked for `count` sleeps in total.
    fileprivate func waitForSleep(_ sleeper: Sleeper, count: Int = 1) async {
        while await sleeper.requested.count < count { try? await Task.sleep(for: .milliseconds(1)) }
    }
}
