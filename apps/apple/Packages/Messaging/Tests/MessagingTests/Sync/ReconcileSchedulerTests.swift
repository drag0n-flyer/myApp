import Foundation
import Testing
@testable import Messaging

/// Counts reconcile runs; can be told to fail.
private actor Runs {
    private(set) var count = 0
    var fail = false
    func run() throws {
        count += 1
        if fail { throw URLError(.notConnectedToInternet) }
    }
    func setFail(_ value: Bool) { fail = value }
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
        let scheduler = ReconcileScheduler(reconcile: { try await runs.run() }, sleep: { try await sleeper.sleep($0) })
        return (scheduler, runs, sleeper)
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
