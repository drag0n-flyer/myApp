import Foundation

/// How the chat list is ordered and filtered. Pure, so the rules are tested on Linux.
/// The same rules as `ChatListOrder.kt` on Android.
public enum ChatListOrder {
    public static let maxPins = 5

    /// Pinned chats first, the newest pin on top; then the rest by last message, newest first.
    /// Equal times keep a stable order by chat ID.
    public static func sorted(_ chats: [Chat]) -> [Chat] {
        pinnedFirst(chats.sorted {
            let a = $0.lastMessage?.sentAt ?? .distantPast, b = $1.lastMessage?.sentAt ?? .distantPast
            return a != b ? a > b : $0.id < $1.id
        })
    }

    /// Moves the pinned chats to the front, newest pin first; the others keep the order they came in. For a store
    /// whose list is already in its own activity order.
    public static func pinnedFirst(_ chats: [Chat]) -> [Chat] {
        let pinned = chats.filter { $0.pinnedAt != nil }.sorted {
            let a = $0.pinnedAt ?? .distantPast, b = $1.pinnedAt ?? .distantPast
            return a != b ? a > b : $0.id < $1.id
        }
        return pinned + chats.filter { $0.pinnedAt == nil }
    }

    /// The main list (`archive == false`) or the archive (`archive == true`).
    public static func visible(_ chats: [Chat], archive: Bool) -> [Chat] {
        chats.filter { $0.archived == archive }
    }

    /// Chats with something unread that are neither muted nor archived.
    public static func badgeCount(_ chats: [Chat]) -> Int {
        chats.filter { $0.unreadCount > 0 && !$0.muted && !$0.archived }.count
    }

    /// The unread count shown on the "Archived" row: every unread message in archived chats.
    public static func archivedUnread(_ chats: [Chat]) -> Int {
        chats.filter(\.archived).reduce(0) { $0 + $1.unreadCount }
    }

    /// The first line of the draft that is not blank, trimmed; `nil` when there is none.
    public static func draftPreview(_ draft: String?) -> String? {
        guard let draft else { return nil }
        for line in draft.split(whereSeparator: \.isNewline) {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { return trimmed }
        }
        return nil
    }
}
