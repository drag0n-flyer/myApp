import Foundation
import Testing
@testable import LocalStore

/// A database exactly as the previous app version left it: schema version 4, populated.
private func populatedV4() throws -> Database {
    let db = try Database.inMemory()
    for s in schemaV1 + schemaV2 + schemaV3 + schemaV4 { try db.execute(s) }
    try db.execute("PRAGMA user_version = 4")
    try db.execute("INSERT INTO chats(id, is_request, unread, muted, last_activity, pinned_at, archived, timer_seconds) VALUES ('bob',0,3,1,500,9,0,86400)")
    try db.execute("INSERT INTO chats(id, is_request, unread, muted, last_activity) VALUES ('eve',1,0,0,100)")
    try db.execute("""
        INSERT INTO messages(id, chat_id, sender_account, sent_at, received_at, envelope, status, folded)
        VALUES ('m1','bob','bob',400,400,x'7b7d','delivered','hi there')
        """)
    try db.execute("INSERT INTO conversations(server_id, chat_id, group_id, mode, epoch, active, created_at) VALUES ('s1','bob',x'01','e2ee',1,1,1)")
    return db
}

@Test func testMigrationToV5MakesOldChatsDirectAndKeepsEveryRow() throws {
    let db = try populatedV4()
    try migrate(db)
    #expect(latestSchemaVersion == 5)
    #expect(try db.query("PRAGMA user_version") { $0.int(0) } == [5])
    #expect(try db.query("SELECT id, kind, conversation_id IS NULL, title IS NULL, photo IS NULL, group_ver IS NULL, members_version IS NULL, my_state FROM chats ORDER BY id") {
        [$0.text(0), $0.text(1), String($0.int(2)), String($0.int(3)), String($0.int(4)), String($0.int(5)), String($0.int(6)), $0.text(7)]
    } == [["bob", "direct", "1", "1", "1", "1", "1", "member"], ["eve", "direct", "1", "1", "1", "1", "1", "member"]])
    #expect(try db.query("SELECT unread, muted, pinned_at, timer_seconds FROM chats WHERE id='bob'") { [$0.int(0), $0.int(1), $0.int(2), $0.int(3)] } == [[3, 1, 9, 86400]])
    #expect(try db.query("SELECT id, sender_account, status, folded FROM messages") { [$0.text(0), $0.text(1), $0.text(2), $0.text(3)] } == [["m1", "bob", "delivered", "hi there"]])
    #expect(try db.query("SELECT COUNT(*) FROM conversations") { $0.int(0) } == [1])
    #expect(try db.query("SELECT COUNT(*) FROM group_members") { $0.int(0) } == [0])
    #expect(try db.query("SELECT COUNT(*) FROM receipts") { $0.int(0) } == [0])
    #expect(try db.query("PRAGMA foreign_key_check") { $0.int(0) }.isEmpty)
    #expect(try db.query("PRAGMA integrity_check") { $0.text(0) } == ["ok"])
    try migrate(db)   // a second open changes nothing
    #expect(try db.query("PRAGMA user_version") { $0.int(0) } == [5])
    let repo = Repository(db: db, now: { t(1000) })
    let bob = try #require(try repo.chat(id: "bob"))
    #expect(bob.kind == "direct" && bob.conversationID == nil && bob.myState == "member" && bob.unread == 3 && bob.timerSeconds == 86400)
    #expect(try repo.search("there", limit: 5).map(\.messageID) == ["m1"])
}

@Test func testTheDatabaseRefusesAChatKindOrStateItDoesNotKnow() throws {
    let db = try populatedV4()
    try migrate(db)
    #expect(throws: DatabaseError.self) { try db.execute("UPDATE chats SET kind='channel' WHERE id='bob'") }
    #expect(throws: DatabaseError.self) { try db.execute("UPDATE chats SET my_state='banned' WHERE id='bob'") }
}

private let conversation = "c-1"
private let chatID = "g:c-1"

private func groupRepo() throws -> Repository {
    let (_, repo) = try makeRepo()
    #expect(try repo.upsertGroup(conversationID: conversation, title: nil, photo: nil, at: t(5)) == chatID)
    return repo
}

private func member(_ id: String, _ role: String = "member") -> GroupMemberRecord { GroupMemberRecord(accountID: id, role: role) }

