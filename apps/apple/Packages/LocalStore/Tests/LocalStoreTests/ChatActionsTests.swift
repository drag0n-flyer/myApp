import Foundation
import Testing
@testable import LocalStore

private func seeded(_ ids: String...) throws -> Repository {
    let (_, repo) = try makeRepo()
    for id in ids { try repo.upsertChat(chat(id, activity: 1)) }
    return repo
}

@Test func testPinMuteArchiveAreSingleColumnWrites() throws {
    let repo = try seeded("a")
    try repo.setMuted(chatID: "a", true)
    try repo.setPinnedAt(chatID: "a", t(500))
    var c = try #require(try repo.chat(id: "a"))
    #expect(c.muted && c.pinnedAt == t(500) && !c.archived && c.unread == 0)
    try repo.setPinnedAt(chatID: "a", nil)
    try repo.setMuted(chatID: "a", false)
    c = try #require(try repo.chat(id: "a"))
    #expect(!c.muted && c.pinnedAt == nil)
}

@Test func testArchiveClearsThePinInTheSameStatementAndUnarchiveDoesNotRestoreIt() throws {
    let repo = try seeded("a")
    try repo.setPinnedAt(chatID: "a", t(500))
    try repo.setArchived(chatID: "a", true)
    var c = try #require(try repo.chat(id: "a"))
    #expect(c.archived && c.pinnedAt == nil)
    try repo.setArchived(chatID: "a", false)
    c = try #require(try repo.chat(id: "a"))
    #expect(!c.archived && c.pinnedAt == nil)
}

@Test func testPinRefusesTheSixthAndAnArchivedChat() throws {
    let repo = try seeded("a", "b", "c", "d", "e", "f", "z")
    for (i, id) in ["a", "b", "c", "d", "e"].enumerated() { #expect(try repo.pin(chatID: id, at: t(Double(10 + i)), limit: 5)) }
    #expect(try repo.pin(chatID: "f", at: t(99), limit: 5) == false)
    #expect(try repo.chat(id: "f")?.pinnedAt == nil)
    // Pinning a chat that is already pinned keeps its time and is not counted twice.
    #expect(try repo.pin(chatID: "a", at: t(200), limit: 5))
    #expect(try repo.chat(id: "a")?.pinnedAt == t(10))
    // Unpinning makes room.
    try repo.setPinnedAt(chatID: "b", nil)
    #expect(try repo.pin(chatID: "f", at: t(99), limit: 5))
    // An archived chat is never pinned.
    try repo.setArchived(chatID: "z", true)
    #expect(try repo.pin(chatID: "z", at: t(300), limit: 10) == false)
    #expect(try repo.chat(id: "z")?.pinnedAt == nil)
}

@Test func testBumpActivityOnlyRaisesLastActivity() throws {
    let repo = try seeded("a")
    try repo.setMuted(chatID: "a", true)
    try repo.setArchived(chatID: "a", true)
    try repo.setPinnedAt(chatID: "a", t(500))
    try repo.bumpActivity(chatID: "a", at: t(900))
    var c = try #require(try repo.chat(id: "a"))
    #expect(c.lastActivity == t(900) && c.muted && c.archived && c.pinnedAt == t(500))
    try repo.bumpActivity(chatID: "a", at: t(100))
    #expect(try repo.chat(id: "a")?.lastActivity == t(900))
    try repo.bumpActivity(chatID: "nobody", at: t(5))
    c = try #require(try repo.chat(id: "a"))
    #expect(c.unread == 0)
}

@Test func testBumpChatAddsUnreadAndRaisesActivityLeavingPinMuteArchive() throws {
    let repo = try seeded("a")
    try repo.setMuted(chatID: "a", true)
    try repo.setArchived(chatID: "a", true)
    try repo.bumpChat(chatID: "a", unreadDelta: 1, at: t(50))
    try repo.bumpChat(chatID: "a", unreadDelta: 0, at: t(10))
    let c = try #require(try repo.chat(id: "a"))
    #expect(c.unread == 1 && c.lastActivity == t(50) && c.muted && c.archived)
}

@Test func testReduceUnreadNeverGoesBelowZeroAndTouchesNothingElse() throws {
    let repo = try seeded("a")
    try repo.setMuted(chatID: "a", true)
    try repo.setUnread(chatID: "a", 3)
    try repo.reduceUnread(chatID: "a", by: 2)
    #expect(try repo.chat(id: "a")?.unread == 1)
    try repo.reduceUnread(chatID: "a", by: 5)
    let c = try #require(try repo.chat(id: "a"))
    #expect(c.unread == 0 && c.muted)
}

@Test func testWholeRowUpsertKeepsPinMuteArchive() throws {
    let repo = try seeded("a")
    try repo.setMuted(chatID: "a", true)
    try repo.setArchived(chatID: "a", true)
    try repo.setPinnedAt(chatID: "a", t(500))
    // A whole-row write built from an older read must not undo them.
    try repo.upsertChat(ChatRecord(id: "a", isRequest: false, unread: 2, muted: false, lastActivity: t(7)))
    let c = try #require(try repo.chat(id: "a"))
    #expect(c.muted && c.archived && c.pinnedAt == t(500) && c.unread == 2)
}

@Test func testDraftsBlankDeletesOneRowPerChat() throws {
    let repo = try seeded("a", "b")
    #expect(try repo.setDraft(chatID: "a", "one", at: t(10)))
    #expect(try repo.setDraft(chatID: "b", "two", at: t(11)))
    #expect(try repo.setDraft(chatID: "a", "one, edited", at: t(12)))
    #expect(try repo.drafts() == ["a": "one, edited", "b": "two"])
    #expect(try repo.setDraft(chatID: "a", "  \n ", at: t(13)))
    #expect(try repo.drafts() == ["b": "two"])
}

@Test func testDraftForAChatWithNoRowIsNotWritten() throws {
    let repo = try seeded()
    #expect(try repo.setDraft(chatID: "nobody", "lost", at: t(1)) == false)
    #expect(try repo.drafts().isEmpty)
    try repo.upsertChat(chat("nobody"))
    #expect(try repo.setDraft(chatID: "nobody", "kept", at: t(2)))
    #expect(try repo.drafts() == ["nobody": "kept"])
}

@Test func testDraftIsDeletedWithTheChatAndBySignOut() throws {
    let repo = try seeded("a", "b")
    _ = try repo.setDraft(chatID: "a", "gone with the chat", at: t(1))
    _ = try repo.setDraft(chatID: "b", "gone with the account", at: t(2))
    try repo.deleteChat(id: "a")
    #expect(try repo.drafts() == ["b": "gone with the account"])
    try repo.wipe()
    #expect(try repo.drafts().isEmpty)
}
