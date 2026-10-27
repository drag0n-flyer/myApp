import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
#if canImport(os)
import os
#endif

/// Sends content that is not stored locally (receipts). Lane A's inbox processor uses it.
public protocol ControlSender: Sendable {
    func sendControl(_ body: ContentEnvelope.Body, chatID: String) async
}

/// The send queue (Design 3, 6, 18): one outbox row per stored message, retried with backoff.
/// Every attempt encrypts afresh and posts with the envelope ID as `clientMsgID`, so a retry is
/// idempotent on the server. Never logs message content, only IDs.
public actor Outbox: ControlSender {
    private let deps: MessagingDeps
    private let conversations: ConversationManaging
    private var flushing = false
    private var cachedEscrowKey: Data?

    public init(deps: MessagingDeps, conversations: ConversationManaging) {
        self.deps = deps
        self.conversations = conversations
    }

    // MARK: - Public

    /// Stores the message as `sending` and adds its outbox row, in one transaction.
    public func enqueue(_ envelope: ContentEnvelope, chatID: String) throws {
        let now = deps.now()
        let data = envelope.encoded()
        var text = ""
        var reply: String?
        switch envelope.body {
        case .text(let t, let r): text = t; reply = r
        case .photo(_, _, _, _, let r), .voice(_, _, let r): reply = r
        case .file(_, let name, _, let r): text = name; reply = r
        default: break
        }
        let message = MessageRecord(
            id: envelope.id, chatID: chatID, conversationID: nil, senderAccount: deps.me.accountID,
            senderDevice: deps.me.deviceID, sentAt: Date(timeIntervalSince1970: Double(envelope.at) / 1000),
            receivedAt: now, envelope: data, status: "sending", replyTo: reply, editedAt: nil, deleted: false,
            undecryptable: false, folded: fold(text))
        try deps.repo.enqueueSend(
            message: message,
            outbox: OutboxRecord(messageID: envelope.id, chatID: chatID, envelope: data, attempts: 0, nextAttemptAt: now))
    }

    /// Tries every due item once (plus one immediate retry after a stale epoch or a mode refresh).
    public func flush() async {
        guard !flushing else { return }
        flushing = true
        defer { flushing = false }
        let due = (try? deps.repo.dueOutbox(at: deps.now())) ?? []
        var blockedChats: Set<String> = []   // keeps the order of a chat's messages
        for item in due where !blockedChats.contains(item.chatID) {
            let envelope = ContentEnvelope.decode(item.envelope)
            switch await attempt(envelope, chatID: item.chatID) {
            case .sent:
                try? deps.repo.updateStatus(ids: [item.messageID], to: "sent")
                try? deps.repo.removeOutbox(id: item.messageID)
            case .failed:
                try? deps.repo.updateStatus(ids: [item.messageID], to: "failed")
                try? deps.repo.removeOutbox(id: item.messageID)
            case .waiting:
                blockedChats.insert(item.chatID)
            case .retry:
                let delay = min(pow(2, Double(item.attempts)), 300)
                try? deps.repo.bumpOutbox(id: item.messageID, nextAttempt: deps.now().addingTimeInterval(delay))
                blockedChats.insert(item.chatID)
            }
        }
    }

    /// Not stored, sent immediately, best effort.
    public func sendControl(_ body: ContentEnvelope.Body, chatID: String) async {
        let envelope = ContentEnvelope(
            id: UUID().uuidString.lowercased(), at: Int64((deps.now().timeIntervalSince1970 * 1000).rounded()), body: body)
        _ = await attempt(envelope, chatID: chatID)
    }

    // MARK: - Sending

    private enum Outcome { case sent, failed, waiting, retry }

    private enum Plan {
        case waiting
        case fail
        case send(escrow: Bool, mismatch: Bool)
    }

    /// Design 18: `inner_mode` x server `mode`.
    private static func plan(inner: String?, server: String) -> Plan {
        switch (inner, server) {
        case ("server_readable"?, "server_readable"): return .send(escrow: true, mismatch: false)
        case ("e2ee"?, "e2ee"): return .send(escrow: false, mismatch: false)
        case ("e2ee"?, _): return .fail
        case ("server_readable"?, _): return .send(escrow: false, mismatch: true)
        case (nil, "e2ee"): return .send(escrow: false, mismatch: false)
        default: return .waiting
        }
    }

    private func attempt(_ envelope: ContentEnvelope, chatID: String) async -> Outcome {
        var conversation: ConversationRecord
        do { conversation = try await conversations.preferredConversation(with: chatID) } catch { return .retry }
        var staleRetried = false
        var modeRetried = false
        while true {
            let escrowWanted: Bool
            switch Self.plan(inner: conversation.innerMode, server: conversation.mode) {
            case .waiting:
                return .waiting
            case .fail:
                noteMismatch(conversation)
                return .failed
            case .send(let escrow, let mismatch):
                if mismatch { noteMismatch(conversation) }
                escrowWanted = escrow
            }

            let ciphertext: Data
            let epoch: UInt64
            var escrowCopy: Data?
            do {
                (ciphertext, epoch) = try await deps.mls.encrypt(envelope.encoded(), in: GroupID(raw: conversation.groupID))
                if escrowWanted {
                    guard let key = try await escrowKey() else { return .retry }
                    escrowCopy = try deps.escrow.seal(try escrowPlaintext(envelope, conversation: conversation), to: key)
                }
            } catch is EscrowError {
                return .failed
            } catch {
                return .retry
            }

            do {
                _ = try await deps.server.postMessage(
                    conversationID: conversation.serverID, epoch: epoch, ciphertext: ciphertext, escrow: escrowCopy,
                    clientMsgID: envelope.id)
                return .sent
            } catch {
                switch error.code {
                case "stale_epoch" where !staleRetried:
                    staleRetried = true
                    do {
                        try await conversations.handleStaleEpoch(conversation)
                        conversation = try deps.repo.conversation(serverID: conversation.serverID) ?? conversation
                    } catch { return .retry }
                case "stale_epoch":
                    return .retry
                case "escrow_required", "escrow_forbidden":
                    guard !modeRetried else {
                        noteMismatch(conversation)
                        return .failed
                    }
                    modeRetried = true
                    do {
                        let fresh = try await deps.server.conversation(id: conversation.serverID)
                        try deps.repo.setServerMode(serverID: conversation.serverID, fresh.mode)
                        conversation = try deps.repo.conversation(serverID: conversation.serverID) ?? conversation
                    } catch { return .retry }
                default:
                    let transient = error.status == 0 || error.status >= 500 || error.status == 429 || error.status == 401
                    return transient ? .retry : .failed
                }
            }
        }
    }

    private func escrowKey() async throws -> Data? {
        if let cachedEscrowKey { return cachedEscrowKey }
        let info = try await deps.server.serverInfo()
        guard let encoded = info.encryption.escrowKey, let key = Base64URLData(encoded: encoded)?.data else { return nil }
        cachedEscrowKey = key
        return key
    }

    private struct EscrowPlaintext: Encodable {
        let conversationID: String
        let senderAccountID: String
        let sentAt: Int64
        let body: ContentEnvelope
    }

    /// Design 10: `{"conversationID","senderAccountID","sentAt","body":<envelope>}`; `sentAt` is the envelope's `at`.
    private func escrowPlaintext(_ envelope: ContentEnvelope, conversation: ConversationRecord) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(EscrowPlaintext(
            conversationID: conversation.serverID, senderAccountID: deps.me.accountID, sentAt: envelope.at, body: envelope))
    }

    private func noteMismatch(_ conversation: ConversationRecord) {
        guard !conversation.modeMismatch else { return }
        try? deps.repo.flagModeMismatch(serverID: conversation.serverID)
        #if canImport(os)
        Logger(subsystem: "org.example.app", category: "outbox").error("mode_mismatch conversation=\(conversation.serverID, privacy: .public)")
        #else
        FileHandle.standardError.write(Data("mode_mismatch conversation=\(conversation.serverID)\n".utf8))
        #endif
    }
}
