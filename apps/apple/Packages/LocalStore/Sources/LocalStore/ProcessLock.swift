import Foundation
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

public struct LockTimeout: Error, Equatable {}

/// The lock file could not be opened or locked (not a timeout).
public struct LockError: Error, Equatable {
    public let errno: Int32
}

/// Cross-process lock (`flock`) shared by the app and the notification extension.
public final class ProcessLock: Sendable {
    private let url: URL

    public init(url: URL) {
        self.url = url
    }

    public func withLock<T>(timeout: Duration, _ body: () throws -> T) throws -> T {
        let fd = open(url.path, O_CREAT | O_RDWR, 0o600)
        guard fd >= 0 else { throw LockError(errno: errno) }
        defer { close(fd) }
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        while flock(fd, LOCK_EX | LOCK_NB) != 0 {
            let e = errno
            guard e == EWOULDBLOCK || e == EINTR else { throw LockError(errno: e) }
            guard clock.now < deadline else { throw LockTimeout() }
            Thread.sleep(forTimeInterval: 0.02)
        }
        defer { flock(fd, LOCK_UN) }
        return try body()
    }

    /// Like `withLock`, for a body that awaits (the MLS engine is async). The lock is a `flock` on its own
    /// open file description, so it does not matter which thread the body resumes on. Waiting does not
    /// block a thread.
    public func withAsyncLock<T>(
        isolation: isolated (any Actor)? = #isolation, timeout: Duration, _ body: () async throws -> T
    ) async throws -> T {
        let fd = open(url.path, O_CREAT | O_RDWR, 0o600)
        guard fd >= 0 else { throw LockError(errno: errno) }
        defer { close(fd) }
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        while flock(fd, LOCK_EX | LOCK_NB) != 0 {
            let e = errno
            guard e == EWOULDBLOCK || e == EINTR else { throw LockError(errno: e) }
            guard clock.now < deadline else { throw LockTimeout() }
            try await Task.sleep(for: .milliseconds(20))
        }
        defer { flock(fd, LOCK_UN) }
        return try await body()
    }
}
