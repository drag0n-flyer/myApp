import Foundation
import LocalStore

/// Holds the cross-process `flock` across `await`s.
///
/// `ProcessLock.withLock` takes a synchronous body, but the MLS engine is async. A dedicated
/// thread takes the lock and parks until `body` finishes, so no cooperative thread is blocked
/// while waiting. Throws `LockTimeout` when the lock is not free within `timeout`.
func withProcessLock<T: Sendable>(
    _ lock: ProcessLock, timeout: Duration, _ body: () async throws -> T
) async throws -> T {
    let release = DispatchSemaphore(value: 0)
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        let thread = Thread {
            do {
                try lock.withLock(timeout: timeout) {
                    continuation.resume()
                    release.wait()
                }
            } catch {
                continuation.resume(throwing: error)
            }
        }
        thread.start()
    }
    defer { release.signal() }
    return try await body()
}
