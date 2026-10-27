import AppCore
import Foundation
import LocalStore
import SecureMessagingCore

/// What the notification extension shows for one push.
public enum NotificationOutcome: Equatable, Sendable {
    case show(title: String, body: String, threadID: String)
    /// Only honored when `NSE_FILTERING` is on; otherwise the extension shows `fallback`.
    case drop
    /// The generic "[App]" / "New message".
    case fallback
    /// Plan 8's CALL branch decides what to show.
    case incomingCall(CallSignalPayload, from: Contact)
}

/// The result of applying one processed MLS message. Lane A's `IncomingHandler` produces these
/// (its `AppliedResult`); this is the narrowest shape the notification processor needs.
public enum AppliedIncoming: Sendable, Equatable {
    case stored(MessageRecord)
    case callSignal(CallSignalPayload, sender: String)
    case ownDeviceAdded(deviceIDs: [String])
    case ignored
}

/// Lane A's `IncomingHandler` conforms (see the D2 report for the merge note).
public protocol IncomingApplying: Sendable {
    func apply(_ incoming: Incoming, raw: RawInboxRecord, db: Repository) throws -> AppliedIncoming
}

/// Lane A's `InboxProcessor` conforms.
public protocol InboxSyncing: Sendable {
    func syncOnce(maxItems: Int) async throws -> Int
}

/// Every user-visible string the processor produces. The extension builds this from its String
/// Catalog; the defaults are the English text.
public struct NotificationStrings: Sendable, Equatable {
    public var requestTitle: String
    public var requestBody: String
    public var reactedFormat: String        // "Reacted %@"
    public var newDeviceTitle: String
    public var newDeviceBodyFormat: String  // "%@ can now read ..."
    public var unknownDevice: String
    public var unknownCaller: String
    public var photo: String
    public var voiceMessage: String
    public var file: String
    public var unsupported: String

    public init(brand: String = "[App]") {
        requestTitle = "Message request"
        requestBody = "Open \(brand) to see it"
        reactedFormat = "Reacted %@"
        newDeviceTitle = "New device added"
        newDeviceBodyFormat = "%@ can now read your new messages. If this wasn't you, remove it in Settings > Linked devices."
        unknownDevice = "A new device"
        unknownCaller = "Unknown"
        photo = "Photo"
        voiceMessage = "Voice message"
        file = "File"
        unsupported = "Update \(brand) to see this message"
    }
}

/// Turns one push into an outcome: stores the message so the app never processes it again.
/// Never logs payloads, message text or names.
public struct NotificationProcessor: Sendable {
    /// The thread ID for the "New device added" notice.
    public static let devicesThreadID = "devices"
    static let lockTimeout: Duration = .seconds(2)
    static let previewLimit = 140

    private let deps: MessagingDeps
    private let handler: any IncomingApplying
    private let inbox: any InboxSyncing
    private let conversations: ConversationManaging
    private let strings: NotificationStrings

    public init(
        deps: MessagingDeps, handler: any IncomingApplying, inbox: any InboxSyncing,
        conversations: ConversationManaging, strings: NotificationStrings = NotificationStrings()
    ) {
        self.deps = deps
        self.handler = handler
        self.inbox = inbox
        self.conversations = conversations
        self.strings = strings
    }

    public func process(pushData: Data, deadline: Date) async -> NotificationOutcome {
        guard deps.now() < deadline else { return .fallback }
        do {
            let outcome = try await run(pushData: pushData)
            // Past the deadline the state is stored, but the extension has no time to show more.
            return deps.now() < deadline ? outcome : .fallback
        } catch {
            return .fallback
        }
    }

    private struct LeaveForApp: Error {}

    /// The push body is only the padded inbox payload: it has no `kind` and no conversation ID
    /// (server `fanout.go` passes `item.Payload` as `PushPayload`). The server's `kind` is known
    /// only for inbox items already fetched into `inbox_raw` (same payload bytes), so look it up
    /// there and trust it. Only when the item has not been fetched yet (the usual first push)
    /// fall back to sniffing the bytes: MLSMessage framing (version 1, wire format 3), or the
    /// fake engine's `{"fake":"welcome"}`.
    private func isWelcome(_ payload: Data) throws -> Bool {
        if let known = try deps.repo.rawItems(limit: 500).first(where: { $0.payload == payload }) {
            return known.kind == "welcome"
        }
        return Self.sniffsAsWelcome(payload)
    }

