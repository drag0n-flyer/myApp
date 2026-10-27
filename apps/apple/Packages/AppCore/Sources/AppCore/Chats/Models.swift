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
}

public enum DeliveryStatus: Hashable, Codable, Sendable {
    case sending, sent, delivered, read
}

public struct Message: Identifiable, Hashable, Codable, Sendable {
    public var id: String
    public var senderID: String
    public var sentAt: Date
    public var content: MessageContent
    public var status: DeliveryStatus
    public var replyToID: String?
    public var reactions: [String]

    public init(id: String, senderID: String, sentAt: Date, content: MessageContent, status: DeliveryStatus, replyToID: String?, reactions: [String]) {
        self.id = id
        self.senderID = senderID
        self.sentAt = sentAt
        self.content = content
        self.status = status
        self.replyToID = replyToID
        self.reactions = reactions
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

    public init(id: String, contactID: String, encryption: ChatEncryption, folderIDs: Set<String>, unreadCount: Int, messages: [Message], isRequest: Bool) {
        self.id = id
        self.contactID = contactID
        self.encryption = encryption
        self.folderIDs = folderIDs
        self.unreadCount = unreadCount
        self.messages = messages
        self.isRequest = isRequest
    }

    public var lastMessage: Message? { messages.last }
}
