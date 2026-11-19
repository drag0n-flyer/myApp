import Foundation

/// One answer of a poll. `id` is 1...8 characters and unique inside the poll; `text` is 1...100.
public struct PollOption: Hashable, Codable, Sendable, Identifiable {
    public var id: String
    public var text: String

    public init(id: String, text: String) {
        self.id = id
        self.text = text
    }
}

/// A poll as sent: a question, 2...12 options and whether more than one may be picked.
public struct Poll: Hashable, Codable, Sendable {
    public static let maxQuestion = 300
    public static let maxOptionText = 100
    public static let maxOptionID = 8
    public static let optionCount = 2...12

    public var question: String
    public var options: [PollOption]
    public var multi: Bool

    public init(question: String, options: [PollOption], multi: Bool) {
        self.question = question
        self.options = options
        self.multi = multi
    }

    /// The wire rules: question 1...300, 2...12 options with unique ids of 1...8 characters and texts of 1...100.
    public var isValid: Bool {
        guard (1...Self.maxQuestion).contains(question.count), Self.optionCount.contains(options.count) else { return false }
        guard Set(options.map(\.id)).count == options.count else { return false }
        return options.allSatisfy { (1...Self.maxOptionID).contains($0.id.count) && (1...Self.maxOptionText).contains($0.text.count) }
    }

    /// A poll from what the person typed: trimmed, blank options dropped, ids `1`, `2`... Nil when the result is not valid.
    public static func make(question: String, optionTexts: [String], multi: Bool) -> Poll? {
        let question = question.trimmingCharacters(in: .whitespacesAndNewlines)
        let texts = optionTexts.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        let poll = Poll(question: question, options: texts.enumerated().map { PollOption(id: String($0.offset + 1), text: $0.element) }, multi: multi)
        return poll.isValid ? poll : nil
    }
}

/// One person's latest answer. An empty `optionIDs` means no answer.
public struct PollVote: Hashable, Codable, Sendable {
    public var accountID: String
    public var optionIDs: [String]

    public init(accountID: String, optionIDs: [String]) {
        self.accountID = accountID
        self.optionIDs = optionIDs
    }
}

/// A poll with the votes known for it, as a message shows it.
public struct PollContent: Hashable, Codable, Sendable {
    public var poll: Poll
    public var votes: [PollVote]

    public init(poll: Poll, votes: [PollVote] = []) {
        self.poll = poll
        self.votes = votes
    }
}

/// Counts and voters per option, and what I picked.
public struct PollTally: Equatable, Sendable {
    /// Votes per option ID (every option of the poll has an entry).
    public var counts: [String: Int]
    /// Account IDs per option ID, sorted, with `me` first when I voted for it.
    public var voters: [String: [String]]
    /// My picked option IDs, in poll order.
    public var mine: [String]
    /// How many people voted at all.
    public var voterCount: Int

    public init(_ content: PollContent, me: String) {
        let poll = content.poll
        let valid = Set(poll.options.map(\.id))
        var counts = Dictionary(uniqueKeysWithValues: poll.options.map { ($0.id, 0) })
        var voters: [String: [String]] = [:]
        var mine: Set<String> = []
        var voterCount = 0
        // One entry per account: the last one in the list.
        var latest: [String: [String]] = [:]
        for vote in content.votes { latest[vote.accountID] = vote.optionIDs }
        for (account, ids) in latest.sorted(by: { $0.key < $1.key }) {
            let picked = Self.sanitized(ids, in: poll, valid: valid)
            guard !picked.isEmpty else { continue }
            voterCount += 1
            for id in picked {
                counts[id, default: 0] += 1
                voters[id, default: []].append(account)
                if account == me { mine.insert(id) }
            }
        }
        for id in Array(voters.keys) {
            let list = voters[id] ?? []
            if list.contains(me) { voters[id] = [me] + list.filter { $0 != me } }
        }
        self.counts = counts
        self.voters = voters
        self.mine = poll.options.map(\.id).filter(mine.contains)
        self.voterCount = voterCount
    }

    /// Unknown and repeated option IDs dropped; a single-choice poll keeps only the first valid one.
    public static func sanitized(_ ids: [String], in poll: Poll) -> [String] {
        sanitized(ids, in: poll, valid: Set(poll.options.map(\.id)))
    }

    private static func sanitized(_ ids: [String], in poll: Poll, valid: Set<String>) -> [String] {
        var seen: Set<String> = []
        let kept = ids.filter { valid.contains($0) && seen.insert($0).inserted }
        return poll.multi ? kept : Array(kept.prefix(1))
    }

    /// The share of voters who picked the option (0...1).
    public func fraction(_ optionID: String) -> Double {
        voterCount == 0 ? 0 : Double(counts[optionID] ?? 0) / Double(voterCount)
    }

    /// My picks after tapping `optionID`: in a single-choice poll the option replaces mine (or clears it when it was
    /// mine); in a multiple-choice poll it is toggled.
    public func picks(afterTapping optionID: String, in poll: Poll) -> [String] {
        if poll.multi {
            let next = mine.contains(optionID) ? mine.filter { $0 != optionID } : mine + [optionID]
            return poll.options.map(\.id).filter(next.contains)
        }
        return mine == [optionID] ? [] : [optionID]
    }
}
