import Foundation
#if canImport(SQLite3)
import SQLite3
#else
import CSQLite
#endif

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

public enum SQLValue: Sendable, Equatable {
    case null
    case int(Int64)
    case double(Double)
    case text(String)
    case blob(Data)
}

public struct DatabaseError: Error, Equatable, CustomStringConvertible {
    public let code: Int32
    public let message: String
    public var description: String { "sqlite error \(code): \(message)" }
}

/// One result row, read by column index. Valid only inside the `row` closure of `Database.query`.
public struct Row {
    let stmt: OpaquePointer

    public func int(_ i: Int) -> Int64 { sqlite3_column_int64(stmt, Int32(i)) }
    public func double(_ i: Int) -> Double { sqlite3_column_double(stmt, Int32(i)) }
    public func text(_ i: Int) -> String {
        guard let c = sqlite3_column_text(stmt, Int32(i)) else { return "" }
        return String(cString: c)
    }
    public func blob(_ i: Int) -> Data {
        let n = Int(sqlite3_column_bytes(stmt, Int32(i)))
        guard n > 0, let p = sqlite3_column_blob(stmt, Int32(i)) else { return Data() }
        return Data(bytes: p, count: n)
    }
    public func isNull(_ i: Int) -> Bool { sqlite3_column_type(stmt, Int32(i)) == SQLITE_NULL }
}

/// Thin SQLite wrapper. All access is serialized by a lock (recursive, so a
/// transaction body can call back into the same `Database`).
public final class Database: @unchecked Sendable {
    private var handle: OpaquePointer?
    private let lock = NSRecursiveLock()
    private var depth = 0

    public convenience init(url: URL) throws {
        try self.init(path: url.path)
        try applyProtection(to: url, .untilFirstUnlock)  // database, -wal and -shm (iOS only)
    }

    public static func inMemory() throws -> Database {
        try Database(path: ":memory:")
    }

    private init(path: String) throws {
        var h: OpaquePointer?
        let rc = sqlite3_open_v2(path, &h, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil)
        guard rc == SQLITE_OK, let h else {
            let msg = h.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            if let h { sqlite3_close_v2(h) }
            throw DatabaseError(code: rc, message: msg)
        }
        handle = h
        sqlite3_busy_timeout(h, 5000)
        try execute("PRAGMA foreign_keys=ON")
        try execute("PRAGMA journal_mode=WAL")
        try execute("PRAGMA busy_timeout=5000")
        try execute("PRAGMA secure_delete=ON")
    }

    deinit {
        if let handle { sqlite3_close_v2(handle) }
    }

    public func execute(_ sql: String, _ args: [SQLValue] = []) throws {
        try run(sql, args)
    }

    /// Like `execute`, and returns the number of rows changed by the statement.
    @discardableResult
    public func run(_ sql: String, _ args: [SQLValue] = []) throws -> Int {
        lock.lock()
        defer { lock.unlock() }
        let stmt = try prepare(sql, args)
        defer { sqlite3_finalize(stmt) }
        var rc = sqlite3_step(stmt)
        while rc == SQLITE_ROW { rc = sqlite3_step(stmt) }
        guard rc == SQLITE_DONE else { throw lastError(rc) }
        return Int(sqlite3_changes(handle))
    }

    public func query<T>(_ sql: String, _ args: [SQLValue] = [], row: (Row) throws -> T) throws -> [T] {
        lock.lock()
        defer { lock.unlock() }
        let stmt = try prepare(sql, args)
        defer { sqlite3_finalize(stmt) }
        var out: [T] = []
        var rc = sqlite3_step(stmt)
        while rc == SQLITE_ROW {
            out.append(try row(Row(stmt: stmt)))
            rc = sqlite3_step(stmt)
        }
        guard rc == SQLITE_DONE else { throw lastError(rc) }
        return out
    }

    public func transaction<T>(_ body: (Database) throws -> T) throws -> T {
        lock.lock()
        defer { lock.unlock() }
        if depth > 0 { return try body(self) }  // join the outer transaction
        try execute("BEGIN IMMEDIATE")
        depth += 1
        defer { depth -= 1 }
        do {
            let result = try body(self)
            try execute("COMMIT")
            return result
        } catch {
            _ = try? execute("ROLLBACK")
            throw error
        }
    }

    private func prepare(_ sql: String, _ args: [SQLValue]) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        let rc = sqlite3_prepare_v2(handle, sql, -1, &stmt, nil)
        guard rc == SQLITE_OK, let stmt else {
            sqlite3_finalize(stmt)
            throw lastError(rc)
        }
        for (i, value) in args.enumerated() {
            let idx = Int32(i + 1)
            let brc: Int32
            switch value {
            case .null: brc = sqlite3_bind_null(stmt, idx)
            case .int(let v): brc = sqlite3_bind_int64(stmt, idx, v)
            case .double(let v): brc = sqlite3_bind_double(stmt, idx, v)
            case .text(let v): brc = sqlite3_bind_text(stmt, idx, v, -1, sqliteTransient)
            case .blob(let v):
                if v.isEmpty {
                    brc = sqlite3_bind_zeroblob(stmt, idx, 0)  // a nil pointer would bind NULL
                } else {
                    brc = v.withUnsafeBytes { sqlite3_bind_blob(stmt, idx, $0.baseAddress, Int32(v.count), sqliteTransient) }
                }
            }
            guard brc == SQLITE_OK else {
                sqlite3_finalize(stmt)
                throw lastError(brc)
            }
        }
        return stmt
    }

    private func lastError(_ rc: Int32) -> DatabaseError {
        DatabaseError(code: rc, message: handle.map { String(cString: sqlite3_errmsg($0)) } ?? "closed")
    }
}
