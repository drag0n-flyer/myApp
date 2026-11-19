import Foundation
import Testing
@testable import LocalStore

@Test func testPollVoteKeptBeforeThePollAndLatestWins() throws {
    let (_, repo) = try makeRepo()
    // No poll message exists yet: the vote is kept.
    try repo.setPollVote(chatID: "c1", messageID: "p1", accountID: "bob", optionIDs: ["a"], at: t(10))
    try repo.setPollVote(chatID: "c1", messageID: "p1", accountID: "bob", optionIDs: ["b"], at: t(20))
    try repo.setPollVote(chatID: "c1", messageID: "p1", accountID: "bob", optionIDs: ["c"], at: t(15))   // older: ignored
    try repo.setPollVote(chatID: "c1", messageID: "p1", accountID: "eve", optionIDs: [], at: t(5))
    let votes = try repo.pollVotes(chatID: "c1", messageID: "p1")
    #expect(votes.map(\.accountID) == ["bob", "eve"])
    #expect(votes[0].optionIDs == ["b"] && votes[1].optionIDs == [])
    #expect(try repo.pollVotes(chatID: "c2", messageID: "p1").isEmpty)
}
