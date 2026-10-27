import Foundation
#if canImport(Glibc)
import Glibc
#elseif canImport(Musl)
import Musl
#elseif canImport(Darwin)
import Darwin
#endif

/// An exclusive advisory lock on a file, shared by every process that opens the same path
/// (the app and its notification extension share one app group container).
///
/// Each `withLock` call opens the file, takes `flock(LOCK_EX)` without blocking, retries every
/// `retry` until `timeout` has passed, runs the body, and closes the descriptor. The lock is
/// held for exactly one call. A timeout throws `MLSError.busy`.
public struct CrossProcessLock: Sendable {
    public let url: URL
    public let timeout: Duration
    public let retry: Duration

    public init(url: URL, timeout: Duration = .seconds(5), retry: Duration = .milliseconds(10)) {
        self.url = url
        self.timeout = timeout
        self.retry = retry
    }

    public func withLock<T>(_ body: () throws -> T) throws -> T {
        let fd = open(url.path, O_CREAT | O_RDWR, 0o600)
        guard fd >= 0 else {
            throw MLSError.storage("cannot open lock file: \(String(cString: strerror(errno)))")
        }
        defer { close(fd) }   // closing also drops the lock, even if the unlock below is skipped

        let clock = ContinuousClock()
        let deadline = clock.now + timeout
        while flock(fd, LOCK_EX | LOCK_NB) != 0 {
            let code = errno
            guard code == EWOULDBLOCK || code == EINTR else {
                throw MLSError.storage("cannot lock: \(String(cString: strerror(code)))")
            }
            if clock.now >= deadline { throw MLSError.busy }
            Thread.sleep(forTimeInterval: Self.seconds(retry))
        }
        defer { _ = flock(fd, LOCK_UN) }
        return try body()
    }

    private static func seconds(_ duration: Duration) -> TimeInterval {
        let parts = duration.components
        return TimeInterval(parts.seconds) + TimeInterval(parts.attoseconds) / 1e18
    }
}
