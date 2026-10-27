import Foundation

public final class ManualClock: Clock, @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date
    public init(_ start: Date = Date(timeIntervalSince1970: 0)) { current = start }
    public var now: Date { lock.lock(); defer { lock.unlock() }; return current }
    public func advance(_ seconds: TimeInterval) {
        lock.lock(); current = current.addingTimeInterval(seconds); lock.unlock()
    }
}
