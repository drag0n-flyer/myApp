import Foundation

/// When own-device reconcile runs (mls-profile "Removing a device": a device runs the comparison when it comes
/// online). The app calls `connected()` on every live connect, after the inbox sync, and `disconnected()` when the
/// connection stops. A removal needs two checks at least `DeviceSync.missingFor` apart, so each connection gets
/// one more run `rerunDelay` later while it stays up.
public actor ReconcileScheduler {
    /// Longer than `DeviceSync.missingFor` (10 minutes), so the second run can remove what the first one marked.
    public static let rerunDelay: Duration = .seconds(11 * 60)

    private let reconcile: @Sendable () async throws -> Void
    private let sleep: @Sendable (Duration) async throws -> Void
    private var rerun: Task<Void, Never>?
    private var generation = 0

    /// - Parameter reconcile: `DeviceSync.reconcileOwnDevices` in the app.
    /// - Parameter sleep: waits the given time; throws when cancelled. Tests pass their own.
    public init(
        reconcile: @escaping @Sendable () async throws -> Void,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.reconcile = reconcile
        self.sleep = sleep
    }

    /// Runs reconcile now, then schedules one more run `rerunDelay` later. A newer connect replaces the pending
    /// run. A failed run is not retried here; the next run (re-run or next connect) catches up.
    public func connected() async {
        await cancelRerun()
        let current = generation
        try? await reconcile()
        // Disconnected or connected again while this run was going: that call owns the schedule.
        guard current == generation else { return }
        let reconcile = self.reconcile, sleep = self.sleep
        rerun = Task {
            do { try await sleep(Self.rerunDelay) } catch { return }
            guard !Task.isCancelled else { return }
            try? await reconcile()
        }
    }

    /// Cancels the pending run and waits for it, so nothing runs after the connection (or the account) is gone.
    public func disconnected() async {
        await cancelRerun()
    }

    /// Waits for the scheduled run, if any (tests).
    func settle() async {
        await rerun?.value
    }

    private func cancelRerun() async {
        generation += 1
        let task = rerun
        rerun = nil
        task?.cancel()
        await task?.value
    }
}
