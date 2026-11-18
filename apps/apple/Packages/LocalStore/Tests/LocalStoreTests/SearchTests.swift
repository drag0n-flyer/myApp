import Foundation
import Testing
@testable import LocalStore

private func store(_ texts: [String]) throws -> Repository {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat())
    for (i, s) in texts.enumerated() {
        _ = try repo.insertMessage(message("m\(i)", at: Double(i), folded: fold(s)))
    }
    return repo
}

@Test func testSearchFoldsDiacritics() throws {
    let repo = try store(["Crème brûlée", "ＡＢＣ", "नमस्ते दोस्त", "東京タワー", "שלום עולם", "çé"])
    #expect(try repo.search("creme", limit: 10).map(\.messageID) == ["m0"])
    #expect(try repo.search("abc", limit: 10).map(\.messageID) == ["m1"])
    #expect(try repo.search("नमस्ते", limit: 10).map(\.messageID) == ["m2"])
    #expect(try repo.search("京タ", limit: 10).map(\.messageID) == ["m3"])
    #expect(try repo.search("עולם", limit: 10).map(\.messageID) == ["m4"])
    #expect(try repo.search("ce", limit: 10).map(\.messageID) == ["m5"])
    #expect(try repo.search("CRÈME", limit: 10).first?.chatID == "c1")
    #expect(try repo.search("zzzz", limit: 10).isEmpty)
    #expect(try repo.search("", limit: 10).isEmpty)
}

@Test func testSearchHandlesQuotesAndWildcards() throws {
    let repo = try store(["say \"hello\" 100%"])
    #expect(try repo.search("\"hello\"", limit: 10).count == 1)
    #expect(try repo.search("0%", limit: 10).count == 1)
    #expect(try repo.search("%%", limit: 10).isEmpty)
}

@Test func testSearchSkipsDeleted() throws {
    let repo = try store(["Crème brûlée"])
    #expect(try repo.search("creme", limit: 10).count == 1)
    _ = try repo.markDeleted(id: "m0", sender: "me")
    #expect(try repo.search("creme", limit: 10).isEmpty)
}

@Test func testSearchFollowsEdits() throws {
    let repo = try store(["alpha beta"])
    _ = try repo.applyEdit(id: "m0", sender: "me", envelope: Data("e".utf8), folded: fold("gamma delta"), at: t(9))
    #expect(try repo.search("alpha", limit: 10).isEmpty)
    #expect(try repo.search("gamma", limit: 10).count == 1)
}

@Test func testSearchInOneChatIgnoresNewerHitsElsewhere() throws {
    let (_, repo) = try makeRepo()
    try repo.upsertChat(chat("c1"))
    try repo.upsertChat(chat("c2"))
    _ = try repo.insertMessage(message("old", chat: "c2", at: 1, folded: fold("hello from c2")))
    for i in 0..<5 { _ = try repo.insertMessage(message("n\(i)", chat: "c1", at: Double(10 + i), folded: fold("hello from c1"))) }
    // The global newest 3 hits are all in c1.
    #expect(try repo.search("hello", limit: 3).allSatisfy { $0.chatID == "c1" })
    #expect(try repo.search("hello", chatID: "c2", limit: 3).map(\.messageID) == ["old"])
    #expect(try repo.search("he", chatID: "c2", limit: 3).map(\.messageID) == ["old"])
    #expect(try repo.search("hello", chatID: "c1", limit: 10).count == 5)
    #expect(try repo.search("hello", chatID: "missing", limit: 10).isEmpty)
}