@Test func testUpsertGroupMakesAGroupChatOnceAndNeverTouchesAnExistingRow() throws {
    let (_, repo) = try makeRepo()
    #expect(try repo.upsertGroup(conversationID: "c-1", title: "Club", photo: nil, at: t(5)) == "g:c-1")
    var row = try #require(try repo.chat(id: "g:c-1"))
    #expect(row.kind == "group" && row.conversationID == "c-1" && row.title == "Club" && row.myState == "member")
    #expect(!row.isRequest && row.unread == 0 && row.lastActivity == t(5))
    #expect(row.groupVer == nil && row.membersVersion == nil && row.photo == nil)
    // A second call (a repeated welcome) changes nothing.
    try repo.setUnread(chatID: "g:c-1", 4)
    #expect(try repo.upsertGroup(conversationID: "c-1", title: "Other", photo: nil, at: t(9)) == "g:c-1")
    row = try #require(try repo.chat(id: "g:c-1"))
    #expect(row.title == "Club" && row.unread == 4 && row.lastActivity == t(5))
    // Unusable conversation IDs make no chat.
    #expect(try repo.upsertGroup(conversationID: "", title: nil, photo: nil, at: t(1)) == nil)
    #expect(try repo.upsertGroup(conversationID: String(repeating: "x", count: 200), title: nil, photo: nil, at: t(1)) == nil)
    #expect(try repo.chats().map(\.id) == ["g:c-1"])
}

@Test func testAGroupChatNeverReplacesAOneToOneChatWithTheSameID() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat("g:c-1"))
    _ = try repo.upsertGroup(conversationID: "c-1", title: "Club", photo: nil, at: t(5))
    let row = try #require(try repo.chat(id: "g:c-1"))
    #expect(row.kind == "direct" && row.title == nil)
    #expect(try repo.setGroupMembers(chatID: "g:c-1", [member("a")], version: 1) == nil)
}

@Test func testSetGroupMembersReturnsTheOldListAndStoresTheNewOne() throws {
    let repo = try groupRepo()
    #expect(try repo.groupMembers(chatID: chatID).isEmpty)
    let first = try repo.setGroupMembers(chatID: chatID, [member("b", "admin"), member("a", "owner")], version: 0)
    #expect(first == [])
    #expect(try repo.groupMembers(chatID: chatID) == [member("a", "owner"), member("b", "admin")])
    #expect(try repo.chat(id: chatID)?.membersVersion == 0)
    let second = try repo.setGroupMembers(chatID: chatID, [member("a", "owner"), member("c")], version: 3)
    #expect(second == [member("a", "owner"), member("b", "admin")])
    #expect(try repo.groupMembers(chatID: chatID) == [member("a", "owner"), member("c")])
    #expect(try repo.chat(id: chatID)?.membersVersion == 3)
}

@Test func testAStaleOrEqualMembersVersionIsIgnoredWhole() throws {
    let repo = try groupRepo()
    _ = try repo.setGroupMembers(chatID: chatID, [member("a", "owner"), member("b")], version: 5)
    #expect(try repo.setGroupMembers(chatID: chatID, [member("a", "owner")], version: 5) == nil)
    #expect(try repo.setGroupMembers(chatID: chatID, [member("a", "owner")], version: 4) == nil)
    #expect(try repo.setGroupMembers(chatID: chatID, [member("a", "owner")], version: -1) == nil)
    #expect(try repo.groupMembers(chatID: chatID) == [member("a", "owner"), member("b")])
    #expect(try repo.chat(id: chatID)?.membersVersion == 5)
}

@Test func testMembersFromTheServerAreCheckedBeforeTheyAreStored() throws {
    let repo = try groupRepo()
    // An unknown role is a plain member; an empty ID and a repeated account are dropped.
    _ = try repo.setGroupMembers(chatID: chatID, [member("a", "owner"), member("b", "superuser"), member(""), member("b", "admin")], version: 1)
    #expect(try repo.groupMembers(chatID: chatID) == [member("a", "owner"), member("b")])
    // More than 100 accounts is not a valid list: nothing changes.
    let many = (0..<101).map { member("u\($0)") }
    #expect(try repo.setGroupMembers(chatID: chatID, many, version: 2) == nil)
    #expect(try repo.groupMembers(chatID: chatID).count == 2)
    #expect(try repo.chat(id: chatID)?.membersVersion == 1)
    let hundred = (0..<100).map { member("u\($0)") }
    #expect(try repo.setGroupMembers(chatID: chatID, hundred, version: 2) != nil)
    #expect(try repo.groupMembers(chatID: chatID).count == 100)
}

@Test func testSetGroupMembersForAChatThatIsNotThereDoesNothing() throws {
    let (_, repo) = try makeRepo()
    #expect(try repo.setGroupMembers(chatID: "g:none", [member("a")], version: 1) == nil)
    #expect(try repo.groupMembers(chatID: "g:none").isEmpty)
}

