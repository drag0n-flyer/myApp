import Foundation

/// One member's receipt for one message: `kind` is `delivered` or `read`.
public struct GroupReceipt: Hashable, Sendable {
    public var accountID: String
    public var kind: String
    public var at: Date

    public init(accountID: String, kind: String, at: Date = .distantPast) {
        self.accountID = accountID
        self.kind = kind
        self.at = at
    }
}

/// The tick of one of my messages in a group.
public enum GroupTick: Int, Comparable, Sendable {
    case sent, delivered, read

    public static func < (a: GroupTick, b: GroupTick) -> Bool { a.rawValue < b.rawValue }
}

public enum GroupTicks {
    /// Delivered or read only when every other current member has delivered or read it (a read counts as delivered).
    /// Only accounts in `members` count: a member who left, or an account that never was one, neither holds the tick
    /// back nor moves it. With nobody else in the group the message stays "sent".
    public static func state(receipts: [GroupReceipt], members: [String], me: String) -> GroupTick {
        let others = Set(members).subtracting([me])
        guard !others.isEmpty else { return .sent }
        var delivered = Set<String>(), read = Set<String>()
        for receipt in receipts where others.contains(receipt.accountID) {
            switch receipt.kind {
            case "read": read.insert(receipt.accountID); delivered.insert(receipt.accountID)
            case "delivered": delivered.insert(receipt.accountID)
            default: break
            }
        }
        if read == others { return .read }
        if delivered == others { return .delivered }
        return .sent
    }

    /// The two lists of Message info: who has read it, and who has it delivered but not read. Current members other than
    /// `me` only, each at the time of their receipt; ordered by time, then account ID.
    public static func lists(receipts: [GroupReceipt], members: [String], me: String)
        -> (readBy: [GroupReceipt], deliveredTo: [GroupReceipt]) {
        let others = Set(members).subtracting([me])
        var read: [String: Date] = [:], delivered: [String: Date] = [:]
        for receipt in receipts where others.contains(receipt.accountID) {
            switch receipt.kind {
            case "read": read[receipt.accountID] = min(read[receipt.accountID] ?? .distantFuture, receipt.at)
            case "delivered": delivered[receipt.accountID] = min(delivered[receipt.accountID] ?? .distantFuture, receipt.at)
            default: break
            }
        }
        func ordered(_ map: [String: Date], kind: String) -> [GroupReceipt] {
            map.map { GroupReceipt(accountID: $0.key, kind: kind, at: $0.value) }.sorted { ($0.at, $0.accountID) < ($1.at, $1.accountID) }
        }
        return (ordered(read, kind: "read"), ordered(delivered.filter { read[$0.key] == nil }, kind: "delivered"))
    }
}

/// What a group chat shows around a message.
public enum GroupLines {
    /// Whose name goes in front of the chat-list preview of the last message.
    public enum Sender: Equatable, Sendable { case me, other(String) }

    /// `nil` for no name: a one-to-one chat, an empty chat, a system line or a call.
    public static func previewSender(in chat: Chat, me: String) -> Sender? {
        guard ChatID.isGroup(chat.id), let last = chat.lastMessage else { return nil }
        switch last.content {
        case .system, .call: return nil
        default: return last.senderID == me ? .me : .other(last.senderID)
        }
    }

    /// Whether the incoming bubble at `index` shows the sender's name and tile: the first of a run by one sender. A
    /// system line, another sender or another day starts a new run. Never for my own messages or a system line.
    public static func showsSender(at index: Int, in messages: [Message], me: String, calendar: Calendar = .current) -> Bool {
        guard messages.indices.contains(index) else { return false }
        let message = messages[index]
        if case .system = message.content { return false }
        if case .call = message.content { return false }
        guard message.senderID != me else { return false }
        guard index > 0 else { return true }
        let before = messages[index - 1]
        if case .system = before.content { return true }
        return before.senderID != message.senderID || !calendar.isDate(before.sentAt, inSameDayAs: message.sentAt)
    }
}

/// "%@ is typing…" for one, "%1$d people are typing…" for two or more.
public enum GroupTyping {
    public enum Summary: Equatable, Sendable {
        case none
        case one(String)
        case many(Int)
    }

    /// `names` are the display names of the members typing now (one per account, already cleaned).
    public static func summary(names: [String]) -> Summary {
        switch names.count {
        case 0: .none
        case 1: .one(names[0])
        default: .many(names.count)
        }
    }

    /// Typing counts for 10 seconds after the last `typing on` of that account, as in a one-to-one chat; only for
    /// accounts that are current members and are neither me nor blocked. Sorted by account ID.
    public static func typingNow(
        lastOnMs: [String: Int64], members: Set<String>, me: String, blocked: Set<String>, nowMs: Int64
    ) -> [String] {
        lastOnMs.filter { account, on in
            account != me && members.contains(account) && !blocked.contains(account) && TypingShown.isTyping(lastOnMs: on, nowMs: nowMs)
        }.keys.sorted()
    }
}
