import Foundation
import Testing
@testable import LocalStore

private func setup(status: String, deleted: Bool = false) throws -> Repository {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    var m = message("m1", status: status)
    m.deleted = deleted
    _ = try repo.insertMessage(m)
    return repo
}

@Test func testRequeueOnlyTouchesFailedMessages() throws {
    for status in ["sent", "delivered", "read", "sending"] {
        let repo = try setup(status: status)
        #expect(try repo.requeueSend(id: "m1") == false, "\(status)")
        #expect(try repo.message(id: "m1")?.status == status)
        #expect(try repo.dueOutbox(at: t(1000)).isEmpty)
    }
    let deleted = try setup(status: "failed", deleted: true)
    #expect(try deleted.requeueSend(id: "m1") == false)
    #expect(try deleted.dueOutbox(at: t(1000)).isEmpty)

    let failed = try setup(status: "failed")
    #expect(try failed.requeueSend(id: "m1") == true)
    #expect(try failed.message(id: "m1")?.status == "sending")
    #expect(try failed.dueOutbox(at: t(1000)).count == 1)
    // A second tap changes nothing: the message is already queued again.
    #expect(try failed.requeueSend(id: "m1") == false)
    #expect(try failed.dueOutbox(at: t(1000)).count == 1)
}

@Test func testReplaceUnsentEnvelopeOnlyChangesOwnUnsentRows() throws {
    for status in ["sending", "failed"] {
        let repo = try setup(status: status)
        #expect(try repo.replaceUnsentEnvelope(id: "m1", sender: "me", envelope: Data("new".utf8), folded: "f") == true)
        #expect(try repo.message(id: "m1")?.envelope == Data("new".utf8))
        #expect(try repo.message(id: "m1")?.folded == "f")
    }
    for status in ["sent", "delivered", "read"] {
        let repo = try setup(status: status)
        #expect(try repo.replaceUnsentEnvelope(id: "m1", sender: "me", envelope: Data("new".utf8), folded: "f") == false)
        #expect(try repo.message(id: "m1")?.envelope == Data("{}".utf8))
    }
    let other = try setup(status: "sending")
    #expect(try other.replaceUnsentEnvelope(id: "m1", sender: "someone", envelope: Data("new".utf8), folded: "f") == false)
    let deleted = try setup(status: "sending", deleted: true)
    #expect(try deleted.replaceUnsentEnvelope(id: "m1", sender: "me", envelope: Data("new".utf8), folded: "f") == false)
    #expect(try deleted.message(id: "m1")?.envelope == Data("{}".utf8))
}

@Test func testDeleteUnsentMessageRemovesRowOutboxAndAttachments() throws {
    let repo = try setup(status: "failed")
    try repo.enqueue(OutboxRecord(messageID: "m1", chatID: "c1", envelope: Data("{}".utf8), attempts: 0, nextAttemptAt: t(1)))
    try repo.upsertAttachment(AttachmentRecord(
        id: "pending-m1", messageID: "m1", kind: "file", key: Data(), digest: Data(), size: 1, cipherSize: 1,
        state: "local", downloaded: 0, localPath: "/x"))
    #expect(try repo.deleteUnsentMessage(id: "m1", sender: "someone") == false)
    #expect(try repo.deleteUnsentMessage(id: "m1", sender: "me") == true)
    #expect(try repo.message(id: "m1") == nil)
    #expect(try repo.attachment(messageID: "m1") == nil)
    #expect(try repo.dueOutbox(at: t(1000)).isEmpty)
}

@Test func testDeleteUnsentMessageRefusesSentRows() throws {
    for status in ["sent", "delivered", "read"] {
        let repo = try setup(status: status)
        #expect(try repo.deleteUnsentMessage(id: "m1", sender: "me") == false)
        #expect(try repo.message(id: "m1") != nil)
    }
}

@Test func testResumeAndFailUnsent() throws {
    let repo = try setup(status: "failed")
    #expect(try repo.resumeUnsent(id: "m1") == true)
    #expect(try repo.message(id: "m1")?.status == "sending")
    #expect(try repo.resumeUnsent(id: "m1") == false)
    #expect(try repo.markUnsentFailed(id: "m1") == true)
    #expect(try repo.message(id: "m1")?.status == "failed")
    #expect(try repo.markUnsentFailed(id: "m1") == false)
    let sent = try setup(status: "sent")
    #expect(try sent.markUnsentFailed(id: "m1") == false)
    #expect(try sent.message(id: "m1")?.status == "sent")
}
