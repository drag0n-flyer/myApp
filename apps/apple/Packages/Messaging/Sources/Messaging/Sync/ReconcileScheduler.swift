import Foundation

/// When own-device reconcile runs, and what a `device.revoked` event does (mls-profile "Removing a device": a
/// device runs the comparison when it comes online). The app calls `connected()` on every live connect, after the
/// inbox sync, `disconnected()` when the connection stops, and `deviceRevoked(_:)` for each event.
///
/// A removal needs two checks at least `DeviceSync.missingFor` apart, so each connection gets one more run
/// `rerunDelay` later while it stays up. Every run and every event is handled one at a time, in order: they never
/// overlap.
public actor ReconcileScheduler {
    /// Longer than `DeviceSync.missingFor` (10 minutes), so the second run can remove what the first one marked.
    public static let rerunDelay: Duration = .seconds(11 * 60)

    private let deviceID: String
    private let reconcile: @Sendable () async throws -> Void
    private let sleep: @Sendable (Duration) async throws -> Void
    private var rerun: Task<Void, Never>?
    private var generation = 0
    /// The last queued step; each new step waits for it.
    private var tail: Task<Void, Never>?

    /// - Parameter deviceID: this device.
    /// - Parameter reconcile: `DeviceSync.reconcileOwnDevices` in the app.
    /// - Parameter sleep: waits the given time; throws when cancelled. Tests pass their own.
    public init(
        deviceID: String,
        reconcile: @escaping @Sendable () async throws -> Void,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.deviceID = deviceID
        self.reconcile = reconcile
        self.sleep = sleep
    }

    /// Runs reconcile now, then schedules one more run `rerunDelay` later. A newer connect replaces the pending
    /// run. A failed run is not retried here; the next run (re-run or next connect) catches up.
    public func connected() async {
        await cancelRerun()
        let current = generation
        await runReconcile()
        // Disconnected or connected again while this run was going: that call owns the schedule.
        guard current == generation else { return }
        let sleep = self.sleep
        rerun = Task {
            do { try await sleep(Self.rerunDelay) } catch { return }
            guard !Task.isCancelled else { return }
            await self.runReconcile()
        }
    }

    /// Cancels the pending run and waits for it, so nothing runs after the connection (or the account) is gone.
    public func disconnected() async {
        await cancelRerun()
    }

    /// `device.revoked` is unsigned, and the server never sends it to the revoked device (that socket closes with
    /// 4003). So an event never removes or wipes by itself:
    /// - another device of this account: reconcile runs, and its two-lists-ten-minutes rule decides;
    /// - this device: ignored. Only a 401 or 4003 (revoked session) may wipe this device; the client's sign-out
    ///   path handles those. A device list never does: a wrong, partial or lagging list must not wipe.
    public func deviceRevoked(_ id: String) async {
        guard id != deviceID else { return }
        await runReconcile()
    }

    /// Waits for the scheduled run, if any (tests).
    func settle() async {
        await rerun?.value
    }

    private func runReconcile() async {
        let reconcile = self.reconcile
        await serially { try? await reconcile() }
    }

    /// Runs `work` after every step queued before it.
    private func serially<T: Sendable>(_ work: @escaping @Sendable () async -> T) async -> T {
        let previous = tail
        let task = Task<T, Never> {
            await previous?.value
            return await work()
        }
        tail = Task { _ = await task.value }
        return await task.value
    }

    private func cancelRerun() async {
        generation += 1
        let task = rerun
        rerun = nil
        task?.cancel()
        await task?.value
    }
}
