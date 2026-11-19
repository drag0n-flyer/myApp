import AppCore
import Foundation
import LocalStore

/// Database records to the AppCore types the UI shows. Pure: it reads nothing but its arguments.
enum ChatMapping {
    /// Rows written by `recordCall` use this ID prefix; their envelope is the JSON of a `CallSummary`.
    static let callPrefix = "call-"
    /// `messages.sender_account` of device and safety lines.
    static let systemSender = "system"

    /// - Parameter showRead: false while the Read receipts switch is off, for my own messages: a stored `read`
    ///   then shows as delivered, and shows as read again when the switch goes back on.
    static func status(_ raw: String, showRead: Bool = true) -> DeliveryStatus {
        switch raw {
        case "delivered": .delivered
        case "read": showRead ? .read : .delivered
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
    ///   - showRead: see `status(_:showRead:)`.
    static func message(
        _ record: MessageRecord, attachment: AttachmentRecord?, reactions: [ReactionRecord],
        displayName: (String) -> String?, showRead: Bool = true, me: String? = nil, pollVotes: [PollVoteRecord] = []
    ) -> Message {
        var message = Message(
            id: record.id, senderID: record.senderAccount, sentAt: record.sentAt, content: .unsupported,
            status: status(record.status, showRead: showRead), replyToID: record.replyTo,
            reactions: reactions.map(\.emoji), editedAt: record.editedAt, media: nil, undecryptable: record.undecryptable,
            expiresAt: record.expiresAt)

        if record.deleted {
            message.content = .deleted
            message.reactions = []
            return message
        }
        if record.undecryptable { return message }

        if record.senderAccount == systemSender {
            if let event = try? JSONDecoder().decode(SystemEvent.self, from: record.envelope) {
                message.content = .system(resolve(event, displayName: displayName, me: me))
            }
            return message
        }
        if record.id.hasPrefix(callPrefix) {
            if let summary = try? JSONDecoder().decode(CallSummary.self, from: record.envelope) { message.content = .call(summary) }
            return message
        }

        let envelope = ContentEnvelope.decode(record.envelope)
        switch envelope.body {
        case .text(let text, _):
            message.content = .text(text)
            message.linkPreview = envelope.preview.map { LinkPreviewCard(url: $0.url, title: $0.title, desc: $0.desc, image: $0.img) }
        case .poll(let poll): message.content = .poll(PollContent(poll: poll, votes: pollVotes.map { PollVote(accountID: $0.accountID, optionIDs: $0.optionIDs) }))
        case .photo(let att, _, _, _, _): message.content = .photo(bytes: att.sz)
        case .voice(_, let duration, _): message.content = .voice(duration: duration)
        case .file(let att, let name, _, _): message.content = .file(name: name, bytes: att.sz)
        default: break  // control kinds are never stored as messages; unknown kinds stay `.unsupported`
        }
        switch message.content {
        case .photo, .voice, .file: message.media = attachment.map(mediaState)
        default: break
        }
        switch message.content {
        case .photo, .file: message.caption = envelope.caption
        default: break
        }
        return message
    }

    /// The `name` of a stored system line is an account ID (`nil` = "You"); it becomes a display name here.
    static func resolve(_ event: SystemEvent, displayName: (String) -> String?, me: String? = nil) -> SystemEvent {
        func name(_ account: String?) -> String? {
            guard let account else { return nil }
            return displayName(account) ?? "Someone"
        }
        switch event {
        case .deviceAdded(let account): return .deviceAdded(name: name(account))
        case .deviceRemoved(let account): return .deviceRemoved(name: name(account))
        case .timer(let seconds, let account): return .timer(seconds: seconds, name: name(account))
        case .addedYou(let account): return .addedYou(by: name(account))
        case .group(let line): return .group(line.resolved(me: me) { name($0) ?? "Someone" })
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
        messages: [Message], draft: String? = nil
    ) -> Chat {
        Chat(
            id: record.id, contactID: record.kind == "group" ? nil : record.id, encryption: encryption(preferred: preferred), folderIDs: folderIDs,
            unreadCount: record.unread, messages: messages, isRequest: record.isRequest,
            modeMismatch: conversations.contains { $0.modeMismatch }, pinnedAt: record.pinnedAt, muted: record.muted,
            archived: record.archived, draft: draft, title: record.kind == "group" ? record.title : nil,
            myGroupState: GroupMyState(wire: record.myState))
    }

    /// A stored `current` number (12 groups of 5 digits, joined) split into its groups.
    static func groups(_ number: String) -> [String] {
        let chars = Array(number)
        return stride(from: 0, to: chars.count, by: 5).map { String(chars[$0..<min($0 + 5, chars.count)]) }
    }
}
