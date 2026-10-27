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
