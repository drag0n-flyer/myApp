import Foundation

/// A clock that only moves when `advance(by:)` is called.
public final class ManualClock: Clock, @unchecked Sendable {
    public struct Instant: InstantProtocol {
        public var offset: Duration
        public init(offset: Duration) { self.offset = offset }
        public func advanced(by duration: Duration) -> Instant { Instant(offset: offset + duration) }
        public func duration(to other: Instant) -> Duration { other.offset - offset }
        public static func < (a: Instant, b: Instant) -> Bool { a.offset < b.offset }
    }

    private struct Sleeper {
        let id: Int
        let deadline: Instant
        let cont: CheckedContinuation<Void, Error>
    }

    private let lock = NSLock()
    private var current = Instant(offset: .zero)
    private var sleepers: [Sleeper] = []
    private var nextID = 0

    public init() {}

    public var now: Instant { lock.withLock { current } }
    public var minimumResolution: Duration { .zero }
    public var sleeperCount: Int { lock.withLock { sleepers.count } }

    public func sleep(until deadline: Instant, tolerance: Duration?) async throws {
        let id = lock.withLock { nextID += 1; return nextID }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                let action: (() -> Void)? = lock.withLock {
                    if Task.isCancelled { return { cont.resume(throwing: CancellationError()) } }
                    if deadline <= current { return { cont.resume() } }
                    sleepers.append(Sleeper(id: id, deadline: deadline, cont: cont))
                    return nil
                }
                action?()
            }
        } onCancel: {
            let s: Sleeper? = lock.withLock {
                guard let i = sleepers.firstIndex(where: { $0.id == id }) else { return nil }
                return sleepers.remove(at: i)
            }
            s?.cont.resume(throwing: CancellationError())
        }
    }

    public func advance(by d: Duration) {
        let due: [Sleeper] = lock.withLock {
            current = current.advanced(by: d)
            let due = sleepers.filter { $0.deadline <= current }.sorted { $0.deadline < $1.deadline }
            sleepers.removeAll { $0.deadline <= current }
            return due
        }
        for s in due { s.cont.resume() }
    }

    /// Waits (in real time, up to 5 s) until exactly `n` tasks are sleeping on this clock.
    @discardableResult
    public func waitForSleepers(_ n: Int) async -> Bool {
        for _ in 0..<2500 {
            if sleeperCount == n { return true }
            try? await Task.sleep(nanoseconds: 2_000_000)
        }
        return false
    }
}
