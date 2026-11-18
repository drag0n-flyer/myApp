import Foundation
import Testing
@testable import LocalStore

@Test func testAttachmentMessagesAreOnlyThisChatsPhotosAndFilesNewestFirstWithoutDeleted() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat("c1"))
    try repo.upsertChat(chat("c2"))
    func attach(_ id: String, chat: String, at: Double, kind: String) throws {
        _ = try repo.insertMessage(message(id, chat: chat, at: at))
        try repo.upsertAttachment(AttachmentRecord(
            id: "att-\(id)", messageID: id, kind: kind, key: Data(), digest: Data(), size: 1, cipherSize: 1, state: "remote",
            downloaded: 0, localPath: nil))
    }
    try attach("p1", chat: "c1", at: 1, kind: "photo")
    try attach("f1", chat: "c1", at: 2, kind: "file")
    try attach("v1", chat: "c1", at: 3, kind: "voice")
    try attach("other", chat: "c2", at: 4, kind: "photo")
    try attach("gone", chat: "c1", at: 5, kind: "photo")
    _ = try repo.markDeleted(id: "gone", sender: "me")
    _ = try repo.insertMessage(message("text", chat: "c1", at: 6))
    try attach("p2", chat: "c1", at: 7, kind: "photo")

    #expect(try repo.attachmentMessages(chatID: "c1", before: nil, limit: 10).map(\.id) == ["p2", "f1", "p1"])
    let first = try repo.attachmentMessages(chatID: "c1", before: nil, limit: 1)
    #expect(first.map(\.id) == ["p2"])
    let next = try repo.attachmentMessages(chatID: "c1", before: first.last, limit: 1)
    #expect(next.map(\.id) == ["f1"])
}
