import Foundation

public struct Contact: Identifiable, Hashable, Codable, Sendable {
    public var id: String
    public var displayName: String
    public var username: String

    public init(id: String, displayName: String, username: String) {
        self.id = id
        self.displayName = displayName
        self.username = username
    }
}

/// Shown only on the chat info page (UI design §5).
public enum ChatEncryption: Hashable, Codable, Sendable {
    case endToEnd, serverReadable

    public var infoText: String {
        switch self {
        case .endToEnd: "End-to-end encrypted"
        case .serverReadable: "Not end-to-end encrypted: the server admin can read this chat"
        }
    }
}

public enum CallSummary: Hashable, Codable, Sendable {
    case missedAudio, missedVideo
    case audio(duration: TimeInterval)
    case video(duration: TimeInterval)
}

public enum MessageContent: Hashable, Codable, Sendable {
    case text(String)
    case voice(duration: TimeInterval)
    case photo(bytes: Int64)
    case file(name: String, bytes: Int64)
    case call(CallSummary)
    /// The sender deleted it (Design: content table `delete`).
    case deleted
    /// Unknown kind or newer format: "Update [App] to see this message".
    case unsupported
    /// A line in the chat, not a bubble (Design 19, 20).
    case system(SystemEvent)
}

/// Chat lines for device and safety changes. `name == nil` means "You".
public enum SystemEvent: Hashable, Codable, Sendable {
    case deviceAdded(name: String?)
    case deviceRemoved(name: String?)
    case safetyNumberChanged
    case messagesPaused

    public var text: String {
        switch self {
        case .deviceAdded(let name?): "\(name) added a new device"
        case .deviceAdded(nil): "You added a new device"
        case .deviceRemoved(let name?): "\(name) removed a device"
        case .deviceRemoved(nil): "You removed a device"
        case .safetyNumberChanged: "Safety number changed"
        case .messagesPaused: "Messages in this chat are paused. Open chat info for details."
        }
    }
}

/// Download state of a photo, voice message or file.
public struct MediaState: Hashable, Codable, Sendable {
    public var attachmentID: String
    public var phase: Phase

    public enum Phase: Hashable, Codable, Sendable {
        case remote, downloading(Double), local(URL), failed
    }

    public init(attachmentID: String, phase: Phase) {
        self.attachmentID = attachmentID
        self.phase = phase
    }
}

public struct MessageHit: Hashable, Sendable {
    public var chatID: String
    public var messageID: String
    public var preview: String

    public init(chatID: String, messageID: String, preview: String) {
        self.chatID = chatID
        self.messageID = messageID
        self.preview = preview
    }
}

/// 12 groups of 5 digits, shown 3 per row in the Verify sheet (Design 20).
public struct SafetyNumber: Hashable, Sendable {
    public var groups: [String]
    public var isVerified: Bool

    public init(groups: [String], isVerified: Bool) {
        self.groups = groups
        self.isVerified = isVerified
    }
}

public enum DeliveryStatus: Hashable, Codable, Sendable {
    case sending, sent, delivered, read
    /// Gave up sending (the chat bubble shows "Not sent" and offers a retry).
    case failed
}

public struct Message: Identifiable, Hashable, Codable, Sendable {
    public var id: String
    public var senderID: String
    public var sentAt: Date
    public var content: MessageContent
    public var status: DeliveryStatus
    public var replyToID: String?
    public var reactions: [String]
    public var editedAt: Date?
    public var media: MediaState?
    public var undecryptable: Bool

    public init(id: String, senderID: String, sentAt: Date, content: MessageContent, status: DeliveryStatus, replyToID: String?, reactions: [String],
                editedAt: Date? = nil, media: MediaState? = nil, undecryptable: Bool = false) {
        self.id = id
        self.senderID = senderID
        self.sentAt = sentAt
        self.content = content
        self.status = status
        self.replyToID = replyToID
        self.reactions = reactions
        self.editedAt = editedAt
        self.media = media
        self.undecryptable = undecryptable
    }

    /// One-line summary for the chat list.
    public var previewText: String {
        switch content {
        case .text(let text): text
        case .voice(let duration): "Voice message \(Self.clock(duration))"
        case .photo: "Photo"
        case .file(let name, _): name
        case .call(.missedAudio): "Missed audio call"
        case .call(.missedVideo): "Missed video call"
        case .call(.audio(let duration)): "Audio call · \(Self.minutes(duration)) min"
        case .call(.video(let duration)): "Video call · \(Self.minutes(duration)) min"
        case .deleted: "This message was deleted"
        case .unsupported: "Update [App] to see this message"
        case .system(let event): event.text
        }
    }

    static func clock(_ seconds: TimeInterval) -> String {
        let s = Int(seconds.rounded())
        let rest = s % 60
        return "\(s / 60):\(rest < 10 ? "0" : "")\(rest)"
    }

    static func minutes(_ seconds: TimeInterval) -> Int {
        max(1, Int((seconds / 60).rounded()))
    }
}

public struct Chat: Identifiable, Hashable, Codable, Sendable {
    public var id: String
    public var contactID: String
    public var encryption: ChatEncryption
    public var folderIDs: Set<String>
    public var unreadCount: Int
    public var messages: [Message]
    public var isRequest: Bool
    /// The server and the conversation disagree about the chat's mode; sending is paused (Design 18).
    public var modeMismatch: Bool

    public init(id: String, contactID: String, encryption: ChatEncryption, folderIDs: Set<String>, unreadCount: Int, messages: [Message], isRequest: Bool,
                modeMismatch: Bool = false) {
        self.id = id
        self.contactID = contactID
        self.encryption = encryption
        self.folderIDs = folderIDs
        self.unreadCount = unreadCount
        self.messages = messages
        self.isRequest = isRequest
        self.modeMismatch = modeMismatch
    }

    public var lastMessage: Message? { messages.last }
}
