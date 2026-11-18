import Foundation

public enum ChatRow: Equatable, Identifiable, Sendable {
    /// The start of a local day.
    case date(Date)
    case unreadMarker
    case message(Message)

    public var id: String {
        switch self {
        case .date(let day): "date-\(Int64(day.timeIntervalSince1970))"
        case .unreadMarker: "unread-marker"
        case .message(let message): message.id
        }
    }
}

public enum DaySpan: Equatable, Sendable { case today, yesterday, other }

public enum ChatTimeline {
    /// Messages (oldest first) with a date row above the first message of each local day and the
    /// unread marker directly above `firstUnreadID`.
    public static func rows(messages: [Message], firstUnreadID: String?, calendar: Calendar) -> [ChatRow] {
        var rows: [ChatRow] = []
        var currentDay: Date?
        for message in messages {
            let day = calendar.startOfDay(for: message.sentAt)
            if day != currentDay {
                rows.append(.date(day))
                currentDay = day
            }
            if message.id == firstUnreadID { rows.append(.unreadMarker) }
            rows.append(.message(message))
        }
        return rows
    }

    public static func daySpan(for day: Date, now: Date, calendar: Calendar) -> DaySpan {
        if calendar.isDate(day, inSameDayAs: now) { return .today }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(day, inSameDayAs: yesterday) {
            return .yesterday
        }
        return .other
    }

    /// "Today", "Yesterday", or the medium date. The app shows the first two through the String Catalog.
    public static func dateLabel(for day: Date, now: Date, calendar: Calendar) -> String {
        switch daySpan(for: day, now: now, calendar: calendar) {
        case .today: return "Today"
        case .yesterday: return "Yesterday"
        case .other:
            let formatter = DateFormatter()
            formatter.calendar = calendar
            formatter.timeZone = calendar.timeZone
            formatter.locale = calendar.locale ?? .current
            formatter.dateStyle = .medium
            formatter.timeStyle = .none
            return formatter.string(from: day)
        }
    }

    /// The oldest of the last `unreadCount` messages that someone else sent, or nil when nothing is unread.
    public static func firstUnreadID(messages: [Message], unreadCount: Int, myID: String) -> String? {
        guard unreadCount > 0 else { return nil }
        let incoming = messages.filter {
            if $0.senderID == myID { return false }
            // System lines and call records do not raise the unread count.
            switch $0.content {
            case .system, .call: return false
            default: return true
            }
        }
        return incoming.suffix(unreadCount).first?.id
    }
}
