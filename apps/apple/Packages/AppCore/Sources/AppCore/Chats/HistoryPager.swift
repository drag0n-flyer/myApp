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

    /// Most pages a gap fill or a jump may read before giving up.
    static let maxGapPages = 20
    static let maxJumpPages = 40
    /// When more than this many older messages are loaded, only the newest `trimTo` are kept; paging reloads the rest.
    static let trimAbove = 1000
    static let trimTo = 500
    static let refreshPageSize = 500

    /// Call when the store's newest messages changed (or, with `refreshOlder`, when an older message may have
    /// changed). Keeps messages that slid out of the newest window, fills the gap a burst of arrivals leaves,
    /// and re-reads the loaded older range so edits, deletes and removals show. The store's copy always wins.
    /// With nothing changed and `refreshOlder` false it reads nothing.
    public mutating func sync(previousNewest: [Message], newest: [Message], refreshOlder: Bool = true,
                              loader: (String, Int) -> [Message]) {
        guard let first = newest.first else { return }
        if previousNewest == newest && !refreshOlder { return }
        let newestIDs = Set(newest.map(\.id))
        // Only messages older than the newest window belong in `older`; this also drops anything the store removed
        // from the newest window.
        let slid = previousNewest.filter { !newestIDs.contains($0.id) && $0.sentAt <= first.sentAt }
        older = MessageMerge.merged(newest: slid, older: older.filter { !newestIDs.contains($0.id) && $0.sentAt <= first.sentAt })

        // A burst can push everything we knew out of the window: read back to what we knew.
        if let knownLast = previousNewest.last, !previousNewest.contains(where: { newestIDs.contains($0.id) }),
           first.sentAt > knownLast.sentAt {
            var before = first.id
            var closed = false
            for _ in 0..<Self.maxGapPages {
                let page = loader(before, HistoryPager.pageSize)
                older = MessageMerge.merged(newest: page, older: older)
                guard let oldest = page.first else { closed = true; break }
                if oldest.sentAt <= knownLast.sentAt { closed = true; break }
                before = oldest.id
            }
            if !closed {
                // Too big a burst to close: show the contiguous newest window and page from there.
                older = []
                pager = HistoryPager()
                return
            }
        }

        if older.count > Self.trimAbove {
            older = Array(older.suffix(Self.trimTo))
            pager = HistoryPager()
        }

        guard refreshOlder, let oldestLoaded = older.first else { return }
        var fresh: [Message] = []
        var before = first.id
        var complete = false
        for _ in 0..<8 {
            let limit = min(Self.refreshPageSize, max(1, older.count - fresh.count))
            let page = loader(before, limit)
            fresh = MessageMerge.merged(newest: page, older: fresh)
            guard let oldest = page.first else { complete = true; break }
            if oldest.sentAt <= oldestLoaded.sentAt { complete = true; break }
            before = oldest.id
        }
        if complete {
            older = fresh
        } else {
            older = []
            pager = HistoryPager()
        }
    }

    /// Loads older pages until `targetID` is known. False when history ends first, or after `maxJumpPages`
    /// pages; the caller then leaves the chat as it was.
    public mutating func load(until targetID: String, newest: [Message], loader: (String, Int) -> [Message]) -> Bool {
        for _ in 0..<Self.maxJumpPages {
            if messages(newest: newest).contains(where: { $0.id == targetID }) { return true }
            if pager.reachedStart { return false }
            loadPage(newest: newest, loader: loader)
        }
        return messages(newest: newest).contains(where: { $0.id == targetID })
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
