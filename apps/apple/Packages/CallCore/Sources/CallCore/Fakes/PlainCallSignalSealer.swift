import Foundation
import AppCore

/// Test-only sealer: JSON-encodes the payload; the sender is looked up in `senderOf`.
public final class PlainCallSignalSealer: CallSignalSealer, @unchecked Sendable {
    private let lock = NSLock()
    private var senders: [Data: String]
    public init(senderOf: [Data: String] = [:]) { senders = senderOf }

    public func setSender(_ id: String, of data: Data) {
        lock.withLock { senders[data] = id }
    }
    public func seal(_ payload: CallSignalPayload, to contactID: String) async throws -> Data {
        try JSONEncoder().encode(payload)
    }
    public func open(_ data: Data, from contactID: String) async throws -> CallSignalPayload {
        let sender = lock.withLock { senders[data] }
        guard sender == contactID else { throw CallSealError.wrongSender }
        guard let p = try? JSONDecoder().decode(CallSignalPayload.self, from: data) else {
            throw CallSealError.undecryptable
        }
        return p
    }
}
