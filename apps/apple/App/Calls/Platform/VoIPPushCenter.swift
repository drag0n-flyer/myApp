#if os(iOS)
import Foundation
import PushKit
import CallKit
import AppCore
import CallCore

@MainActor
protocol VoIPPushHandling: AnyObject {
    /// The opened payload for a push, shown on CallKit under `kitCallID`. The server call ID is
    /// `ring.callID`; the push carries no call ID, so the CallKit UUID is made at push time and the engine
    /// maps `kitCallID` to the server call ID. Called only for rings that stay ringing.
    func voipPushDidResolveRing(kitCallID: UUID, contact: Contact, ring: CallSignalPayload)
    /// The push ended up ending (stranger, call already over, or failed): record a missed call for a stranger.
    func voipPushDidEnd(kitCallID: UUID, reason: EndReason, resolution: VoIPPushResolution)
}

/// The one `PKPushRegistry` for the app's lifetime. The push carries only a sealed payload, so:
/// 1. synchronously, in the push callback, report a generic "Incoming call" to CallKit (before anything async);
/// 2. then open the payload, recover the sender, apply `VoIPPushDecision`, and update or end the call.
@MainActor
final class VoIPPushCenter: NSObject, PKPushRegistryDelegate {
    private let callKit: CallKitController
    private let resolver: VoIPPushResolver
    private let onToken: @Sendable (Data) -> Void
    weak var handler: VoIPPushHandling?
    private var registry: PKPushRegistry?

    /// `onToken` hands the VoIP token to the push registrar (kind `voip`).
    init(callKit: CallKitController, resolver: VoIPPushResolver, onToken: @escaping @Sendable (Data) -> Void) {
        self.callKit = callKit
        self.resolver = resolver
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
        MainActor.assumeIsolated { handle(d: d, mustReport: nil, completion: done) }
    }

    // iOS 26.4+: the metadata variant (`PKVoIPPushMetadata.mustReport`) is NOT implemented yet. Its selector
    // must be copied from the iOS 26.4 SDK header on a macOS machine. Until then the plain method above runs
    // with `mustReport == nil`, which reports every push (always safe). When added, it only needs to call
    // `handle(d:mustReport:completion:)` with the metadata value.

    // MARK: Stage 1 (synchronous) and stage 2 (asynchronous)

    func handle(d: String?, mustReport: Bool?, completion: @escaping () -> Void) {
        let kitCallID = UUID()
        let sealed = VoIPPushBody.payload(d: d)
        let stage1 = VoIPPushDecision.immediate(mustReport: mustReport)
        if stage1 == .reportGeneric {
            // Nothing async before this line. The handle is a placeholder: the push has no sender or call ID.
            callKit.reportIncoming(callID: kitCallID, name: String(localized: "Incoming call"),
                                   handle: kitCallID.uuidString, video: false) { _ in }
        }
        // PushKit's completion may run once the report is made; the rest continues on its own.
        completion()
        let reportedGeneric = stage1 == .reportGeneric
        Task { @MainActor [weak self] in
            await self?.resolveAndFinish(kitCallID: kitCallID, sealed: sealed, mustReport: mustReport,
                                         reportedGeneric: reportedGeneric)
        }
    }

    private func resolveAndFinish(kitCallID: UUID, sealed: Data?, mustReport: Bool?, reportedGeneric: Bool) async {
        let r = await resolver.resolve(sealed: sealed)
        let action = VoIPPushDecision.decide(mustReport: mustReport, payload: r.payload,
                                             isStranger: r.isStranger, status: r.status)
        let name: (String) -> String = { $0 == VoIPPushDecision.unknownCallerName ? String(localized: "Unknown") : $0 }
        switch VoIPPushDecision.followUp(reportedGeneric: reportedGeneric, action: action) {
        case let .updateCaller(callerName, video):
            guard case let .ring(ring, contact) = r.payload else { return }
            callKit.updateCaller(callID: kitCallID, name: name(callerName), handle: contact.id, video: video)
            handler?.voipPushDidResolveRing(kitCallID: kitCallID, contact: contact, ring: ring)
        case let .reportRing(callerName, video):
            guard case let .ring(ring, contact) = r.payload else { return }
            callKit.reportIncoming(callID: kitCallID, name: name(callerName), handle: contact.id, video: video) { _ in }
            handler?.voipPushDidResolveRing(kitCallID: kitCallID, contact: contact, ring: ring)
        case let .end(reason):
            callKit.reportEnded(callID: kitCallID, reason: CXCallEndedReason(reason))
            handler?.voipPushDidEnd(kitCallID: kitCallID, reason: reason, resolution: r)
        case .none:
            break
        }
    }
}
#endif
