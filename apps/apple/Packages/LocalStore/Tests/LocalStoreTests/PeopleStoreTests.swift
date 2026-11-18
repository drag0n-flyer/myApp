import Foundation
import Testing
@testable import LocalStore

/// A database exactly as the previous app version left it: schema version 2, populated.
private func populatedV2() throws -> Database {
    let db = try Database.inMemory()
    for s in schemaV1 + schemaV2 { try db.execute(s) }
    try db.execute("PRAGMA user_version = 2")
    try db.execute("INSERT INTO chats(id, is_request, unread, muted, last_activity, pinned_at, archived) VALUES ('bob',0,3,1,500,9,0)")
    try db.execute("""
        INSERT INTO messages(id, chat_id, sender_account, sent_at, received_at, envelope, status, folded)
        VALUES ('m1','bob','bob',400,400,x'7b7d','delivered','hi there')
        """)
    try db.execute("INSERT INTO kv(key, value) VALUES ('profile.ver', x'01')")
    return db
}

@Test func testMigrationToV3KeepsOldRowsAndAddsPeopleColumnsAndTable() throws {
    let db = try populatedV2()
    try migrate(db)
    #expect(try db.query("PRAGMA user_version") { $0.int(0) } == [3])
    #expect(try db.query("SELECT id, status, folded, expires_at IS NULL, caption IS NULL, link_preview IS NULL FROM messages") {
        [$0.text(0), $0.text(1), $0.text(2), String($0.int(3)), String($0.int(4)), String($0.int(5))]
    } == [["m1", "delivered", "hi there", "1", "1", "1"]])
    #expect(try db.query("SELECT unread, muted, pinned_at, timer_seconds FROM chats") { [$0.int(0), $0.int(1), $0.int(2), $0.int(3)] } == [[3, 1, 9, 0]])
    #expect(try db.query("SELECT COUNT(*) FROM profiles") { $0.int(0) } == [0])
    #expect(try db.query("SELECT COUNT(*) FROM kv") { $0.int(0) } == [1])
    #expect(try db.query("PRAGMA foreign_key_check") { $0.int(0) }.isEmpty)
    #expect(try db.query("PRAGMA integrity_check") { $0.text(0) } == ["ok"])
    try migrate(db)
    #expect(try db.query("PRAGMA user_version") { $0.int(0) } == [3])
    // The old message is still found by search.
    let repo = Repository(db: db, now: { t(1000) })
    #expect(try repo.search("there", limit: 5).map(\.messageID) == ["m1"])
}

private func record(_ id: String, at: Double = 1, expires: Double? = nil, caption: String? = nil, preview: String? = nil, folded: String = "") -> MessageRecord {
    var m = message(id, at: at, folded: folded)
    m.expiresAt = expires.map(t)
    m.caption = caption
    m.linkPreview = preview
    return m
}

@Test func testTimerSecondsDefaultsToZeroAndSurvivesChatUpserts() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    #expect(try repo.timerSeconds(chatID: "c1") == 0)
    try repo.setTimer(chatID: "c1", seconds: 604_800)
    #expect(try repo.timerSeconds(chatID: "c1") == 604_800)
    try repo.upsertChat(chat())
    #expect(try repo.timerSeconds(chatID: "c1") == 604_800)
    #expect(try repo.chat(id: "c1")?.timerSeconds == 604_800)
    try repo.setTimer(chatID: "c1", seconds: 0)
    #expect(try repo.timerSeconds(chatID: "c1") == 0)
    #expect(try repo.timerSeconds(chatID: "nobody") == 0)
}

@Test func testMessageKeepsExpiryCaptionAndPreview() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    _ = try repo.insertMessage(record("m1", expires: 5_000, caption: "Look", preview: "{\"url\":\"https://a.b\"}"))
    _ = try repo.insertMessage(record("m2"))
    let m1 = try #require(try repo.message(id: "m1"))
    #expect(m1.expiresAt == t(5_000) && m1.caption == "Look" && m1.linkPreview == "{\"url\":\"https://a.b\"}")
    let m2 = try #require(try repo.message(id: "m2"))
    #expect(m2.expiresAt == nil && m2.caption == nil && m2.linkPreview == nil)
}

