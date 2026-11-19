import Foundation

/// Where a bubble sits in a run of bubbles from one sender, so the chat can tighten the gap and keep the tail
/// (the squarer corner) for the last bubble of a run only.
public struct BubbleRun: Equatable, Sendable {
    public let joinsAbove: Bool
    public let joinsBelow: Bool

    public init(joinsAbove: Bool, joinsBelow: Bool) {
        self.joinsAbove = joinsAbove
        self.joinsBelow = joinsBelow
    }

    /// A run is broken by another sender, a system line or call record, another day, or a pause of more than 5 minutes.
    public static func at(_ index: Int, in messages: [Message], calendar: Calendar = .current) -> BubbleRun {
        guard messages.indices.contains(index) else { return BubbleRun(joinsAbove: false, joinsBelow: false) }
        let above = index > 0 && joins(messages[index - 1], messages[index], calendar)
        let below = index + 1 < messages.count && joins(messages[index], messages[index + 1], calendar)
        return BubbleRun(joinsAbove: above, joinsBelow: below)
    }

    private static func joins(_ a: Message, _ b: Message, _ calendar: Calendar) -> Bool {
        guard plain(a), plain(b), a.senderID == b.senderID else { return false }
        guard calendar.isDate(a.sentAt, inSameDayAs: b.sentAt) else { return false }
        return abs(b.sentAt.timeIntervalSince(a.sentAt)) <= 300
    }

    private static func plain(_ m: Message) -> Bool {
        switch m.content {
        case .system, .call: false
        default: true
        }
    }
}
