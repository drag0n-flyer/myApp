import Foundation
import AppCore

/// Test-only sealer: JSON-encodes the payload; the sender identity (`"<accountID>/<deviceID>"`) is looked up in `senderOf`.
public final class PlainCallSignalSealer: CallSignalSealer, @unchecked Sendable {
    private let lock = NSLock()
    private var senders: [Data: String]
    public init(senderOf: [Data: String] = [:]) { senders = senderOf }

    /// `identity` is `"<accountID>/<deviceID>"`, as in an MLS credential.
    public func setSender(_ identity: String, of data: Data) {
        lock.withLock { senders[data] = identity }
    }
    func identity(of data: Data) -> String? { lock.withLock { senders[data] } }
    /// Splits `"<accountID>/<deviceID>"`; `wrongSender` when either part is missing.
    static func parse(_ identity: String) throws -> (account: String, device: String) {
        let parts = identity.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, !parts[1].isEmpty else { throw CallSealError.wrongSender }
        return (String(parts[0]), String(parts[1]))
    }
    public func seal(_ payload: CallSignalPayload, to contactID: String) async throws -> Data {
        try JSONEncoder().encode(payload)
    }
    public func open(_ data: Data, from contactID: String) async throws -> (payload: CallSignalPayload, senderDeviceID: String) {
        guard let identity = identity(of: data) else { throw CallSealError.wrongSender }
        let sender = try Self.parse(identity)
        guard sender.account == contactID else { throw CallSealError.wrongSender }
        guard let p = try? JSONDecoder().decode(CallSignalPayload.self, from: data) else {
            throw CallSealError.undecryptable
        }
        return (p, sender.device)
    }
}
