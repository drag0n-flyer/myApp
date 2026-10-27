#if os(iOS)
import Foundation
import PushKit
import CallKit
import AppCore
import CallCore

/// What the push center learned synchronously about a push. Opening the sealed payload and reading the call
/// status must not block the push callback: the owner supplies what it already has (for example a
/// payload opened by the message extension, or the cached `call.status`), and `.undecryptable` otherwise.
struct VoIPPushResolution {
    var payload: PushPayloadState
    var isStranger: Bool
    var status: CallStatus?
}

@MainActor
protocol VoIPPushHandling: AnyObject {
    /// Synchronous. `sealed` is the unpadded payload; empty or nil means the server sent an empty push.
    func resolve(sealed: Data?) -> VoIPPushResolution
    /// After the call was reported as ringing: the engine takes over (connect, `call.status`, answer).
    func voipPushDidReportRing(callID: UUID, contact: Contact, ring: CallSignalPayload)
}

/// The one `PKPushRegistry` for the app's lifetime. Every VoIP push is reported to CallKit before the
/// PushKit completion handler runs, with no async work in between.
@MainActor
final class VoIPPushCenter: NSObject, PKPushRegistryDelegate {
    private let callKit: CallKitController
    private let onToken: @Sendable (Data) -> Void
    weak var handler: VoIPPushHandling?
    private var registry: PKPushRegistry?

    /// `onToken` hands the VoIP token to the push registrar (kind `voip`).
    init(callKit: CallKitController, onToken: @escaping @Sendable (Data) -> Void) {
        self.callKit = callKit
        self.onToken = onToken
        super.init()
    }

    func start() {
        guard registry == nil else { return }
        let r = PKPushRegistry(queue: .main)
        r.delegate = self
        r.desiredPushTypes = [.voIP]
        registry = r
    }

    // MARK: PKPushRegistryDelegate

    nonisolated func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials,
                                  for type: PKPushType) {
        let token = pushCredentials.token
        MainActor.assumeIsolated { onToken(token) }
    }

    nonisolated func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {}

    nonisolated func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload,
                                  for type: PKPushType, completion: @escaping () -> Void) {
        // PKPushPayload is not Sendable; read the one field here, on the main queue.
        let d = payload.dictionaryPayload["d"] as? String
        nonisolated(unsafe) let done = completion
        MainActor.assumeIsolated {
            handle(d: d, mustReport: nil, completion: done)
        }
    }

    // iOS 26.4+: the metadata variant (`PKVoIPPushMetadata.mustReport`) is NOT implemented yet. Its selector
    // must be copied from the iOS 26.4 SDK header on a macOS machine. Until then the plain method above runs
    // with `mustReport == nil`, which reports every push (always safe). When added, it only needs to call
    // `handle(d:mustReport:completion:)` with the metadata value.

    // MARK: Decision and report (synchronous)

    func handle(d: String?, mustReport: Bool?, completion: @escaping () -> Void) {
        let sealed = VoIPPushBody.payload(d: d)
        let resolution = handler?.resolve(sealed: sealed)
            ?? VoIPPushResolution(payload: (sealed?.isEmpty ?? true) ? .empty : .undecryptable,
                                  isStranger: false, status: nil)
        let action = VoIPPushDecision.decide(mustReport: mustReport, payload: resolution.payload,
                                             isStranger: resolution.isStranger, status: resolution.status)
        switch action {
        case .ignore:
            completion()
        case let .reportRing(name, video):
            let (callID, handle, contact, ring) = Self.identity(of: resolution.payload)
            callKit.reportIncoming(callID: callID, name: name, handle: handle, video: video) { _ in
                completion()
            }
            if let contact, let ring { self.handler?.voipPushDidReportRing(callID: callID, contact: contact, ring: ring) }
        case let .reportThenEnd(name, reason):
            let (callID, handle, _, _) = Self.identity(of: resolution.payload)
            let kit = callKit
            callKit.reportIncoming(callID: callID, name: name, handle: handle, video: false) { _ in
                kit.reportEnded(callID: callID, reason: CXCallEndedReason(reason))
                completion()
            }
        }
    }

    private static func identity(of payload: PushPayloadState) -> (UUID, String, Contact?, CallSignalPayload?) {
        if case let .ring(ring, contact) = payload {
            return (UUID(uuidString: ring.callID) ?? UUID(), contact.id, contact, ring)
        }
        return (UUID(), "unknown", nil, nil)
    }
}
#endif
