import Testing
@testable import AppCore

private func poll(multi: Bool = false) -> Poll {
    Poll(question: "Lunch?", options: [PollOption(id: "a", text: "Pizza"), PollOption(id: "b", text: "Sushi"), PollOption(id: "c", text: "Soup")], multi: multi)
}

@Suite("Poll") struct PollTests {
    @Test func validity() {
        #expect(poll().isValid)
        #expect(!Poll(question: "", options: poll().options, multi: false).isValid)
        #expect(!Poll(question: String(repeating: "q", count: 301), options: poll().options, multi: false).isValid)
        #expect(!Poll(question: "q", options: [PollOption(id: "a", text: "x")], multi: false).isValid)
        #expect(!Poll(question: "q", options: [PollOption(id: "a", text: "x"), PollOption(id: "a", text: "y")], multi: false).isValid)
        #expect(!Poll(question: "q", options: [PollOption(id: "123456789", text: "x"), PollOption(id: "b", text: "y")], multi: false).isValid)
        #expect(!Poll(question: "q", options: [PollOption(id: "a", text: ""), PollOption(id: "b", text: "y")], multi: false).isValid)
        let many = (0..<13).map { PollOption(id: String($0), text: "o") }
        #expect(!Poll(question: "q", options: many, multi: false).isValid)
        #expect(Poll(question: "q", options: Array(many.prefix(12)), multi: true).isValid)
    }

    @Test func makeTrimsAndNumbers() throws {
        let made = try #require(Poll.make(question: "  Where? ", optionTexts: [" Park", "", "Pool  "], multi: true))
        #expect(made.question == "Where?")
        #expect(made.options == [PollOption(id: "1", text: "Park"), PollOption(id: "2", text: "Pool")])
        #expect(Poll.make(question: "Where?", optionTexts: ["Only", " "], multi: false) == nil)
    }

    @Test func tallyCountsVotersAndMine() {
        let votes = [PollVote(accountID: "z", optionIDs: ["a"]), PollVote(accountID: "me", optionIDs: ["a"]), PollVote(accountID: "y", optionIDs: ["b"])]
        let tally = PollTally(PollContent(poll: poll(), votes: votes), me: "me")
        #expect(tally.counts == ["a": 2, "b": 1, "c": 0])
        #expect(tally.voters["a"] == ["me", "z"])
        #expect(tally.mine == ["a"])
        #expect(tally.voterCount == 3)
        #expect(abs(tally.fraction("a") - 2.0 / 3.0) < 0.0001)
    }

    @Test func latestVoteReplacesAndEmptyRetracts() {
        let votes = [PollVote(accountID: "x", optionIDs: ["a"]), PollVote(accountID: "x", optionIDs: ["b"]),
                     PollVote(accountID: "y", optionIDs: ["a"]), PollVote(accountID: "y", optionIDs: [])]
        let tally = PollTally(PollContent(poll: poll(), votes: votes), me: "me")
        #expect(tally.counts == ["a": 0, "b": 1, "c": 0])
        #expect(tally.voterCount == 1)
        #expect(tally.fraction("c") == 0)
    }

    @Test func invalidIDsIgnoredAndSingleChoiceKeepsFirst() {
        let single = PollTally(PollContent(poll: poll(), votes: [PollVote(accountID: "x", optionIDs: ["zzz", "b", "a"])]), me: "me")
        #expect(single.counts == ["a": 0, "b": 1, "c": 0])
        let multi = PollTally(PollContent(poll: poll(multi: true), votes: [PollVote(accountID: "x", optionIDs: ["c", "zzz", "a", "c"])]), me: "x")
        #expect(multi.counts == ["a": 1, "b": 0, "c": 1])
        #expect(multi.mine == ["a", "c"])
        let none = PollTally(PollContent(poll: poll(), votes: [PollVote(accountID: "x", optionIDs: ["zzz"])]), me: "me")
        #expect(none.voterCount == 0)
    }

    @Test func tappingPicks() {
        let single = PollTally(PollContent(poll: poll(), votes: [PollVote(accountID: "me", optionIDs: ["a"])]), me: "me")
        #expect(single.picks(afterTapping: "b", in: poll()) == ["b"])
        #expect(single.picks(afterTapping: "a", in: poll()) == [])
        let multi = PollTally(PollContent(poll: poll(multi: true), votes: [PollVote(accountID: "me", optionIDs: ["c"])]), me: "me")
        #expect(multi.picks(afterTapping: "a", in: poll(multi: true)) == ["a", "c"])
        #expect(multi.picks(afterTapping: "c", in: poll(multi: true)) == [])
    }
}

@MainActor @Suite("SamplePolls") struct SamplePollTests {
    @Test func sendAndVote() throws {
        let s = SampleChatStore()
        s.sendPoll(poll(), in: "chat-riya")
        let sent = try #require(s.chat(id: "chat-riya")?.lastMessage)
        guard case .poll = sent.content else { Issue.record("not a poll"); return }
        s.votePoll(messageID: sent.id, optionIDs: ["b", "a"], in: "chat-riya")
        guard case .poll(let content) = s.chat(id: "chat-riya")?.lastMessage?.content else { Issue.record("not a poll"); return }
        #expect(PollTally(content, me: s.me.id).mine == ["b"])
        s.votePoll(messageID: sent.id, optionIDs: [], in: "chat-riya")
        guard case .poll(let after) = s.chat(id: "chat-riya")?.lastMessage?.content else { return }
        #expect(PollTally(after, me: s.me.id).voterCount == 0)
        s.sendPoll(Poll(question: "", options: [], multi: false), in: "chat-riya")
        #expect(s.chat(id: "chat-riya")?.lastMessage?.id == sent.id)
    }
}