@Test func testSetGroupInfoKeepsOnlyAHigherVersion() throws {
    let repo = try groupRepo()
    let photo = Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00]) // passes the profile photo rule
    #expect(try repo.setGroupInfo(chatID: chatID, title: "Club", photo: photo, ver: 1))
    var row = try #require(try repo.chat(id: chatID))
    #expect(row.title == "Club" && row.photo == photo && row.groupVer == 1)
    #expect(try !repo.setGroupInfo(chatID: chatID, title: "Same", photo: nil, ver: 1))
    #expect(try !repo.setGroupInfo(chatID: chatID, title: "Old", photo: nil, ver: 0))
    #expect(try !repo.setGroupInfo(chatID: chatID, title: "Neg", photo: nil, ver: -2))
    row = try #require(try repo.chat(id: chatID))
    #expect(row.title == "Club" && row.photo == photo && row.groupVer == 1)
    // A higher version without a photo clears the photo.
    #expect(try repo.setGroupInfo(chatID: chatID, title: "Club 2", photo: nil, ver: 7))
    row = try #require(try repo.chat(id: chatID))
    #expect(row.title == "Club 2" && row.photo == nil && row.groupVer == 7)
}

@Test func testSetGroupInfoRefusesAnOversizedPhotoAndADirectChat() throws {
    let repo = try groupRepo()
    #expect(try !repo.setGroupInfo(chatID: chatID, title: "Club", photo: Data(repeating: 0xFF, count: 24_577), ver: 1))
    // A photo under 4 bytes, or one without a valid frame header, is refused whole too.
    #expect(try !repo.setGroupInfo(chatID: chatID, title: "Club", photo: Data([0xFF, 0xD8, 1]), ver: 1))
    #expect(try repo.chat(id: chatID)?.groupVer == nil)
    #expect(try repo.setGroupInfo(chatID: chatID, title: "Club", photo: Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00]) + Data(count: 24_576 - 15), ver: 1))
    try repo.upsertChat(chat("bob"))
    #expect(try !repo.setGroupInfo(chatID: "bob", title: "Club", photo: nil, ver: 1))
    #expect(try repo.chat(id: "bob")?.title == nil)
}

@Test func testMyStateGoesToRemovedOrLeftAndBackOnlyThroughTheSetter() throws {
    let repo = try groupRepo()
    #expect(try repo.chat(id: chatID)?.myState == "member")
    #expect(try repo.setMyState(chatID: chatID, "removed"))
    #expect(try repo.chat(id: chatID)?.myState == "removed")
    #expect(try repo.setMyState(chatID: chatID, "left"))
    #expect(try repo.chat(id: chatID)?.myState == "left")
    #expect(try !repo.setMyState(chatID: chatID, "banned"))
    #expect(try repo.chat(id: chatID)?.myState == "left")
    try repo.upsertChat(chat("bob"))
    #expect(try !repo.setMyState(chatID: "bob", "removed"))
    // An ordinary chat write never resets it.
    try repo.upsertChat(ChatRecord(id: chatID, isRequest: false, unread: 2, muted: false, lastActivity: t(9)))
    #expect(try repo.chat(id: chatID)?.myState == "left")
}

private func groupMessage(_ id: String, from sender: String = "me") -> MessageRecord {
    var m = message(id, chat: chatID)
    m.senderAccount = sender
    return m
}

@Test func testReceiptsAreUniquePerMemberAndKindAndKeepTheFirstTime() throws {
    let repo = try groupRepo()
    _ = try repo.insertMessage(groupMessage("m1"))
    #expect(try repo.addReceipt(messageID: "m1", accountID: "a", kind: "delivered", at: t(10)))
    #expect(try !repo.addReceipt(messageID: "m1", accountID: "a", kind: "delivered", at: t(99)))
    #expect(try repo.addReceipt(messageID: "m1", accountID: "a", kind: "read", at: t(20)))
    #expect(try repo.addReceipt(messageID: "m1", accountID: "b", kind: "delivered", at: t(15)))
    #expect(try repo.receipts(messageID: "m1") == [
        ReceiptRecord(messageID: "m1", accountID: "a", kind: "delivered", at: t(10)),
        ReceiptRecord(messageID: "m1", accountID: "a", kind: "read", at: t(20)),
        ReceiptRecord(messageID: "m1", accountID: "b", kind: "delivered", at: t(15)),
    ])
    #expect(try repo.receipts(messageID: "nothing").isEmpty)
}

