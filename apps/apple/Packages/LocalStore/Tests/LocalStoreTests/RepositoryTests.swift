import Foundation
import Testing
@testable import LocalStore

func t(_ s: Double) -> Date { Date(timeIntervalSince1970: s) }

func chat(_ id: String = "c1", activity: Double = 1) -> ChatRecord {
    ChatRecord(id: id, isRequest: false, unread: 0, muted: false, lastActivity: t(activity))
}

func conv(_ sid: String, chat: String = "c1", active: Bool = true, mode: String = "e2ee") -> ConversationRecord {
    ConversationRecord(serverID: sid, chatID: chat, groupID: Data(sid.utf8), mode: mode, innerMode: nil,
                       modeMismatch: false, epoch: 1, active: active, createdAt: t(1))
}

func message(_ id: String, chat: String = "c1", at: Double = 1, folded: String = "", status: String = "sent") -> MessageRecord {
    MessageRecord(id: id, chatID: chat, conversationID: nil, senderAccount: "me", senderDevice: nil,
                  sentAt: t(at), receivedAt: t(at), envelope: Data("{}".utf8), status: status, replyTo: nil,
                  editedAt: nil, deleted: false, undecryptable: false, folded: folded)
}

@Test func testInsertMessageIgnoresDuplicate() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    #expect(try repo.insertMessage(message("m1")) == true)
    #expect(try repo.insertMessage(message("m1")) == false)
}

@Test func testStatusNeverLowers() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    _ = try repo.insertMessage(message("m1", status: "sending"))
    try repo.updateStatus(ids: ["m1"], to: "read")
    try repo.updateStatus(ids: ["m1"], to: "delivered")
    #expect(try repo.message(id: "m1")?.status == "read")
    try repo.updateStatus(ids: [], to: "sent")
}

@Test func testMessagesPage() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    for i in 1...120 { _ = try repo.insertMessage(message(String(format: "m%03d", i), at: Double(i))) }
    let p1 = try repo.messages(chatID: "c1", before: nil, limit: 50)
    #expect(p1.count == 50)
    #expect(p1.first?.id == "m071")
    #expect(p1.last?.id == "m120")
    let p2 = try repo.messages(chatID: "c1", before: p1[0], limit: 50)
    #expect(p2.first?.id == "m021")
    #expect(p2.last?.id == "m070")
}

@Test func testPreferredConversation() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    try repo.upsertConversation(conv("b1"))
    try repo.upsertConversation(conv("a1"))
    try repo.upsertConversation(conv("01", active: false))
    #expect(try repo.preferredConversation(chatID: "c1")?.serverID == "a1")
    #expect(try repo.conversations(chatID: "c1").count == 3)
    #expect(try repo.conversation(groupID: Data("b1".utf8))?.serverID == "b1")
}

@Test func testCascadeDeleteChat() throws {
    let (db, repo) = try makeRepo()
    try repo.upsertChat(chat())
    try repo.upsertConversation(conv("a1"))
    try repo.replaceMembers(conversationID: "a1", [(identity: "x/1", signatureKey: Data([1]))])
    try repo.setSafetyCurrent(chatID: "c1", String(repeating: "1", count: 60))
    _ = try repo.insertMessage(message("m1"))
    try repo.setReaction(messageID: "m1", accountID: "x", emoji: "👍")
    try repo.upsertAttachment(AttachmentRecord(id: "att", messageID: "m1", kind: "photo", key: Data([1]), digest: Data([2]),
                                               size: 1, cipherSize: 2, state: "remote", downloaded: 0, localPath: nil))
    try repo.insertCall(CallRecord(id: "call", chatID: "c1", video: false, outgoing: true, outcome: "answered", startedAt: t(1), duration: 3))
    try repo.enqueue(OutboxRecord(messageID: "m1", chatID: "c1", envelope: Data(), attempts: 0, nextAttemptAt: t(1)))
    try repo.deleteChat(id: "c1")
    for table in ["messages", "attachments", "reactions", "conversations", "members", "safety", "calls", "outbox"] {
        #expect(try db.query("SELECT COUNT(*) FROM \(table)") { $0.int(0) } == [0], "\(table)")
    }
}

@Test func testInnerModePinsOnce() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    try repo.upsertConversation(conv("a1"))
    #expect(try repo.pinInnerMode(serverID: "a1", mode: "e2ee") == true)
    #expect(try repo.pinInnerMode(serverID: "a1", mode: "server_readable") == false)
    let c = try #require(try repo.conversation(serverID: "a1"))
    #expect(c.innerMode == "e2ee")
    #expect(c.modeMismatch == true)
}

@Test func testSafetyAndVerified() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    #expect(try repo.safety(chatID: "c1") == nil)
    try repo.setSafetyCurrent(chatID: "c1", "A")
    try repo.setVerified(chatID: "c1", "A")
    try repo.setSafetyCurrent(chatID: "c1", "B")
    let s = try #require(try repo.safety(chatID: "c1"))
    #expect(s.current == "B" && s.verified == "A")
    try repo.setVerified(chatID: "c1", nil)
    #expect(try repo.safety(chatID: "c1")?.verified == nil)
}

