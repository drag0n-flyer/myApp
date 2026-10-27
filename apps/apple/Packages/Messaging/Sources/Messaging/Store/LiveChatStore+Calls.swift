import AppCore
import Foundation
import LocalStore
import SecureMessagingCore

/// Call signal sealing (Design 16). Call signals ride in the 1:1 conversation's MLS group as a `callsig`
/// envelope; the plan 8 call engine moves the sealed bytes through its own transport. The app target
/// adds the `CallSignalSealer` conformance (`Messaging` never imports `CallCore`).
///
/// `recordCall` (local call history, Design 8) lives in `LiveChatStore.swift`.
extension LiveChatStore {
    static let callLockTimeout: Duration = .seconds(5)

    /// MLS-encrypts `payload` in the preferred conversation with `contactID`, creating it if there is none.
    /// Nothing is stored and nothing is posted to the server: the caller sends the returned bytes.
    nonisolated public func seal(_ payload: CallSignalPayload, to contactID: String) async throws -> Data {
        let conversation = try await conversations.preferredConversation(with: contactID)
        // A chat whose mode does not match what it started with is paused: nothing goes into it.
        guard !conversation.modeMismatch else { throw OutboxError.modeMismatch }
        let envelope = ContentEnvelope(
            id: UUID().uuidString.lowercased(), at: Int64((deps.now().timeIntervalSince1970 * 1000).rounded()),
            body: .callSignal(payload))
        let plaintext = envelope.encoded()
        let group = GroupID(raw: conversation.groupID)
        let mls = deps.mls
        return try await deps.lock.withAsyncLock(timeout: Self.callLockTimeout) {
            try await mls.encrypt(plaintext, in: group).ciphertext
        }
    }

    /// Decrypts `data`; throws `CallSealError.wrongSender` unless the MLS sender's account is `contactID`.
    nonisolated public func open(_ data: Data, from contactID: String) async throws -> CallSignalPayload {
        let opened = try await openRecoveringSender(data)
        guard opened.senderAccountID == contactID else { throw CallSealError.wrongSender }
        return opened.payload
    }

    /// Decrypts `data` and returns the payload with the sender's account ID, taken from the decrypted MLS
    /// message (`"<accountID>/<deviceID>"`, the part before the slash), never from anything outside the
    /// ciphertext. Every failure is `CallSealError.undecryptable`.
    ///
    /// Only an application message with a `callsig` body is accepted. The payload hash is marked processed,
    /// so the same bytes never open twice (and the notification extension never processes them again).
    nonisolated public func openRecoveringSender(
        _ data: Data
    ) async throws -> (payload: CallSignalPayload, senderAccountID: String) {
        let hash = PortableSHA256.hash(data)
        let repo = deps.repo
        let mls = deps.mls
        let result: (CallSignalPayload, String)?
        do {
            result = try await deps.lock.withAsyncLock(timeout: Self.callLockTimeout) { () async throws -> (CallSignalPayload, String)? in
                if try repo.isProcessed(hash: hash) { return nil }
                let incoming = try await mls.process(data)
                try repo.markProcessed(hash: hash)
                guard case .application(_, let sender, let plaintext, _) = incoming,
                      case .callSignal(let payload) = ContentEnvelope.decode(plaintext).body else { return nil }
                return (payload, String(sender.split(separator: "/", maxSplits: 1).first ?? ""))
            }
        } catch { throw CallSealError.undecryptable }
        guard let result, !result.1.isEmpty else { throw CallSealError.undecryptable }
        return (result.0, result.1)
    }
}
