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
    /// Throws `OutboxError` when the message could not be sent (nothing is queued).
    func sendControl(_ body: ContentEnvelope.Body, chatID: String) async throws
}

/// Why a send did not happen.
public enum OutboxError: Error, Equatable, Sendable {
    /// A network or server problem; trying again later may work.
    case retryable
    /// `inner_mode` is not known yet and the server is server-readable (Design 18).
    case waitingForMode
    /// The conversation's pinned mode and the server's mode disagree so that sending must stop.
    case modeMismatch
    /// The server refused the message with this error code.
    case rejected(String)
    /// The escrow copy could not be made.
    case escrowFailed
}

/// The send queue (Design 3, 6, 18): one outbox row per stored message, retried with backoff.
/// Every attempt encrypts afresh and posts with the envelope ID as `clientMsgID`, so a retry is
/// idempotent on the server. Never logs message content, only IDs.
///
/// Public API for other lanes:
/// - `send(_:in:)`: the one escrow-aware send (Design 18 table, seal, POST, one stale-epoch retry, one
///   mode refresh). It throws `OutboxError` on any failure. Lane A's `announceMode` uses it.
/// - `sendControl(_:chatID:)`: a non-stored message through the preferred conversation; throws.
/// - `EscrowKeyProvider` and `Escrow.seal` / `Escrow.plaintext` are the shared sealing pieces.
public actor Outbox: ControlSender {
    private let deps: MessagingDeps
    private let conversations: ConversationManaging
    private let keys: EscrowKeyProvider
    private var flushing = false

    public init(deps: MessagingDeps, conversations: ConversationManaging, keys: EscrowKeyProvider? = nil) {
        self.deps = deps
        self.conversations = conversations
        self.keys = keys ?? EscrowKeyProvider(server: deps.server)
    }

    // MARK: - Queue

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

    /// Sends what is due. Only the oldest queued message of a chat is eligible, so a newer message never
    /// overtakes an older one that is waiting or backing off, in this flush or a later one. Throws when the
    /// queue cannot be read or a result cannot be recorded.
    public func flush() async throws {
        guard !flushing else { return }
        flushing = true
        defer { flushing = false }
        var blockedChats: Set<String> = []
        var progressed = true
        while progressed {
            progressed = false
            for item in try deps.repo.dueOutboxHeads(at: deps.now()) where !blockedChats.contains(item.chatID) {
                let envelope = ContentEnvelope.decode(item.envelope)
                switch await attempt(envelope, chatID: item.chatID) {
                case .sent:
                    try deps.repo.completeSend(id: item.messageID, status: "sent")
                    progressed = true
                case .failed:
                    try deps.repo.completeSend(id: item.messageID, status: "failed")
                    progressed = true
                case .waiting:
                    blockedChats.insert(item.chatID)
                case .retry:
                    let delay = min(pow(2, Double(item.attempts)), 300)
                    try deps.repo.bumpOutbox(id: item.messageID, nextAttempt: deps.now().addingTimeInterval(delay))
                    blockedChats.insert(item.chatID)
                }
            }
        }
    }

    /// Not stored, sent now through the chat's preferred conversation. Throws on failure.
    public func sendControl(_ body: ContentEnvelope.Body, chatID: String) async throws {
        let envelope = ContentEnvelope(
            id: UUID().uuidString.lowercased(), at: Int64((deps.now().timeIntervalSince1970 * 1000).rounded()), body: body)
        let conversation: ConversationRecord
        do { conversation = try await conversations.preferredConversation(with: chatID) } catch { throw OutboxError.retryable }
        try await send(envelope, in: conversation)
    }

    // MARK: - Sending

    private enum Outcome { case sent, failed, waiting, retry }

    private func attempt(_ envelope: ContentEnvelope, chatID: String) async -> Outcome {
        do {
            let conversation = try await conversations.preferredConversation(with: chatID)
            try await send(envelope, in: conversation)
            return .sent
        } catch let error as OutboxError {
            switch error {
            case .retryable: return .retry
            case .waitingForMode: return .waiting
            case .modeMismatch, .rejected, .escrowFailed: return .failed
            }
        } catch {
            return .retry
        }
    }

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

    /// Encrypts, adds the escrow copy when Design 18 says so, and posts. Throws `OutboxError`.
    /// The envelope ID is the `clientMsgID`, so calling it again for the same envelope is idempotent.
    public func send(_ envelope: ContentEnvelope, in start: ConversationRecord) async throws {
        var conversation = start
        var staleRetried = false
        var modeRetried = false
        while true {
            let escrowWanted: Bool
            switch Self.plan(inner: conversation.innerMode, server: conversation.mode) {
            case .waiting:
                throw OutboxError.waitingForMode
            case .fail:
                noteMismatch(conversation)
                throw OutboxError.modeMismatch
            case .send(let escrow, let mismatch):
                if mismatch { noteMismatch(conversation) }
                escrowWanted = escrow
            }

            let ciphertext: Data
            let epoch: UInt64
            var escrowCopy: Data?
            do {
                (ciphertext, epoch) = try await deps.mls.encrypt(envelope.encoded(), in: GroupID(raw: conversation.groupID))
            } catch {
                throw OutboxError.retryable
            }
            if escrowWanted {
                let key: Data
                do { key = try await keys.key() } catch is EscrowKeyError { throw OutboxError.escrowFailed } catch {
                    throw OutboxError.retryable
                }
                do {
                    escrowCopy = try deps.escrow.seal(
                        try Escrow.plaintext(for: envelope, conversationID: conversation.serverID,
                                             senderAccountID: deps.me.accountID),
                        to: key)
                } catch {
                    await keys.invalidate()   // a bad key may have been rotated; the next attempt refetches
                    throw OutboxError.escrowFailed
                }
            }

            do {
                _ = try await deps.server.postMessage(
                    conversationID: conversation.serverID, epoch: epoch, ciphertext: ciphertext, escrow: escrowCopy,
                    clientMsgID: envelope.id)
                return
            } catch {
                switch error.code {
                case "stale_epoch" where !staleRetried:
                    staleRetried = true
                    do {
                        try await conversations.handleStaleEpoch(conversation)
                        conversation = try deps.repo.conversation(serverID: conversation.serverID) ?? conversation
                    } catch { throw OutboxError.retryable }
                case "stale_epoch":
                    throw OutboxError.retryable
                case "escrow_required", "escrow_forbidden":
                    guard !modeRetried else {
                        noteMismatch(conversation)
                        throw OutboxError.rejected(error.code)
                    }
                    modeRetried = true
                    await keys.invalidate()
                    do {
                        let fresh = try await deps.server.conversation(id: conversation.serverID)
                        try deps.repo.setServerMode(serverID: conversation.serverID, fresh.mode)
                        conversation = try deps.repo.conversation(serverID: conversation.serverID) ?? conversation
                    } catch { throw OutboxError.retryable }
                default:
                    let transient = error.status == 0 || error.status >= 500 || error.status == 429 || error.status == 401
                    throw transient ? OutboxError.retryable : OutboxError.rejected(error.code)
                }
            }
        }
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
