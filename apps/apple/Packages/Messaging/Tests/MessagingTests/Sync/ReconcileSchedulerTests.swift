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

/// What `GET /v1/devices` answers in the test.
private final class DeviceList: @unchecked Sendable {
    private let lock = NSLock()
    private var ids: [String] = ["me", "other"]
    private var error: Error?
    func set(_ ids: [String]) { lock.withLock { self.ids = ids; error = nil } }
    func fail(_ error: Error) { lock.withLock { self.error = error } }
    func get() throws -> [String] { try lock.withLock { if let error { throw error }; return ids } }
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
    private func make(list: DeviceList = DeviceList()) -> (ReconcileScheduler, Runs, Sleeper) {
        let runs = Runs(), sleeper = Sleeper()
        let scheduler = ReconcileScheduler(
            deviceID: "me", reconcile: { try await runs.run() }, devices: { try list.get() },
            sleep: { try await sleeper.sleep($0) })
        return (scheduler, runs, sleeper)
    }

    // device.revoked is unsigned and the server never sends it to the revoked device (its socket closes with 4003).

    @Test func forgedEventNamingThisDeviceIsIgnored() async throws {
        let list = DeviceList()
        let (scheduler, runs, _) = make(list: list)
        #expect(await scheduler.deviceRevoked("me") == .none, "the server still lists this device")
        list.set([])
        #expect(await scheduler.deviceRevoked("me") == .none, "an empty list proves nothing")
        list.fail(URLError(.timedOut))
        #expect(await scheduler.deviceRevoked("me") == .none, "no answer proves nothing")
        // A 401 is handled by the client's own sign-out path, not here (no second sign-out).
        list.fail(ServerErrorStub())
        #expect(await scheduler.deviceRevoked("me") == .none)
        #expect(await runs.count == 0)
    }

    @Test func eventNamingThisDeviceSignsOutOnlyWithProof() async throws {
        let list = DeviceList()
        let (scheduler, _, _) = make(list: list)
        list.set(["other"])   // the server lists other devices of this account, but not this one
        #expect(await scheduler.deviceRevoked("me") == .signOut)
    }

    @Test func siblingEventOnlyRunsReconcile() async throws {
        let (scheduler, runs, sleeper) = make()
        #expect(await scheduler.deviceRevoked("other") == .none)
        #expect(await runs.count == 1)
        #expect(await sleeper.requested.isEmpty, "the event schedules no re-run of its own")
    }

    @Test func reconcileAndRevokedHandlingNeverOverlap() async throws {
        let (scheduler, runs, sleeper) = make()
        await runs.setSlow(true)
        await withTaskGroup(of: Void.self) { group in
            group.addTask { await scheduler.connected() }
            for _ in 0..<3 { group.addTask { _ = await scheduler.deviceRevoked("other") } }
            group.addTask { _ = await scheduler.deviceRevoked("me") }
        }
        await scheduler.waitForSleep(sleeper)
        await sleeper.fire()
        await scheduler.settle()
        #expect(await runs.count == 5)
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

private struct ServerErrorStub: Error {}
