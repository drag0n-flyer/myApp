import AppCore
import Foundation
import LocalStore

/// Database records to the AppCore types the UI shows. Pure: it reads nothing but its arguments.
enum ChatMapping {
    /// Rows written by `recordCall` use this ID prefix; their envelope is the JSON of a `CallSummary`.
    static let callPrefix = "call-"
    /// `messages.sender_account` of device and safety lines.
    static let systemSender = "system"

    static func status(_ raw: String) -> DeliveryStatus {
        switch raw {
        case "delivered": .delivered
        case "read": .read
        case "sent": .sent
        case "failed": .failed
        default: .sending
        }
    }

    static func mediaState(_ r: AttachmentRecord) -> MediaState {
        let phase: MediaState.Phase
        switch r.state {
        case "local": phase = r.localPath.map { .local(URL(fileURLWithPath: $0)) } ?? .failed
        case "downloading": phase = .downloading(r.cipherSize > 0 ? min(Double(r.downloaded) / Double(r.cipherSize), 1) : 0)
        case "failed": phase = .failed
        default: phase = .remote
        }
        return MediaState(attachmentID: r.id, phase: phase)
    }

    /// - Parameters:
    ///   - displayName: resolves an account ID to a name for system lines; `nil` gives "Someone".
    static func message(
        _ record: MessageRecord, attachment: AttachmentRecord?, reactions: [ReactionRecord],
        displayName: (String) -> String?
    ) -> Message {
        var message = Message(
            id: record.id, senderID: record.senderAccount, sentAt: record.sentAt, content: .unsupported,
            status: status(record.status), replyToID: record.replyTo,
            reactions: reactions.map(\.emoji), editedAt: record.editedAt, media: nil, undecryptable: record.undecryptable)

        if record.deleted {
            message.content = .deleted
            message.reactions = []
            return message
        }
        if record.undecryptable { return message }

        if record.senderAccount == systemSender {
            if let event = try? JSONDecoder().decode(SystemEvent.self, from: record.envelope) {
                message.content = .system(resolve(event, displayName: displayName))
            }
            return message
        }
        if record.id.hasPrefix(callPrefix) {
            if let summary = try? JSONDecoder().decode(CallSummary.self, from: record.envelope) { message.content = .call(summary) }
            return message
        }

        switch ContentEnvelope.decode(record.envelope).body {
        case .text(let text, _): message.content = .text(text)
        case .photo(let att, _, _, _, _): message.content = .photo(bytes: att.sz)
        case .voice(_, let duration, _): message.content = .voice(duration: duration)
        case .file(let att, let name, _, _): message.content = .file(name: name, bytes: att.sz)
        default: break  // control kinds are never stored as messages; unknown kinds stay `.unsupported`
        }
        switch message.content {
        case .photo, .voice, .file: message.media = attachment.map(mediaState)
        default: break
        }
        return message
    }

    /// The `name` of a stored system line is an account ID (`nil` = "You"); it becomes a display name here.
    static func resolve(_ event: SystemEvent, displayName: (String) -> String?) -> SystemEvent {
        func name(_ account: String?) -> String? {
            guard let account else { return nil }
            return displayName(account) ?? "Someone"
        }
        switch event {
        case .deviceAdded(let account): return .deviceAdded(name: name(account))
        case .deviceRemoved(let account): return .deviceRemoved(name: name(account))
        case .safetyNumberChanged, .messagesPaused: return event
        }
    }

    /// `Chat.encryption` follows the pinned `inner_mode` of the preferred conversation only; the server's
    /// `mode` is never trusted for it (Design 18).
    static func encryption(preferred: ConversationRecord?) -> ChatEncryption {
        preferred?.innerMode == "server_readable" ? .serverReadable : .endToEnd
    }

    static func chat(
        _ record: ChatRecord, conversations: [ConversationRecord], preferred: ConversationRecord?, folderIDs: Set<String>,
        messages: [Message]
    ) -> Chat {
        Chat(
            id: record.id, contactID: record.id, encryption: encryption(preferred: preferred), folderIDs: folderIDs,
            unreadCount: record.unread, messages: messages, isRequest: record.isRequest,
            modeMismatch: conversations.contains { $0.modeMismatch })
    }

    /// A stored `current` number (12 groups of 5 digits, joined) split into its groups.
    static func groups(_ number: String) -> [String] {
        let chars = Array(number)
        return stride(from: 0, to: chars.count, by: 5).map { String(chars[$0..<min($0 + 5, chars.count)]) }
    }
}
