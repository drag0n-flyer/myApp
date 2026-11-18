import Foundation

/// Tells the person's edits of the composer from text the program put there (a saved draft, the text of a message being
/// edited): only the first count as typing. Call `willAssign` just before assigning, then `isUserEdit` in the change handler.
public struct ComposerEditTracker: Equatable, Sendable {
    private var expected: [String] = []

    public init() {}

    /// A program assignment of `text` over the composer's `current` text. Nothing is expected when it changes nothing,
    /// because no change event follows.
    public mutating func willAssign(_ text: String, current: String) {
        if text != current { expected.append(text) }
    }

    /// False for the change that was set by `willAssign` (consumed), true for anything else.
    public mutating func isUserEdit(newText: String) -> Bool {
        if let first = expected.first, first == newText {
            expected.removeFirst()
            return false
        }
        // Something else arrived: what was expected is no longer on its way.
        expected.removeAll()
        return true
    }
}

/// The three privacy switches that outlive a launch.
public struct PrivacySwitches: Equatable, Sendable {
    public var typingIndicator: Bool
    public var onlineAndLastSeen: Bool
    public var readReceipts: Bool

    public init(typingIndicator: Bool = true, onlineAndLastSeen: Bool = true, readReceipts: Bool = true) {
        self.typingIndicator = typingIndicator
        self.onlineAndLastSeen = onlineAndLastSeen
        self.readReceipts = readReceipts
    }
}

/// Keeps `PrivacySwitches` in `UserDefaults` (the same store as the app lock). A missing or odd value counts as on.
public struct PrivacySwitchesStore {
    public static let typingKey = "privacy.typingIndicator"
    public static let presenceKey = "privacy.onlineAndLastSeen"
    public static let receiptsKey = "privacy.readReceipts"
    private let defaults: UserDefaults

    public init(defaults: UserDefaults) { self.defaults = defaults }

    public func load() -> PrivacySwitches {
        PrivacySwitches(
            typingIndicator: defaults.object(forKey: Self.typingKey) as? Bool ?? true,
            onlineAndLastSeen: defaults.object(forKey: Self.presenceKey) as? Bool ?? true,
            readReceipts: defaults.object(forKey: Self.receiptsKey) as? Bool ?? true)
    }

    public func save(_ switches: PrivacySwitches) {
        defaults.set(switches.typingIndicator, forKey: Self.typingKey)
        defaults.set(switches.onlineAndLastSeen, forKey: Self.presenceKey)
        defaults.set(switches.readReceipts, forKey: Self.receiptsKey)
    }
}

/// Which chats get `online` and `away`: those with a message in the last 7 days, at most 30, most recent first.
public enum PresenceFanOut {
    public static let windowMs: Int64 = 7 * 86_400_000
    public static let maxChats = 30

    public struct Candidate: Equatable, Sendable {
        public var id: String
        public var lastMessageMs: Int64?
        public init(id: String, lastMessageMs: Int64?) {
            self.id = id
            self.lastMessageMs = lastMessageMs
        }
    }

    public static func targets(_ chats: [Candidate], nowMs: Int64) -> [String] {
        chats
            .compactMap { chat -> (String, Int64)? in
                guard let at = chat.lastMessageMs, nowMs - at < windowMs else { return nil }
                return (chat.id, at)
            }
            .sorted { $0.1 > $1.1 }
            .prefix(maxChats)
            .map(\.0)
    }
}
