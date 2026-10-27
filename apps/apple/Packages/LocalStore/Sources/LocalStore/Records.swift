import Foundation

public struct ContactRecord: Sendable, Equatable {
    public var accountID: String, username: String, displayName: String
    public var isContact: Bool
    public var addedAt: Date
    public init(accountID: String, username: String, displayName: String, isContact: Bool, addedAt: Date) {
        self.accountID = accountID; self.username = username; self.displayName = displayName
        self.isContact = isContact; self.addedAt = addedAt
    }
}

public struct ChatRecord: Sendable, Equatable {
    public var id: String
    public var isRequest: Bool
    public var unread: Int
    public var muted: Bool
    public var lastActivity: Date
    public init(id: String, isRequest: Bool, unread: Int, muted: Bool, lastActivity: Date) {
        self.id = id; self.isRequest = isRequest; self.unread = unread; self.muted = muted; self.lastActivity = lastActivity
    }
}

public struct ConversationRecord: Sendable, Equatable {
    public var serverID: String, chatID: String
    public var groupID: Data
    /// The server's claim.
    public var mode: String
    /// Pinned from the creator's `mode` envelope.
    public var innerMode: String?
    public var modeMismatch: Bool
    public var epoch: Int64
    public var active: Bool
    public var createdAt: Date
    public init(serverID: String, chatID: String, groupID: Data, mode: String, innerMode: String?, modeMismatch: Bool,
                epoch: Int64, active: Bool, createdAt: Date) {
        self.serverID = serverID; self.chatID = chatID; self.groupID = groupID; self.mode = mode
        self.innerMode = innerMode; self.modeMismatch = modeMismatch; self.epoch = epoch; self.active = active
        self.createdAt = createdAt
    }
}

public struct MessageRecord: Sendable, Equatable {
    public var id: String, chatID: String
    public var conversationID: String?
    public var senderAccount: String
    public var senderDevice: String?
    public var sentAt: Date, receivedAt: Date
    public var envelope: Data
    public var status: String
    public var replyTo: String?
    public var editedAt: Date?
    public var deleted: Bool, undecryptable: Bool
    public var folded: String
    public init(id: String, chatID: String, conversationID: String?, senderAccount: String, senderDevice: String?,
                sentAt: Date, receivedAt: Date, envelope: Data, status: String, replyTo: String?, editedAt: Date?,
                deleted: Bool, undecryptable: Bool, folded: String) {
        self.id = id; self.chatID = chatID; self.conversationID = conversationID; self.senderAccount = senderAccount
        self.senderDevice = senderDevice; self.sentAt = sentAt; self.receivedAt = receivedAt; self.envelope = envelope
        self.status = status; self.replyTo = replyTo; self.editedAt = editedAt; self.deleted = deleted
        self.undecryptable = undecryptable; self.folded = folded
    }
}

public struct ReactionRecord: Sendable, Equatable {
    public var messageID: String, accountID: String, emoji: String
    public init(messageID: String, accountID: String, emoji: String) {
        self.messageID = messageID; self.accountID = accountID; self.emoji = emoji
    }
}

public struct AttachmentRecord: Sendable, Equatable {
    public var id: String, messageID: String, kind: String
    public var key: Data, digest: Data
    public var size: Int64, cipherSize: Int64
    public var state: String
    public var downloaded: Int64
    public var localPath: String?
    public init(id: String, messageID: String, kind: String, key: Data, digest: Data, size: Int64, cipherSize: Int64,
                state: String, downloaded: Int64, localPath: String?) {
        self.id = id; self.messageID = messageID; self.kind = kind; self.key = key; self.digest = digest
        self.size = size; self.cipherSize = cipherSize; self.state = state; self.downloaded = downloaded
        self.localPath = localPath
    }
}

public struct OutboxRecord: Sendable, Equatable {
    public var messageID: String, chatID: String
    public var envelope: Data
    public var attempts: Int
    public var nextAttemptAt: Date
    public init(messageID: String, chatID: String, envelope: Data, attempts: Int, nextAttemptAt: Date) {
        self.messageID = messageID; self.chatID = chatID; self.envelope = envelope
        self.attempts = attempts; self.nextAttemptAt = nextAttemptAt
    }
}

public struct RawInboxRecord: Sendable, Equatable {
    public var cursor: Int64
    public var kind: String, conversationID: String
    public var seq: Int64?
    public var epoch: Int64
    public var senderAccount: String, senderDevice: String
    public var payload: Data
    public var sentAt: Date
    public init(cursor: Int64, kind: String, conversationID: String, seq: Int64?, epoch: Int64,
                senderAccount: String, senderDevice: String, payload: Data, sentAt: Date) {
        self.cursor = cursor; self.kind = kind; self.conversationID = conversationID; self.seq = seq; self.epoch = epoch
        self.senderAccount = senderAccount; self.senderDevice = senderDevice; self.payload = payload; self.sentAt = sentAt
    }
}

public struct FolderRecord: Sendable, Equatable {
    public var id: String, name: String
    public var position: Int
    public init(id: String, name: String, position: Int) {
        self.id = id; self.name = name; self.position = position
    }
}

public struct CallRecord: Sendable, Equatable {
    public var id: String, chatID: String
    public var video: Bool, outgoing: Bool
    public var outcome: String
    public var startedAt: Date
    public var duration: Double
    public init(id: String, chatID: String, video: Bool, outgoing: Bool, outcome: String, startedAt: Date, duration: Double) {
        self.id = id; self.chatID = chatID; self.video = video; self.outgoing = outgoing
        self.outcome = outcome; self.startedAt = startedAt; self.duration = duration
    }
}
