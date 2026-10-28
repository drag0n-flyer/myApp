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

    /// Decrypts `data` and returns the payload with the sender's device from the MLS credential. Throws
    /// `CallSealError.wrongSender` unless the MLS sender's account is `contactID` and the identity names a device.
    nonisolated public func open(
        _ data: Data, from contactID: String
    ) async throws -> (payload: CallSignalPayload, senderDeviceID: String) {
        let opened = try await openRecoveringSender(data)
        guard opened.senderAccountID == contactID else { throw CallSealError.wrongSender }
        return (opened.payload, opened.senderDeviceID)
    }

    /// Splits an MLS credential identity `"<accountID>/<deviceID>"`. Throws `CallSealError.wrongSender` when
    /// either part is missing: a call is bound to one device, so a sender without one is refused.
    nonisolated static func callSender(_ identity: String) throws -> (account: String, device: String) {
        let parts = identity.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, !parts[1].isEmpty else { throw CallSealError.wrongSender }
        return (String(parts[0]), String(parts[1]))
    }

    /// Decrypts `data` and returns the payload with the sender's account and device IDs, taken from the decrypted
    /// MLS message (`"<accountID>/<deviceID>"`), never from anything outside the ciphertext. An identity without
    /// both parts is `CallSealError.wrongSender`; every other failure is `CallSealError.undecryptable`.
    ///
    /// The call channel accepts call payloads only. `mls.process` has already advanced the group by the time
    /// the result is known, so a commit, Welcome or other content that arrives here is not applied to the
    /// database here and its hash is not marked processed. It goes to the inbox pipeline instead
    /// (`InboxProcessor.adopt`, inside the lock section; then `syncOnce`), which stores it like any inbox item, and the call fails.
    /// A call payload's hash is marked processed, so the same bytes never open twice.
    ///
    /// TODO(M7, encryption core): an engine-level "application messages only" check before decrypting plugs in
    /// at the `mls.process(data)` call below. Then a non-application message is refused without touching group
    /// state and the hand-over to the inbox is no longer needed.
    nonisolated public func openRecoveringSender(
        _ data: Data
    ) async throws -> (payload: CallSignalPayload, senderAccountID: String, senderDeviceID: String) {
        let hash = PortableSHA256.hash(data)
        let repo = deps.repo
        let mls = deps.mls
        enum Opened { case call(CallSignalPayload, String), notACall, replay }
        let inbox = inbox
        let opened: Opened
        do {
            opened = try await deps.lock.withAsyncLock(timeout: Self.callLockTimeout) { () async throws -> Opened in
                if try repo.isProcessed(hash: hash) { return .replay }
                let incoming = try await mls.process(data)   // M7: application-only check goes before this call
                guard case .application(_, let sender, let plaintext, _) = incoming,
                      case .callSignal(let payload) = ContentEnvelope.decode(plaintext).body else {
                    // Handed over inside the same lock section as the `process` that produced it: a drain waiting
                    // on the lock must find it and never process the payload a second time.
                    await inbox.adopt(incoming, forPayloadHash: hash)
                    return .notACall
                }
                try repo.markProcessed(hash: hash)
                return .call(payload, sender)   // the full credential identity, split below
            }
        } catch { throw CallSealError.undecryptable }
        switch opened {
        case .call(let payload, let identity):
            let sender = try Self.callSender(identity)
            return (payload, sender.account, sender.device)
        case .notACall:
            _ = try? await inbox.syncOnce()
            throw CallSealError.undecryptable
        default:
            throw CallSealError.undecryptable
        }
    }
}
