import Foundation
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

public struct LockTimeout: Error, Equatable {}

/// Cross-process lock (`flock`) shared by the app and the notification extension.
public final class ProcessLock: Sendable {
    private let url: URL

    public init(url: URL) {
        self.url = url
    }

    public func withLock<T>(timeout: Duration, _ body: () throws -> T) throws -> T {
        let fd = open(url.path, O_CREAT | O_RDWR, 0o600)
        guard fd >= 0 else { throw LockTimeout() }
        defer { close(fd) }
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        while flock(fd, LOCK_EX | LOCK_NB) != 0 {
            guard errno == EWOULDBLOCK || errno == EINTR, clock.now < deadline else { throw LockTimeout() }
            Thread.sleep(forTimeInterval: 0.02)
        }
        defer { flock(fd, LOCK_UN) }
        return try body()
    }
}
