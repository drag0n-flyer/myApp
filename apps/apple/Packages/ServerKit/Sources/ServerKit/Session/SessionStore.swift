import Foundation

public struct Session: Codable, Equatable, Sendable {
    public var host: String
    public var accountID: String
    public var deviceID: String
    public var token: String

    public init(host: String, accountID: String, deviceID: String, token: String) {
        self.host = host
        self.accountID = accountID
        self.deviceID = deviceID
        self.token = token
    }
}

public protocol SessionStorage: Sendable {
    func load() throws -> Session?
    func save(_ s: Session) throws
    func clear() throws
}

public final class MemorySessionStorage: SessionStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var value: Session?

    public init(_ initial: Session? = nil) { value = initial }

    public func load() throws -> Session? { lock.withLock { value } }
    public func save(_ s: Session) throws { lock.withLock { value = s } }
    public func clear() throws { lock.withLock { value = nil } }
}

/// Caches the stored session under a lock. `generation` increases on every save.
public final class SessionStore: @unchecked Sendable {
    private let storage: SessionStorage
    private let lock = NSLock()
    private var cached: Session?
    private var saves = 0

    public init(storage: SessionStorage) {
        self.storage = storage
        self.cached = try? storage.load()
    }

    public var current: Session? { lock.withLock { cached } }

    var generation: Int { lock.withLock { saves } }

    public func save(_ s: Session) throws {
        try lock.withLock {
            try storage.save(s)
            cached = s
            saves += 1
        }
    }

    public func clear() {
        lock.withLock {
            try? storage.clear()
            cached = nil
        }
    }
}
