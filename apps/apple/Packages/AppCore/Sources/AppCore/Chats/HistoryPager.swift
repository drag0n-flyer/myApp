import Foundation

/// When to ask the store for an older page (spec 5.3).
public struct HistoryPager: Sendable {
    public static let pageSize = 50
    /// How close to the oldest loaded message the user must scroll before the next page is requested.
    static let threshold = 5

    public private(set) var reachedStart = false
    public private(set) var isLoading = false

    public init() {}

    public func shouldLoad(oldestVisibleIndex: Int) -> Bool {
        oldestVisibleIndex < Self.threshold && !isLoading && !reachedStart
    }

    public mutating func begin() { isLoading = true }

    public mutating func finish(loadedCount: Int) {
        isLoading = false
        // Only an empty page proves the start: a page can come back short while older messages exist
        // (hidden system lines are dropped after the database limit).
        if loadedCount == 0 { reachedStart = true }
    }
}

public enum MessageMerge {
    /// `newest` (the store's current copy) and `older` (pages loaded by scrolling) as one list, oldest first,
    /// without duplicates. The store's copy wins, so edits, deletes and reactions show.
    public static func merged(newest: [Message], older: [Message]) -> [Message] {
        var byID: [String: Message] = [:]
        var order: [String] = []
        for message in older + newest {
            if byID[message.id] == nil { order.append(message.id) }
            byID[message.id] = message
        }
        let originalIndex = Dictionary(uniqueKeysWithValues: order.enumerated().map { ($1, $0) })
        return order.compactMap { byID[$0] }.sorted {
            if $0.sentAt != $1.sentAt { return $0.sentAt < $1.sentAt }
            return originalIndex[$0.id, default: 0] < originalIndex[$1.id, default: 0]
        }
    }
}

/// Older messages loaded by scrolling, merged with the store's newest messages. Value type so a failed
/// jump can be tried on a copy and thrown away.
public struct ChatHistory: Sendable {
    public private(set) var older: [Message] = []
    public private(set) var pager = HistoryPager()

    public init() {}

    public func messages(newest: [Message]) -> [Message] {
        MessageMerge.merged(newest: newest, older: older)
    }

    /// Loads one page before the oldest message currently known. Returns how many new messages arrived.
    /// `loader(beforeID, limit)` has the semantics of `ChatStore.messages(in:before:limit:)`.
    @discardableResult
    public mutating func loadPage(newest: [Message], loader: (String, Int) -> [Message]) -> Int {
        guard !pager.reachedStart, !pager.isLoading else { return 0 }
        pager.begin()
        guard let oldestID = messages(newest: newest).first?.id else {
            // Nothing is known yet, so there is nothing to page from.
            pager.finish(loadedCount: 0)
            return 0
        }
        let page = loader(oldestID, HistoryPager.pageSize)
        let before = messages(newest: newest).count
        older = MessageMerge.merged(newest: older, older: page)
        let added = messages(newest: newest).count - before
        // A page that adds nothing new means the store has nothing more to give; do not ask again.
        pager.finish(loadedCount: added == 0 ? 0 : page.count)
        return added
    }

    /// Call when the store's newest messages changed. Keeps messages that slid out of the newest window,
    /// fills a gap left by a burst of arrivals, and re-reads the loaded older range so edits, deletes and
    /// reactions on older messages show. The store's copy always wins.
    public mutating func sync(previousNewest: [Message], newest: [Message], loader: (String, Int) -> [Message]) {
        older = MessageMerge.merged(newest: previousNewest, older: older)
        guard let firstID = newest.first?.id else { return }
        if let lastSeen = previousNewest.last?.id, !newest.contains(where: { $0.id == lastSeen }) {
            var before = firstID
            for _ in 0..<20 {
                let page = loader(before, HistoryPager.pageSize)
                older = MessageMerge.merged(newest: page, older: older)
                guard let oldestOfPage = page.first, !page.contains(where: { $0.id == lastSeen }) else { break }
                before = oldestOfPage.id
            }
        }
        if !older.isEmpty {
            let fresh = loader(firstID, min(older.count, 500))
            older = MessageMerge.merged(newest: fresh, older: older)
        }
    }

    /// Loads older pages until `targetID` is known. False when history ends first; the caller then leaves the chat as it was.
    public mutating func load(until targetID: String, newest: [Message], loader: (String, Int) -> [Message]) -> Bool {
        while true {
            if messages(newest: newest).contains(where: { $0.id == targetID }) { return true }
            if pager.reachedStart { return false }
            loadPage(newest: newest, loader: loader)
        }
    }
}

/// Follows new messages while the newest one is on screen; otherwise counts them for the scroll button.
public struct NewestTracker: Sendable {
    public private(set) var atBottom = true
    private var anchorID: String?

    public init() {}

    /// The newest message went on or off screen. `anchorID` is the newest message the user has seen.
    public mutating func setAtBottom(_ value: Bool, newestID: String?) {
        atBottom = value
        if value { anchorID = newestID }
    }

    /// The newest message changed. True when the screen should follow it to the bottom.
    public mutating func messagesChanged(newestID: String?) -> Bool {
        guard atBottom else { return false }
        anchorID = newestID
        return true
    }

    /// Messages after the last one the user saw. Older pages added above do not change it.
    public func unseenCount(in messages: [Message]) -> Int {
        guard !atBottom, let anchorID, let index = messages.lastIndex(where: { $0.id == anchorID }) else { return 0 }
        return messages.count - index - 1
    }
}
