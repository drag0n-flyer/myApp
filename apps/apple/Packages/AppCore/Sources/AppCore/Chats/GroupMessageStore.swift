import Foundation

/// One line of Message info: a member and the time of their receipt.
public struct ReceiptEntry: Hashable, Sendable {
    public var accountID: String
    public var name: String
    public var at: Date

    public init(accountID: String, name: String, at: Date) {
        self.accountID = accountID
        self.name = name
        self.at = at
    }
}

/// "Read by" and "Delivered to" of one message of mine in a group.
public struct MessageReceiptLists: Equatable, Sendable {
    public var readBy: [ReceiptEntry]
    public var deliveredTo: [ReceiptEntry]

    public init(readBy: [ReceiptEntry] = [], deliveredTo: [ReceiptEntry] = []) {
        self.readBy = readBy
        self.deliveredTo = deliveredTo
    }
}

/// What a group chat needs from the store beyond a one-to-one chat: who said what (names of members who need not be
/// contacts) and who has received or read a message.
@MainActor
public protocol GroupMessageStore: AnyObject {
    /// The name to show for a member of a group chat: the contact's or profile name, else "Someone". Never the empty
    /// string and never an account ID.
    func memberName(_ accountID: String) -> String
    /// The lists of Message info for one of my messages in a group chat; empty for anything else.
    func receiptLists(messageID: String, in chatID: String) -> MessageReceiptLists
}

extension GroupMessageStore {
    public func memberName(_ accountID: String) -> String { "Someone" }
    public func receiptLists(messageID: String, in chatID: String) -> MessageReceiptLists { MessageReceiptLists() }
}
