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
    applyProtection(to: url, .untilFirstUnlock)
    let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
    #expect(attrs[.protectionKey] as? FileProtectionType == .completeUntilFirstUnlock)
}
#endif
