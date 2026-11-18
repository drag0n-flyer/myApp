import Foundation

/// Items in a message's context menu (spec 5.2).
public enum MessageAction: Equatable, Sendable { case reply, react, copy, forward, edit, delete }

public enum MessageActions {
    /// The six quick reactions, in menu order.
    public static let reactionEmoji = ["👍", "❤️", "😂", "😮", "😢", "🙏"]

    /// Which menu items apply to `message`, in the spec 5.2 order.
    public static func available(for message: Message, isMine: Bool) -> [MessageAction] {
        let isText: Bool
        switch message.content {
        case .deleted, .system, .call, .unsupported: return []
        case .text: isText = true
        case .voice, .photo, .file: isText = false
        }
        if isMine, message.status == .failed {
            return isText ? [.copy, .delete] : [.delete]
        }
        var actions: [MessageAction] = [.reply, .react]
        if isText { actions += [.copy, .forward] }
        if isMine, isText { actions.append(.edit) }
        if isMine { actions.append(.delete) }
        return actions
    }

    /// Whether `action`, picked from a menu built earlier, may still run against the message as it is now.
    /// `current` is nil when the message vanished. Guards against remote deletes and failed sends.
    public static func isStillApplicable(_ action: MessageAction, current: Message?, isMine: Bool) -> Bool {
        guard let current else { return false }
        return available(for: current, isMine: isMine).contains(action)
    }
}

/// Drops a repeat of the same reaction on the same message within a short window, so a double tap
/// does not add and then remove it.
public struct ReactionGate: Sendable {
    public static let window: TimeInterval = 0.6
    private var lastMessageID: String?
    private var lastEmoji: String?
    private var lastAt: Date?

    public init() {}

    public mutating func allow(messageID: String, emoji: String, at now: Date) -> Bool {
        if lastMessageID == messageID, lastEmoji == emoji, let lastAt, now.timeIntervalSince(lastAt) < Self.window {
            return false
        }
        lastMessageID = messageID
        lastEmoji = emoji
        lastAt = now
        return true
    }
}