@Test func testEditDeleteReactions() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    _ = try repo.insertMessage(message("m1", folded: "hello"))
    #expect(try repo.applyEdit(id: "m1", sender: "other", envelope: Data("x".utf8), folded: "x", at: t(5)) == false)
    #expect(try repo.applyEdit(id: "m1", sender: "me", envelope: Data("x".utf8), folded: "x", at: t(5)) == true)
    #expect(try repo.message(id: "m1")?.editedAt == t(5))
    try repo.setReaction(messageID: "m1", accountID: "a", emoji: "👍")
    try repo.setReaction(messageID: "m1", accountID: "a", emoji: "❤️")
    try repo.setReaction(messageID: "m1", accountID: "b", emoji: "👍")
    #expect(try repo.reactions(messageID: "m1").count == 2)
    try repo.setReaction(messageID: "m1", accountID: "a", emoji: "")
    #expect(try repo.reactions(messageID: "m1").count == 1)
    #expect(try repo.markDeleted(id: "m1", sender: "other") == false)
    #expect(try repo.markDeleted(id: "m1", sender: "me") == true)
    #expect(try repo.message(id: "m1")?.deleted == true)
}

@Test func testOutboxRawProcessedFoldersBlocksCallsKV() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    try repo.enqueue(OutboxRecord(messageID: "m1", chatID: "c1", envelope: Data([1]), attempts: 0, nextAttemptAt: t(10)))
    #expect(try repo.dueOutbox(at: t(9)).isEmpty)
    #expect(try repo.dueOutbox(at: t(10)).count == 1)
    try repo.bumpOutbox(id: "m1", nextAttempt: t(50))
    let o = try repo.dueOutbox(at: t(60))
    #expect(o.first?.attempts == 1)
    try repo.removeOutbox(id: "m1")
    #expect(try repo.dueOutbox(at: t(60)).isEmpty)

    let raw = RawInboxRecord(cursor: 5, kind: "app", conversationID: "a1", seq: nil, epoch: 1, senderAccount: "x",
                             senderDevice: "d", payload: Data([9]), sentAt: t(1))
    try repo.insertRaw([raw, raw])
    #expect(try repo.rawItems(limit: 10).count == 1)
    #expect(try repo.rawItems(limit: 10).first?.seq == nil)
    try repo.deleteRaw(cursor: 5)
    #expect(try repo.rawItems(limit: 10).isEmpty)

    let h = Data([1, 2, 3])
    #expect(try repo.isProcessed(hash: h) == false)
    try repo.markProcessed(hash: h)
    #expect(try repo.isProcessed(hash: h))
    try repo.pruneProcessed(olderThan: t(2000))
    #expect(try repo.isProcessed(hash: h) == false)

    for (i, n) in ["a", "b", "c"].enumerated() { try repo.addFolder(FolderRecord(id: n, name: n, position: i)) }
    try repo.moveFolder(id: "c", to: 0)
    #expect(try repo.folders().map(\.id) == ["c", "a", "b"])
    try repo.renameFolder(id: "a", name: "A")
    try repo.setFolderChats(folderID: "a", chatIDs: ["c1"])
    #expect(try repo.folderChats(folderID: "a") == ["c1"])
    try repo.deleteFolder(id: "a")
    #expect(try repo.folders().map(\.id) == ["c", "b"])

    #expect(try repo.isBlocked("x") == false)
    try repo.block("x")
    #expect(try repo.isBlocked("x"))
    #expect(try repo.blockedIDs() == ["x"])
    try repo.unblock("x")
    #expect(try repo.blockedIDs().isEmpty)

    try repo.insertCall(CallRecord(id: "k1", chatID: "c1", video: true, outgoing: false, outcome: "missed", startedAt: t(1), duration: 0))
    try repo.insertCall(CallRecord(id: "k2", chatID: "c1", video: false, outgoing: true, outcome: "answered", startedAt: t(2), duration: 4.5))
    #expect(try repo.calls(limit: 1).first?.id == "k2")

    try repo.set("k", Data([7]))
    try repo.set("k", Data([8]))
    #expect(try repo.get("k") == Data([8]))
    try repo.wipe()
    #expect(try repo.get("k") == nil)
    #expect(try repo.chats().isEmpty)
}

@Test func testChatsOrderAndContacts() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat("a", activity: 1))
    try repo.upsertChat(chat("b", activity: 5))
    try repo.setUnread(chatID: "a", 3)
    try repo.setRequest(chatID: "a", true)
    #expect(try repo.chats().map(\.id) == ["b", "a"])
    #expect(try repo.chat(id: "a")?.unread == 3)
    #expect(try repo.chat(id: "a")?.isRequest == true)
    try repo.upsertContact(ContactRecord(accountID: "x", username: "u", displayName: "D", isContact: true, addedAt: t(1)))
    #expect(try repo.contact(id: "x")?.displayName == "D")
    #expect(try repo.contacts().count == 1)
}

@Test func testAttachmentState() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    _ = try repo.insertMessage(message("m1"))
    try repo.upsertAttachment(AttachmentRecord(id: "att", messageID: "m1", kind: "photo", key: Data([1]), digest: Data([2]),
                                               size: 1, cipherSize: 2, state: "remote", downloaded: 0, localPath: nil))
    try repo.setAttachmentState(id: "att", state: "local", downloaded: 2, localPath: "p")
    let a = try #require(try repo.attachment(messageID: "m1"))
    #expect(a.state == "local" && a.localPath == "p" && a.downloaded == 2)
    #expect(try repo.attachment(id: "att")?.messageID == "m1")
}