@Test func testSweepExpiredRemovesRowFileSearchEntryAndOutbox() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    _ = try repo.insertMessage(record("gone", at: 1, expires: 2_000, folded: "banana split"))
    _ = try repo.insertMessage(record("edge", at: 2, expires: 2_100, folded: "banana edge"))
    _ = try repo.insertMessage(record("later", at: 3, expires: 2_200, folded: "banana later"))
    _ = try repo.insertMessage(record("forever", at: 4, folded: "banana forever"))
    try repo.upsertAttachment(AttachmentRecord(id: "a1", messageID: "gone", kind: "photo", key: Data([1]), digest: Data([2]),
                                               size: 1, cipherSize: 2, state: "local", downloaded: 2, localPath: "/tmp/x/a1"))
    try repo.enqueue(OutboxRecord(messageID: "gone", chatID: "c1", envelope: Data([1]), attempts: 0, nextAttemptAt: t(1)))
    try repo.setReaction(messageID: "gone", accountID: "bob", emoji: "x")

    #expect(try repo.search("banana", limit: 10).count == 4)
    let removed = try repo.sweepExpired(now: t(2_100))
    #expect(removed.map(\.id).sorted() == ["edge", "gone"])
    #expect(removed.first { $0.id == "gone" }?.chatID == "c1")
    #expect(removed.first { $0.id == "gone" }?.localPaths == ["/tmp/x/a1"])
    #expect(try repo.message(id: "gone") == nil && repo.message(id: "edge") == nil)
    #expect(try repo.message(id: "later") != nil && repo.message(id: "forever") != nil)
    #expect(try repo.attachment(id: "a1") == nil)
    #expect(try repo.reactions(messageID: "gone").isEmpty)
    #expect(try repo.dueOutbox(at: t(10_000)).isEmpty)
    #expect(try repo.search("split", limit: 10).isEmpty)
    #expect(Set(try repo.search("banana", limit: 10).map(\.messageID)) == ["later", "forever"])
    #expect(try repo.sweepExpired(now: t(2_100)).isEmpty)
}

@Test func testNextExpiryIsTheEarliestOfTheRemainingRows() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    #expect(try repo.nextExpiry() == nil)
    _ = try repo.insertMessage(record("a", expires: 900))
    _ = try repo.insertMessage(record("b", expires: 500))
    _ = try repo.insertMessage(record("c"))
    #expect(try repo.nextExpiry() == t(500))
}

@Test func testExpiredRowsAreHiddenFromReadsBeforeTheSweep() throws {
    let (_, repo) = try makeRepo()   // now = 1000
    try repo.upsertChat(chat())
    _ = try repo.insertMessage(record("old", at: 1, expires: 999, folded: "pear one"))
    _ = try repo.insertMessage(record("edge", at: 2, expires: 1_000, folded: "pear two"))
    _ = try repo.insertMessage(record("live", at: 3, expires: 1_001, folded: "pear three"))
    _ = try repo.insertMessage(record("plain", at: 4, folded: "pear four"))
    #expect(try repo.messages(chatID: "c1", before: nil, limit: 10).map(\.id) == ["live", "plain"])
    #expect(try repo.search("pear", limit: 10).map(\.messageID) == ["plain", "live"])
    #expect(try repo.search("pe", limit: 10).count == 2)
}

@Test func testCaptionIsFoundByTheExistingSearch() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    _ = try repo.insertMessage(record("m1", caption: "Sunset at the lake", folded: fold("Sunset at the lake")))
    #expect(try repo.search("lake", limit: 5).map(\.messageID) == ["m1"])
}

@Test func testProfileKeepsOnlyAStrictlyHigherVersion() throws {
    let (_, repo) = try makeRepo()
    #expect(try repo.profile(for: "bob") == nil)
    #expect(try repo.saveProfile(accountID: "bob", name: "Bob", photo: Data([0xFF, 0xD8]), ver: 3))
    #expect(try repo.profile(for: "bob") == ProfileRecord(accountID: "bob", name: "Bob", photo: Data([0xFF, 0xD8]), ver: 3))
    #expect(try repo.saveProfile(accountID: "bob", name: "Other", photo: nil, ver: 3) == false)
    #expect(try repo.saveProfile(accountID: "bob", name: "Older", photo: nil, ver: 2) == false)
    #expect(try repo.saveProfile(accountID: "bob", name: "Zero", photo: nil, ver: 0) == false)
    #expect(try repo.profile(for: "bob")?.name == "Bob")
    #expect(try repo.saveProfile(accountID: "bob", name: "Bobby", photo: nil, ver: 4))
    #expect(try repo.profile(for: "bob") == ProfileRecord(accountID: "bob", name: "Bobby", photo: nil, ver: 4))
}

@Test func testWipeRemovesProfiles() throws {
    let (_, repo) = try makeRepo()
    _ = try repo.saveProfile(accountID: "bob", name: "Bob", photo: nil, ver: 1)
    try repo.wipe()
    #expect(try repo.profile(for: "bob") == nil)
}
