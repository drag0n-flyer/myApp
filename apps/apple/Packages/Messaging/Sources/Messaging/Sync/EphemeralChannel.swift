import Foundation
import LocalStore
import ServerKit

/// Sends a `conv.ephemeral` frame. `LiveConnection` is the real one.
public protocol EphemeralSending: Sendable {
    func sendEphemeral(conversationID: String, epoch: UInt64, ciphertext: Data) async throws
}

extension LiveConnection: EphemeralSending {}

public enum EphemeralError: Error, Equatable, Sendable {
    /// Only `typing` and `presence` may travel as a signal.
    case refused
    /// The chat has no conversation yet.
    case noConversation
}

/// A typing or presence signal from the other person in a chat.
public struct EphemeralSignal: Sendable, Equatable {
    public var chatID: String
    public var accountID: String
    public var body: ContentEnvelope.Body

    public init(chatID: String, accountID: String, body: ContentEnvelope.Body) {
        self.chatID = chatID
        self.accountID = accountID
        self.body = body
    }
}

/// Typing and presence signals. They do not use MLS: the envelope's JSON goes in the frame's `ciphertext` field as it
/// is (the transport is TLS to the server, and the server relays it without storing it). Nothing is stored here and
/// the MLS engine is never called, so no ratchet moves. Never logs.
public final class EphemeralChannel: Sendable {
    /// The most bytes a received signal may have.
    public static let maxPayload = 256

    public let events: AsyncStream<EphemeralSignal>
    private let continuation: AsyncStream<EphemeralSignal>.Continuation
    private let repo: Repository
    private let me: String
    private let sender: any EphemeralSending

    public init(repo: Repository, me: String, sender: any EphemeralSending) {
        self.repo = repo
        self.me = me
        self.sender = sender
        (events, continuation) = AsyncStream.makeStream()
    }

    /// Sends `.typing` or `.presence` to the chat's current conversation. Throws `EphemeralError.refused` for any other
    /// body and `.noConversation` when the chat has none; a failed send throws what the transport threw.
    public func send(chatID: String, body: ContentEnvelope.Body) async throws {
        switch body {
        case .typing, .presence: break
        default: throw EphemeralError.refused
        }
        guard let conversation = try repo.preferredConversation(chatID: chatID) else { throw EphemeralError.noConversation }
        let envelope = ContentEnvelope(id: UUID().uuidString.lowercased(), at: 0, body: body)
        try await sender.sendEphemeral(conversationID: conversation.serverID, epoch: UInt64(max(conversation.epoch, 0)), ciphertext: envelope.encoded())
    }

    /// Feed every server event here; only `conversationEphemeral` is used. A frame is dropped silently when the
    /// conversation is unknown, it is not from the other person of that chat (or is from this account or a blocked
    /// one), the payload is over 256 bytes or is not exactly a valid `typing` or `presence`. `epoch` is ignored.
    public func ingest(_ event: ServerEvent) {
        guard case .conversationEphemeral(let conversationID, _, let from, _, let payload) = event,
              payload.count <= Self.maxPayload, from != me,
              let conversation = (try? repo.conversation(serverID: conversationID)) ?? nil,
              conversation.chatID == from,
              (try? repo.isBlocked(from)) == false else { return }
        let body = ContentEnvelope.decode(payload).body
        switch body {
        case .typing, .presence: continuation.yield(EphemeralSignal(chatID: conversation.chatID, accountID: from, body: body))
        default: return
        }
    }

    /// Ends `events`.
    public func finish() { continuation.finish() }
}