    static func sniffsAsWelcome(_ payload: Data) -> Bool {
        let b = [UInt8](payload.prefix(4))
        if b == [0, 1, 0, 3] { return true }
        guard let object = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] else { return false }
        return object["fake"] as? String == "welcome"
    }

    // MARK: - Steps

    private func run(pushData: Data) async throws -> NotificationOutcome {
        let payload = try Padding.unpad(pushData)
        if payload.isEmpty { return try await syncAndShowNewest() }

        // A Welcome needs the inbox item's server conversation ID to register the conversation,
        // and the push does not carry it. Leave it for the full sync (never mark it processed here).
        if try isWelcome(payload) { return try await syncAndShowNewest() }

        let hash = PortableSHA256.hash(payload)
        if try deps.repo.isProcessed(hash: hash) { return .drop }

        let applied: (Incoming, AppliedIncoming)? = try await withProcessLock(deps.lock, timeout: Self.lockTimeout) {
            // Re-check under the lock: the app may have processed it while we waited.
            if try deps.repo.isProcessed(hash: hash) { return nil }
            let incoming = try await deps.mls.process(payload)
            // No local conversation row: the app has not joined this group yet. Do not consume it.
            if case .application(let group, _, _, _) = incoming,
               try deps.repo.conversation(groupID: group.raw) == nil {
                throw LeaveForApp()
            }
            let result = try handler.apply(incoming, raw: rawRecord(for: incoming, payload: payload), db: deps.repo)
            try deps.repo.markProcessed(hash: hash)
            return (incoming, result) as (Incoming, AppliedIncoming)?
        }
        guard let (incoming, result) = applied else { return .drop }
        return try decide(incoming: incoming, result: result)
    }

    private func syncAndShowNewest() async throws -> NotificationOutcome {
        let started = deps.now()
        _ = try await inbox.syncOnce(maxItems: 20)
        var newest: MessageRecord?
        for chat in try deps.repo.chats() {
            let recent = try deps.repo.messages(chatID: chat.id, before: nil, limit: 5).filter {
                $0.senderAccount != "system" && $0.senderAccount != deps.me.accountID && !$0.deleted
            }
            guard let last = recent.max(by: { $0.sentAt < $1.sentAt }) else { continue }
            if last.receivedAt >= started, newest == nil || last.receivedAt > newest!.receivedAt { newest = last }
        }
        guard let newest else { return .fallback }
        return try outcome(forStored: newest)
    }

    private func rawRecord(for incoming: Incoming, payload: Data) throws -> RawInboxRecord {
        let kind: String, group: GroupID, epoch: UInt64, sender: String
        switch incoming {
        case .application(let g, let s, _, let e): kind = "message"; group = g; epoch = e; sender = s
        case .commit(let g, let e, _): kind = "commit"; group = g; epoch = e; sender = ""
        case .joined(let g, let e): kind = "welcome"; group = g; epoch = e; sender = ""
        }
        let parts = sender.split(separator: "/", maxSplits: 1).map(String.init)
        return RawInboxRecord(
            cursor: 0, kind: kind, conversationID: try deps.repo.conversation(groupID: group.raw)?.serverID ?? "",
            seq: nil, epoch: Int64(epoch), senderAccount: parts.first ?? "", senderDevice: parts.count > 1 ? parts[1] : "",
            payload: payload, sentAt: deps.now())
    }

    // MARK: - Decision

    private func decide(incoming: Incoming, result: AppliedIncoming) throws -> NotificationOutcome {
        switch result {
        case .stored(let message):
            return try outcome(forStored: message)
        case .callSignal(let payload, let sender):
            let account = Self.account(of: sender)
            let contact = try deps.repo.contact(id: account).map {
                Contact(id: $0.accountID, displayName: $0.displayName, username: $0.username)
            } ?? Contact(id: account, displayName: strings.unknownCaller, username: "")
            return .incomingCall(payload, from: contact)
        case .ownDeviceAdded(let ids):
            return try ownDeviceOutcome(ids)
        case .ignored:
            guard case .application(let group, let sender, let plaintext, _) = incoming,
                  case .react(_, let emoji) = ContentEnvelope.decode(plaintext).body, !emoji.isEmpty
            else { return .drop }
            let account = Self.account(of: sender)
            guard account != deps.me.accountID else { return .drop }
            let chatID = try deps.repo.conversation(groupID: group.raw)?.chatID ?? account
            guard try shouldNotify(chatID: chatID, sender: account) else { return .drop }
            let name = try deps.repo.contact(id: account).flatMap { $0.isContact ? $0.displayName : nil }
            guard let name else { return requestOutcome(chatID: chatID) }
            return .show(title: name, body: String(format: strings.reactedFormat, emoji), threadID: chatID)
        }
    }

    private func outcome(forStored message: MessageRecord) throws -> NotificationOutcome {
        guard message.senderAccount != deps.me.accountID, message.senderAccount != "system", !message.deleted
        else { return .drop }
        guard try shouldNotify(chatID: message.chatID, sender: message.senderAccount) else { return .drop }
        guard let contact = try deps.repo.contact(id: message.senderAccount), contact.isContact else {
            return requestOutcome(chatID: message.chatID)
        }
        return .show(title: contact.displayName, body: preview(message), threadID: message.chatID)
    }

    private func requestOutcome(chatID: String) -> NotificationOutcome {
        .show(title: strings.requestTitle, body: strings.requestBody, threadID: chatID)
    }

    /// False for a muted chat or a blocked sender.
    private func shouldNotify(chatID: String, sender: String) throws -> Bool {
        if try deps.repo.isBlocked(sender) { return false }
        if try deps.repo.chat(id: chatID)?.muted == true { return false }
        return true
    }

    private func ownDeviceOutcome(_ ids: [String]) throws -> NotificationOutcome {
        var fresh: [String] = []
        for id in ids where try deps.repo.get("notified.device.\(id)") == nil {
            try deps.repo.set("notified.device.\(id)", Data([1]))
            fresh.append(id)
        }
        guard !fresh.isEmpty else { return .drop }
        return .show(
            title: strings.newDeviceTitle, body: String(format: strings.newDeviceBodyFormat, strings.unknownDevice),
            threadID: Self.devicesThreadID)
    }

    private func preview(_ message: MessageRecord) -> String {
        switch ContentEnvelope.decode(message.envelope).body {
        case .text(let text, _): return String(text.prefix(Self.previewLimit))
        case .photo: return strings.photo
        case .voice: return strings.voiceMessage
        case .file(_, let name, _, _): return name.isEmpty ? strings.file : String(name.prefix(Self.previewLimit))
        default: return strings.unsupported
        }
    }

    private static func account(of identity: String) -> String {
        identity.split(separator: "/", maxSplits: 1).first.map(String.init) ?? identity
    }
}