@Test func testAReceiptForAnUnknownKindOrMessageIsRefused() throws {
    let repo = try groupRepo()
    _ = try repo.insertMessage(groupMessage("m1"))
    #expect(try !repo.addReceipt(messageID: "m1", accountID: "a", kind: "seen", at: t(1)))
    #expect(try !repo.addReceipt(messageID: "nope", accountID: "a", kind: "read", at: t(1)))
    #expect(try repo.receipts(messageID: "m1").isEmpty)
}

@Test func testDeletingAGroupChatRemovesItsMembersAndReceipts() throws {
    let (db, repo) = try makeRepo()
    _ = try repo.upsertGroup(conversationID: conversation, title: "Club", photo: nil, at: t(5))
    _ = try repo.setGroupMembers(chatID: chatID, [member("a", "owner"), member("b")], version: 1)
    _ = try repo.insertMessage(groupMessage("m1"))
    _ = try repo.addReceipt(messageID: "m1", accountID: "a", kind: "read", at: t(2))
    try repo.deleteChat(id: chatID)
    #expect(try db.query("SELECT COUNT(*) FROM group_members") { $0.int(0) } == [0])
    #expect(try db.query("SELECT COUNT(*) FROM receipts") { $0.int(0) } == [0])
    #expect(try repo.chat(id: chatID) == nil)
}

@Test func testWipeLeavesNoGroupRows() throws {
    let (db, repo) = try makeRepo()
    _ = try repo.upsertGroup(conversationID: conversation, title: "Club", photo: nil, at: t(5))
    _ = try repo.setGroupMembers(chatID: chatID, [member("a", "owner")], version: 1)
    _ = try repo.insertMessage(groupMessage("m1"))
    _ = try repo.addReceipt(messageID: "m1", accountID: "a", kind: "read", at: t(2))
    try repo.wipe()
    for table in ["chats", "group_members", "receipts", "messages"] {
        #expect(try db.query("SELECT COUNT(*) FROM \(table)") { $0.int(0) } == [0], "\(table)")
    }
}

@Test func testGroupConversationIDIsUniquePerChat() throws {
    let (db, repo) = try makeRepo()
    _ = try repo.upsertGroup(conversationID: conversation, title: nil, photo: nil, at: t(5))
    #expect(throws: DatabaseError.self) {
        try db.execute("INSERT INTO chats(id, is_request, unread, muted, last_activity, kind, conversation_id) VALUES ('g:other',0,0,0,1,'group','c-1')")
    }
}

@Test func testUpsertGroupRefusesATitleTheGroupNameRuleWouldChangeAndOddConversationIDs() throws {
    let (_, repo) = try makeRepo()
    for title in ["", "  Club", "Club ", "Bo\u{202E}ok", "Book\nclub", String(repeating: "a", count: 51)] {
        #expect(try repo.upsertGroup(conversationID: "c-1", title: title, photo: nil, at: t(1)) == nil, "\(title.debugDescription)")
    }
    for id in ["c 1", "c/1", "c:1", "c.1", "\u{00E9}", "c\n1"] {
        #expect(try repo.upsertGroup(conversationID: id, title: nil, photo: nil, at: t(1)) == nil, "\(id.debugDescription)")
    }
    #expect(try repo.chats().isEmpty)
    #expect(try repo.upsertGroup(conversationID: "Ab_9-z", title: String(repeating: "a", count: 50), photo: nil, at: t(1)) == "g:Ab_9-z")
    #expect(try repo.upsertGroup(conversationID: "c-2", title: nil, photo: nil, at: t(1)) == "g:c-2")
}

@Test func testGroupMemberAccountsListsEveryAccountOfEveryGroupOnce() throws {
    let repo = try groupRepo()
    #expect(try repo.groupMemberAccounts().isEmpty)
    _ = try repo.setGroupMembers(chatID: chatID, [member("b", "admin"), member("a", "owner")], version: 0)
    _ = try repo.upsertGroup(conversationID: "c-2", title: "Other", photo: nil, at: t(5))
    _ = try repo.setGroupMembers(chatID: "g:c-2", [member("b"), member("c")], version: 0)
    #expect(try repo.groupMemberAccounts() == ["a", "b", "c"])
}

