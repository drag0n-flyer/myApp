import AppCore
import CallCore
import Foundation

/// The call sealer the app hands to the call engine and the VoIP push resolver before the live chat store
/// exists. Until `use(_:)` it fails closed (`UnavailableCallSignalSealer`); at sign-out `clear()` returns to that.
final class SwitchableCallSealer: SenderRecoveringSealer, @unchecked Sendable {
    private let lock = NSLock()
    private var current: any SenderRecoveringSealer = UnavailableCallSignalSealer()

    func use(_ sealer: any SenderRecoveringSealer) { lock.withLock { current = sealer } }
    func clear() { lock.withLock { current = UnavailableCallSignalSealer() } }
    private var sealer: any SenderRecoveringSealer { lock.withLock { current } }

    func seal(_ payload: CallSignalPayload, to contactID: String) async throws -> Data {
        try await sealer.seal(payload, to: contactID)
    }
    func open(_ data: Data, from contactID: String) async throws -> CallSignalPayload {
        try await sealer.open(data, from: contactID)
    }
    func openRecoveringSender(_ data: Data) async throws -> (payload: CallSignalPayload, senderAccountID: String) {
        try await sealer.openRecoveringSender(data)
    }
}
