import Foundation

/// What one notification shows. `title` is empty when `hidesContent`: the caller puts the app name there.
/// `bodyNeedsTranslation`: `body` is a fixed English phrase ("New message", "Missed call") the caller looks up in its
/// String Catalog; otherwise `body` is the message text and is shown as it is.
public struct PlannedNotification: Equatable, Sendable {
    public var title: String
    public var body: String
    /// The chat ID: one thread per chat.
    public var threadIdentifier: String
    public var category: String
    public var silent: Bool
    /// App lock is on: nobody is named, no text is shown, and there is no Reply.
    public var hidesContent: Bool
    public var bodyNeedsTranslation: Bool
}

public enum ReplyDecision: Equatable, Sendable {
    case send
    /// Do not send; keep the text as the chat's draft and say "Not sent".
    case refuseKeepDraft
}

/// What a reply turned into.
public enum ReplyOutcome: Equatable, Sendable {
    case sent
    case keepDraft
}

/// The same rules as `apps/android/core/.../notify/NotificationPlan.kt`.
public enum NotificationPlan {
    public static let messageCategory = "message"
    public static let lockedCategory = "message.locked"
    public static let hiddenText = "New message"
    public static let missedCallText = "Missed call"
    /// Same limit as the composer.
    public static let maxReplyLength = 4096

    public static func content(
        chatID: String, sender: String, text: String, muted: Bool, appLockOn: Bool, missedCall: Bool = false
    ) -> PlannedNotification {
        // A missed call is never silent, muted chat or not.
        let silent = muted && !missedCall
        if appLockOn {
            return PlannedNotification(
                title: "", body: missedCall ? missedCallText : hiddenText, threadIdentifier: chatID,
                category: lockedCategory, silent: silent, hidesContent: true, bodyNeedsTranslation: true)
        }
        return PlannedNotification(
            title: sender, body: missedCall ? missedCallText : text, threadIdentifier: chatID,
            category: messageCategory, silent: silent, hidesContent: false, bodyNeedsTranslation: missedCall)
    }

    /// An already delivered notification, rewritten when app lock is switched on. Quiet: it must not alert again.
    public static func lockedForm(chatID: String) -> PlannedNotification {
        content(chatID: chatID, sender: "", text: "", muted: true, appLockOn: true)
    }

    /// The title of a call notification: empty (the caller shows the app name) with app lock on.
    public static func callTitle(callerName: String, appLockOn: Bool) -> String {
        appLockOn ? "" : callerName
    }

    /// `textLength` is the length in characters of the trimmed reply.
    public static func replyDecision(
        chatKnown: Bool, isRequest: Bool, isBlocked: Bool, textLength: Int, appLockOn: Bool
    ) -> ReplyDecision {
        guard chatKnown, !isRequest, !isBlocked, !appLockOn, textLength >= 1, textLength <= maxReplyLength else {
            return .refuseKeepDraft
        }
        return .send
    }

    /// Chats with unread messages that are neither muted nor archived.
    public static func badgeCount(unread: [String: Int], muted: Set<String>, archived: Set<String>) -> Int {
        unread.filter { $0.value > 0 && !muted.contains($0.key) && !archived.contains($0.key) }.count
    }
}

/// Finds the chat a notification belongs to.
public enum NotificationRoute {
    static let maxIDLength = 256
    /// Notifications that belong to no chat (the "New device added" notice).
    static let nonChatThreads: Set<String> = ["devices"]

    /// The chat ID from the notification's `userInfo` and its thread identifier. When both exist they must agree.
    /// nil when there is none, it is blank or too long, or the thread is not a chat's.
    public static func chatID(userInfoChatID: String?, threadID: String) -> String? {
        let fromInfo = userInfoChatID.flatMap { $0.isEmpty ? nil : $0 }
        let fromThread = threadID.isEmpty ? nil : threadID
        if let fromInfo, let fromThread, fromInfo != fromThread { return nil }
        guard let id = fromInfo ?? fromThread,
              !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              id.count <= maxIDLength, !nonChatThreads.contains(id)
        else { return nil }
        return id
    }
}

/// Replies already handled, so one that is delivered twice is sent once. Only a hash of the text is kept.
public struct ReplyClaims: Sendable {
    public static let limit = 256
    private var used: Set<Int> = []

    public init() {}

    public var count: Int { used.count }

    /// True the first time this request ID and text are seen.
    public mutating func claim(requestID: String, text: String) -> Bool {
        if used.count >= Self.limit { used.removeAll() }
        var hasher = Hasher()
        hasher.combine(requestID)
        hasher.combine(text)
        return used.insert(hasher.finalize()).inserted
    }
}

public enum ReplyDraft {
    /// `text` on a new line after the chat's existing draft.
    public static func appending(_ text: String, to existing: String?) -> String {
        guard let existing, !existing.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return text }
        return existing.hasSuffix("\n") ? existing + text : existing + "\n" + text
    }

    /// A reply counts as sent only when it was decided to send and the store queued it.
    public static func outcome(decision: ReplyDecision, queued: Bool) -> ReplyOutcome {
        decision == .send && queued ? .sent : .keepDraft
    }
}

/// The two plain settings the notification extension reads from the shared app-group container: it cannot see the
/// app's database or memory. The app writes this file; the extension reads it and never writes it.
public struct NotificationSettings: Codable, Equatable, Sendable {
    public static let fileName = "notification-settings.json"

    public var mutedChatIDs: Set<String>
    public var appLockOn: Bool

    public init(mutedChatIDs: Set<String>, appLockOn: Bool) {
        self.mutedChatIDs = mutedChatIDs
        self.appLockOn = appLockOn
    }

    /// What is used when the file is missing or unreadable: locked, nothing muted.
    public static let unreadable = NotificationSettings(mutedChatIDs: [], appLockOn: true)

    public static func decode(_ data: Data?) -> NotificationSettings {
        guard let data, let value = try? JSONDecoder().decode(NotificationSettings.self, from: data) else { return unreadable }
        return value
    }

    public func encoded() -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return (try? encoder.encode(self)) ?? Data()
    }
}
