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
    /// The call channel accepts call payloads only. `mls.process` has already advanced the group by the time
    /// the result is known, so a commit, Welcome or other content that arrives here is not applied to the
    /// database here and its hash is not marked processed. It goes to the inbox pipeline instead
    /// (`InboxProcessor.adopt`, then `syncOnce`), which stores it like any inbox item, and the call fails.
    /// A call payload's hash is marked processed, so the same bytes never open twice.
    ///
    /// TODO(M7, encryption core): an engine-level "application messages only" check before decrypting plugs in
    /// at the `mls.process(data)` call below. Then a non-application message is refused without touching group
    /// state and the hand-over to the inbox is no longer needed.
    nonisolated public func openRecoveringSender(
        _ data: Data
    ) async throws -> (payload: CallSignalPayload, senderAccountID: String) {
        let hash = PortableSHA256.hash(data)
        let repo = deps.repo
        let mls = deps.mls
        enum Opened { case call(CallSignalPayload, String), notACall(Incoming), replay }
        let opened: Opened
        do {
            opened = try await deps.lock.withAsyncLock(timeout: Self.callLockTimeout) { () async throws -> Opened in
                if try repo.isProcessed(hash: hash) { return .replay }
                let incoming = try await mls.process(data)   // M7: application-only check goes before this call
                guard case .application(_, let sender, let plaintext, _) = incoming,
                      case .callSignal(let payload) = ContentEnvelope.decode(plaintext).body else { return .notACall(incoming) }
                try repo.markProcessed(hash: hash)
                return .call(payload, String(sender.split(separator: "/", maxSplits: 1).first ?? ""))
            }
        } catch { throw CallSealError.undecryptable }
        switch opened {
        case .call(let payload, let account) where !account.isEmpty:
            return (payload, account)
        case .notACall(let incoming):
            await inbox.adopt(incoming, forPayloadHash: hash)
            _ = try? await inbox.syncOnce()
            throw CallSealError.undecryptable
        default:
            throw CallSealError.undecryptable
        }
    }
}
