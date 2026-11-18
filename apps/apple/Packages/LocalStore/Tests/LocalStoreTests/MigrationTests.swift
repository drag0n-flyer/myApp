import Foundation
import Testing
@testable import LocalStore

/// A database exactly as the previous app version left it: schema version 1, populated.
private func populatedV1() throws -> Database {
    let db = try Database.inMemory()
    for s in schemaV1 { try db.execute(s) }
    try db.execute("PRAGMA user_version = 1")
    try db.execute("INSERT INTO contacts(account_id, username, display_name, is_contact, added_at) VALUES ('bob','bob','Bob',1,10)")
    try db.execute("INSERT INTO chats(id, is_request, unread, muted, last_activity) VALUES ('bob',0,3,1,500)")
    try db.execute("INSERT INTO chats(id, is_request, unread, muted, last_activity) VALUES ('eve',1,0,0,100)")
    try db.execute("""
        INSERT INTO messages(id, chat_id, sender_account, sent_at, received_at, envelope, status, folded)
        VALUES ('m1','bob','bob',400,400,x'7b7d','delivered','hi')
        """)
    try db.execute("INSERT INTO folders(id, name, position) VALUES ('f1','Work',0)")
    try db.execute("INSERT INTO folder_chats(folder_id, chat_id) VALUES ('f1','bob')")
    try db.execute("INSERT INTO kv(key, value) VALUES ('k', x'01')")
    return db
}

private func snapshot(_ db: Database) throws -> [String] {
    var rows: [String] = []
    rows += try db.query("SELECT id, is_request, unread, muted, last_activity FROM chats ORDER BY id") {
        "chat \($0.text(0)) \($0.int(1)) \($0.int(2)) \($0.int(3)) \($0.int(4))"
    }
    rows += try db.query("SELECT id, chat_id, sent_at, status, folded FROM messages ORDER BY id") {
        "msg \($0.text(0)) \($0.text(1)) \($0.int(2)) \($0.text(3)) \($0.text(4))"
    }
    rows += try db.query("SELECT account_id, display_name FROM contacts") { "contact \($0.text(0)) \($0.text(1))" }
    rows += try db.query("SELECT folder_id, chat_id FROM folder_chats") { "fc \($0.text(0)) \($0.text(1))" }
    rows += try db.query("SELECT key FROM kv") { "kv \($0.text(0))" }
    return rows
}

@Test func testMigrationToV2KeepsEveryOldRowAndAddsTheNewColumns() throws {
    let db = try populatedV1()
    let before = try snapshot(db)
    try migrate(db)
    #expect(try db.query("PRAGMA user_version") { $0.int(0) } == [2])
    #expect(try snapshot(db) == before)
    // Old rows are not pinned, not archived, and have no draft.
    #expect(try db.query("SELECT pinned_at IS NULL, archived FROM chats ORDER BY id") { [$0.int(0), $0.int(1)] } == [[1, 0], [1, 0]])
    #expect(try db.query("SELECT COUNT(*) FROM drafts") { $0.int(0) } == [0])
    #expect(try db.query("PRAGMA foreign_key_check") { $0.int(0) }.isEmpty)
    #expect(try db.query("PRAGMA integrity_check") { $0.text(0) } == ["ok"])
}

@Test func testSecondOpenChangesNothing() throws {
    let db = try populatedV1()
    try migrate(db)
    try db.execute("UPDATE chats SET pinned_at = 77, archived = 1 WHERE id = 'bob'")
    let before = try snapshot(db)
    try migrate(db)
    #expect(try snapshot(db) == before)
    #expect(try db.query("SELECT pinned_at, archived FROM chats WHERE id='bob'") { [$0.int(0), $0.int(1)] } == [[77, 1]])
    #expect(try db.query("PRAGMA user_version") { $0.int(0) } == [2])
}

@Test func testDraftsTableCascadesWithTheChat() throws {
    let db = try populatedV1()
    try migrate(db)
    try db.execute("INSERT INTO drafts(chat_id, text, updated_at) VALUES ('bob','half',1)")
    try db.execute("DELETE FROM chats WHERE id='bob'")
    #expect(try db.query("SELECT COUNT(*) FROM drafts") { $0.int(0) } == [0])
}

@Test func testRepositoryReadsOldRowsAfterMigration() throws {
    let db = try populatedV1()
    try migrate(db)
    let repo = Repository(db: db, now: { Date(timeIntervalSince1970: 1) })
    let bob = try #require(try repo.chat(id: "bob"))
    #expect(bob.unread == 3 && bob.muted && bob.pinnedAt == nil && !bob.archived)
}