@Test func testReceiptsOfManyMessagesComeBackGroupedInChunks() throws {
    let repo = try groupRepo()
    let ids = (0..<1200).map { "m\($0)" }
    for id in ids { _ = try repo.insertMessage(groupMessage(id)) }
    _ = try repo.addReceipt(messageID: "m0", accountID: "a", kind: "read", at: t(1))
    _ = try repo.addReceipt(messageID: "m0", accountID: "b", kind: "delivered", at: t(2))
    _ = try repo.addReceipt(messageID: "m1199", accountID: "a", kind: "delivered", at: t(3))
    let found = try repo.receipts(messageIDs: ids + ["nothing"])
    #expect(found["m0"]?.map(\.accountID) == ["a", "b"])
    #expect(found["m1199"]?.count == 1)
    #expect(found["m5"] == nil)
    #expect(try repo.receipts(messageIDs: []).isEmpty)
}

@Test func testDeleteConversationsOfAGroupKeepsTheChatItsMessagesAndMembers() throws {
    let repo = try groupRepo()
    try repo.upsertConversation(ConversationRecord(serverID: "c1", chatID: chatID, groupID: Data([9]), mode: "e2ee", innerMode: nil,
                                                   modeMismatch: false, epoch: 1, active: true, createdAt: Date(timeIntervalSince1970: 1)))
    try repo.upsertChat(chat("bob"))
    try repo.upsertConversation(ConversationRecord(serverID: "c2", chatID: "bob", groupID: Data([8]), mode: "e2ee", innerMode: nil,
                                                   modeMismatch: false, epoch: 1, active: true, createdAt: Date(timeIntervalSince1970: 1)))
    #expect(try repo.deleteConversations(groupChatID: "bob").isEmpty)   // a one-to-one chat is never touched
    #expect(try repo.conversation(serverID: "c2") != nil)
    #expect(try repo.deleteConversations(groupChatID: chatID) == ["c1"])
    #expect(try repo.conversation(serverID: "c1") == nil && repo.chat(id: chatID) != nil)
}

@Test func testFailQueuedSendsMarksTheMessagesFailedAndClearsTheQueue() throws {
    let repo = try groupRepo()
    let message = MessageRecord(id: "m1", chatID: chatID, conversationID: nil, senderAccount: "me", senderDevice: nil,
                                sentAt: Date(timeIntervalSince1970: 1), receivedAt: Date(timeIntervalSince1970: 1), envelope: Data("{}".utf8),
                                status: "sending", replyTo: nil, editedAt: nil, deleted: false, undecryptable: false, folded: "")
    try repo.enqueueSend(message: message, outbox: OutboxRecord(messageID: "m1", chatID: chatID, envelope: Data("{}".utf8), attempts: 0,
                                                                nextAttemptAt: Date(timeIntervalSince1970: 1)))
    try repo.failQueuedSends(chatID: chatID)
    #expect(try repo.message(id: "m1")?.status == "failed")
    #expect(try repo.dueOutbox(at: Date(timeIntervalSince1970: 10)).isEmpty)
}

@Test func testDeleteGroupChatRemovesRowsOnlyAfterIAmOutAndReturnsTheMessageIDs() throws {
    let repo = try groupRepo()
    let message = MessageRecord(id: "m1", chatID: chatID, conversationID: nil, senderAccount: "me", senderDevice: nil,
                                sentAt: Date(timeIntervalSince1970: 1), receivedAt: Date(timeIntervalSince1970: 1), envelope: Data("{}".utf8),
                                status: "sending", replyTo: nil, editedAt: nil, deleted: false, undecryptable: false, folded: "")
    try repo.enqueueSend(message: message, outbox: OutboxRecord(messageID: "m1", chatID: chatID, envelope: Data("{}".utf8), attempts: 0,
                                                                nextAttemptAt: Date(timeIntervalSince1970: 1)))
    _ = try repo.setGroupMembers(chatID: chatID, [member("me", "owner"), member("bob")], version: 1)
    // Still a member: nothing is deleted.
    #expect(try repo.deleteGroupChat(id: chatID) == nil)
    #expect(try repo.chat(id: chatID) != nil)
    #expect(try repo.setMyState(chatID: chatID, "removed"))
    let gone = try #require(try repo.deleteGroupChat(id: chatID))
    #expect(gone.messageIDs == ["m1"])
    #expect(try repo.chat(id: chatID) == nil)
    #expect(try repo.message(id: "m1") == nil)
    #expect(try repo.groupMembers(chatID: chatID).isEmpty)
    #expect(try repo.dueOutbox(at: Date(timeIntervalSince1970: 10)).isEmpty)
    // A one-to-one chat is never touched by it.
    try repo.upsertChat(chat("bob"))
    #expect(try repo.deleteGroupChat(id: "bob") == nil)
    #expect(try repo.chat(id: "bob") != nil)
}
