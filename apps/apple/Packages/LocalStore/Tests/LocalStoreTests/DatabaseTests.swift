import Foundation
import Testing
@testable import LocalStore

func makeRepo() throws -> (Database, Repository) {
    let db = try Database.inMemory()
    try migrate(db)
    return (db, Repository(db: db, now: { Date(timeIntervalSince1970: 1_000) }))
}

struct Boom: Error {}

@Test func testMigrateSetsVersion() throws {
    let db = try Database.inMemory()
    try migrate(db)
    try migrate(db)
    let v = try db.query("PRAGMA user_version") { $0.int(0) }
    #expect(v == [1])
}

@Test func testTransactionRollsBack() throws {
    let (db, repo) = try makeRepo()
    #expect(throws: Boom.self) {
        try db.transaction { d in
            try d.execute("INSERT INTO kv(key, value) VALUES (?, ?)", [.text("k"), .blob(Data([1]))])
            throw Boom()
        }
    }
    #expect(try repo.get("k") == nil)
    try db.transaction { d in
        try d.execute("INSERT INTO kv(key, value) VALUES (?, ?)", [.text("k"), .blob(Data())])
    }
    #expect(try repo.get("k") == Data())
}

@Test func testPragmasAndStrict() throws {
    let db = try Database.inMemory()
    #expect(try db.query("PRAGMA foreign_keys") { $0.int(0) } == [1])
    try migrate(db)
    #expect(throws: DatabaseError.self) {
        try db.execute("INSERT INTO blocked(account_id, blocked_at) VALUES (?, ?)", [.text("a"), .text("not a number")])
    }
}

@Test func testFileDatabaseUsesWAL() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("ls-\(UUID().uuidString).sqlite")
    defer { for s in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: url.path + s) } }
    let db = try Database(url: url)
    try migrate(db)
    #expect(try db.query("PRAGMA journal_mode") { $0.text(0) } == ["wal"])
    #expect(try db.query("PRAGMA busy_timeout") { $0.int(0) } == [5000])
}

#if os(iOS)
@Test func testProtectionOnIOS() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("ls-\(UUID().uuidString).sqlite")
    defer { try? FileManager.default.removeItem(at: url) }
    let db = try Database(url: url)
    try migrate(db)
    try applyProtection(to: url, .untilFirstUnlock)
    let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
    // The iOS Simulator has no data protection and reports no key; a device reports the class that was set.
    if let level = attrs[.protectionKey] as? FileProtectionType {
        #expect(level == .completeUntilFirstUserAuthentication)
    }
}
#endif

@Test func testTwoConnectionsMigrateSameFile() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("ls-\(UUID().uuidString).sqlite")
    defer { for s in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: url.path + s) } }
    let dbs = [try Database(url: url), try Database(url: url)]
    let failures = Failures()
    DispatchQueue.concurrentPerform(iterations: 2) { i in
        do { try migrate(dbs[i]) } catch { failures.add("\(error)") }
    }
    #expect(failures.all.isEmpty, "\(failures.all)")
    #expect(try dbs[0].query("PRAGMA user_version") { $0.int(0) } == [1])
}

final class Failures: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String] = []
    func add(_ s: String) { lock.lock(); items.append(s); lock.unlock() }
    var all: [String] { lock.lock(); defer { lock.unlock() }; return items }
}

@Test func testMigrateRejectsNewerSchema() throws {
    let db = try Database.inMemory()
    try db.execute("PRAGMA user_version = 99")
    #expect(throws: SchemaTooNew.self) { try migrate(db) }
}

/// The notification extension's ownership read: never creates the file, never writes.
@Test func testReadOnlyOpenNeverCreatesOrWrites() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("ls-\(UUID().uuidString).sqlite")
    defer { for s in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: url.path + s) } }
    #expect(throws: DatabaseError.self) { _ = try Database.readOnly(url: url) }
    #expect(!FileManager.default.fileExists(atPath: url.path))

    let rw = try Database(url: url)
    try migrate(rw)
    try Repository(db: rw, now: { Date() }).set("k", Data([1]))
    let ro = try Database.readOnly(url: url)
    #expect(try Repository(db: ro, now: { Date() }).get("k") == Data([1]))
    #expect(throws: DatabaseError.self) { try Repository(db: ro, now: { Date() }).set("k", Data([2])) }
    #expect(try ro.query("PRAGMA query_only") { $0.int(0) } == [1])
}
