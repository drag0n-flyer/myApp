import Foundation
import Testing
@testable import LocalStore

/// The schema version that adds `outbox.ciphertext` and `outbox.ciphertext_epoch`. Renumber here (and in
/// `Migrations.swift`) if another branch takes this version first.
private let ciphertextVersion: Int64 = 4

/// A database as the previous build left it, with queued outbox rows.
private func populatedBefore() throws -> Database {
    let db = try Database.inMemory()
    for s in schemaV1 { try db.execute(s) }
    for s in schemaV2 { try db.execute(s) }
    for s in schemaV3 { try db.execute(s) }
    try db.execute("PRAGMA user_version = \(ciphertextVersion - 1)")
    try db.execute("INSERT INTO chats(id, is_request, unread, muted, last_activity) VALUES ('bob',0,0,0,500)")
    try db.execute("""
        INSERT INTO messages(id, chat_id, sender_account, sent_at, received_at, envelope, status, folded)
        VALUES ('m1','bob','me',400,400,x'7b7d','sending','hi')
        """)
    try db.execute("INSERT INTO outbox(message_id, chat_id, envelope, attempts, next_attempt_at) VALUES ('m1','bob',x'7b7d',3,900)")
    try db.execute("INSERT INTO outbox(message_id, chat_id, envelope, attempts, next_attempt_at) VALUES ('c1','bob',x'7b7b',0,100)")
    return db
}

@Test func testMigrationAddsCiphertextColumnsAndKeepsQueuedRows() throws {
    let db = try populatedBefore()
    try migrate(db)
    #expect(try db.query("PRAGMA user_version") { $0.int(0) } == [latestSchemaVersion])
    #expect(latestSchemaVersion >= ciphertextVersion)
    let repo = Repository(db: db, now: { Date(timeIntervalSince1970: 1) })
    let rows = try repo.dueOutbox(at: Date.distantFuture)
    #expect(rows.map(\.messageID) == ["c1", "m1"])
    #expect(rows.allSatisfy { $0.ciphertext == nil && $0.ciphertextEpoch == nil && $0.ciphertextConversation == nil })
    let m1 = try #require(rows.first { $0.messageID == "m1" })
    #expect(m1.attempts == 3 && m1.envelope == Data("{}".utf8))
    #expect(try db.query("SELECT COUNT(*) FROM messages") { $0.int(0) } == [1])
    #expect(try db.query("PRAGMA foreign_key_check") { $0.int(0) }.isEmpty)
    #expect(try db.query("PRAGMA integrity_check") { $0.text(0) } == ["ok"])
    // An old row can take a ciphertext now.
    #expect(try repo.setOutboxCiphertext(id: "m1", envelope: Data("{}".utf8), ciphertext: Data([1, 2, 3]), epoch: 7, conversation: "s1"))
    let again = try #require(try repo.dueOutbox(at: Date.distantFuture).first { $0.messageID == "m1" })
    #expect(again.ciphertext == Data([1, 2, 3]) && again.ciphertextEpoch == 7 && again.ciphertextConversation == "s1")
    // A second open changes nothing.
    try migrate(db)
    #expect(try repo.dueOutbox(at: Date.distantFuture).first { $0.messageID == "m1" }?.ciphertext == Data([1, 2, 3]))
}

private func queued() throws -> Repository {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    _ = try repo.insertMessage(message("m1", status: "sending"))
    try repo.enqueue(OutboxRecord(messageID: "m1", chatID: "c1", envelope: Data("a".utf8), attempts: 0, nextAttemptAt: t(1)))
    return repo
}

private func row(_ repo: Repository) throws -> OutboxRecord? { try repo.dueOutbox(at: Date.distantFuture).first }

@Test func testCiphertextIsStoredWithItsEpochAndSurvivesBackoff() throws {
    let repo = try queued()
    #expect(try repo.setOutboxCiphertext(id: "m1", envelope: Data("a".utf8), ciphertext: Data([9]), epoch: 4, conversation: "s1"))
    try repo.bumpOutbox(id: "m1", nextAttempt: t(50))
    let r = try #require(try row(repo))
    #expect(r.ciphertext == Data([9]) && r.ciphertextEpoch == 4 && r.ciphertextConversation == "s1" && r.attempts == 1)
}

@Test func testCiphertextOfAnOldEnvelopeIsRefused() throws {
    let repo = try queued()
    // The envelope was replaced while the old one was being encrypted: the stale result must not be kept.
    try repo.enqueue(OutboxRecord(messageID: "m1", chatID: "c1", envelope: Data("b".utf8), attempts: 0, nextAttemptAt: t(1)))
    #expect(try repo.setOutboxCiphertext(id: "m1", envelope: Data("a".utf8), ciphertext: Data([9]), epoch: 4, conversation: "s1") == false)
    #expect(try row(repo)?.ciphertext == nil)
}

@Test func testReplacingTheEnvelopeClearsTheCiphertext() throws {
    let repo = try queued()
    _ = try repo.setOutboxCiphertext(id: "m1", envelope: Data("a".utf8), ciphertext: Data([9]), epoch: 4, conversation: "s1")
    // Enqueueing again (upload completion, manual retry) replaces the row.
    try repo.enqueue(OutboxRecord(messageID: "m1", chatID: "c1", envelope: Data("b".utf8), attempts: 0, nextAttemptAt: t(1)))
    #expect(try row(repo)?.ciphertext == nil)
    #expect(try row(repo)?.ciphertextEpoch == nil)
    _ = try repo.setOutboxCiphertext(id: "m1", envelope: Data("b".utf8), ciphertext: Data([8]), epoch: 5, conversation: "s1")
    #expect(try repo.replaceUnsentEnvelope(id: "m1", sender: "me", envelope: Data("c".utf8), folded: "") == true)
    #expect(try row(repo)?.ciphertext == nil)
    #expect(try row(repo)?.ciphertextEpoch == nil)
}

@Test func testCompletingOrRemovingTheRowLeavesNoCiphertext() throws {
    let repo = try queued()
    _ = try repo.setOutboxCiphertext(id: "m1", envelope: Data("a".utf8), ciphertext: Data([9]), epoch: 4, conversation: "s1")
    try repo.completeSend(id: "m1", status: "sent")
    #expect(try row(repo) == nil)
    try repo.enqueue(OutboxRecord(messageID: "m1", chatID: "c1", envelope: Data("a".utf8), attempts: 0, nextAttemptAt: t(1)))
    _ = try repo.setOutboxCiphertext(id: "m1", envelope: Data("a".utf8), ciphertext: Data([9]), epoch: 4, conversation: "s1")
    try repo.removeOutbox(id: "m1")
    #expect(try row(repo) == nil)
}

@Test func testClearOutboxCiphertext() throws {
    let repo = try queued()
    _ = try repo.setOutboxCiphertext(id: "m1", envelope: Data("a".utf8), ciphertext: Data([9]), epoch: 4, conversation: "s1")
    try repo.clearOutboxCiphertext(id: "m1")
    let r = try #require(try row(repo))
    #expect(r.ciphertext == nil && r.ciphertextEpoch == nil && r.ciphertextConversation == nil && r.envelope == Data("a".utf8))
}
