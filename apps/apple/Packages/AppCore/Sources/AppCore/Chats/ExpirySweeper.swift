import Foundation

/// How long the sweeper waits before looking again.
public enum ExpiryPlan {
    /// The longest wait: a message with a timer can arrive while the app is open, and the clock can change.
    public static let longestWait: TimeInterval = 60
    /// The shortest wait, so a clock that is a hair behind never makes the loop spin.
    public static let shortestWait: TimeInterval = 0.05

    /// The wait when something is due but the sweep removed nothing (a delete keeps failing): not a spin.
    public static let stuckWait: TimeInterval = 5

    /// The time until `next`, kept between `shortestWait` and `longestWait`; `longestWait` when nothing will expire.
    public static func delay(next: Date?, now: Date) -> TimeInterval {
        guard let next else { return longestWait }
        return min(longestWait, max(shortestWait, next.timeIntervalSince(now)))
    }
}

/// Removes expired messages: at start, on foreground, and by a timer set to the next expiry while the app is open.
/// The decisions are here so they can be tested with a fake clock; the app supplies the real clock, sleep and the
/// removal of delivered notifications.
@MainActor
public final class ExpirySweeper {
    private let store: PeopleStore
    private let now: () -> Date
    private let sleep: (TimeInterval) async throws -> Void
    private let removeNotifications: ([String]) -> Void
    private var task: Task<Void, Never>?

    /// `sleep` throws when cancelled, which ends `run`. `removeNotifications` gets the IDs of swept messages.
    public init(store: PeopleStore, now: @escaping () -> Date = Date.init,
                sleep: @escaping (TimeInterval) async throws -> Void = { try await Task.sleep(for: .seconds($0)) },
                removeNotifications: @escaping ([String]) -> Void) {
        self.store = store
        self.now = now
        self.sleep = sleep
        self.removeNotifications = removeNotifications
    }

    /// Sweeps what is due and returns how long to wait before the next look.
    @discardableResult
    public func tick() -> TimeInterval {
        let current = now()
        let removed = store.sweepExpired(now: current)
        if !removed.isEmpty { removeNotifications(removed) }
        let next = store.nextExpiry()
        if removed.isEmpty, let next, next <= current { return ExpiryPlan.stuckWait }
        return ExpiryPlan.delay(next: next, now: current)
    }

    /// Sweeps, waits for the next expiry, and repeats until `sleep` throws.
    public func run() async {
        while true {
            let delay = tick()
            do { try await sleep(delay) } catch { return }
        }
    }

    /// Starts (or restarts, so it sweeps again at once) the loop. Call at app start and on foreground.
    public func start() {
        task?.cancel()
        task = Task { [weak self] in await self?.run() }
    }

    /// Stops the loop (the app went to the background).
    public func stop() {
        task?.cancel()
        task = nil
    }
}
