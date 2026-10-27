#if os(iOS)
import Foundation
import PushKit
import CallKit
import AVFoundation
import AppCore
import CallCore

@MainActor
protocol VoIPPushHandling: AnyObject {
    /// True when the engine already has this server call ID (its WebSocket ring came first).
    func engineKnows(callID: String) -> Bool
    /// A push call settled as ringing. The server call ID is `ring.callID`; the push carries no call ID, so the
    /// CallKit UUID was made at push time and the engine maps `kitCallID` to it. `answer` is true when the user
    /// already tapped Answer while the payload was opening. Actions after this arrive through `CallKitCallHandling`
    /// under the server call ID (as a UUID).
    func voipPushDidResolveRing(kitCallID: UUID, contact: Contact, ring: CallSignalPayload, answer: Bool)
    /// The user declined before the payload opened: send `call.decline` for `ring.callID`; the call never rings.
    func voipPushDeclined(kitCallID: UUID, contact: Contact, ring: CallSignalPayload)
    /// The call ended before the engine was ever handed it (stranger, undecryptable or empty push, ring cap before the
    /// payload opened). The CallKit call is already ended. Never called for a call given to the engine.
    func voipPushDidEnd(kitCallID: UUID, reason: EndReason, resolution: VoIPPushResolution)
    /// The engine already has this call ringing (`voipPushDidResolveRing`) and `call.status` or the ring cap says it is
    /// over. The CallKit call is already ended; the engine drops its ringing call and records a missed call if `reason`
    /// is `.unanswered`. Called at most once per call, and never after the user answered or declined.
    func voipPushEndedAfterRing(kitCallID: UUID, callID: String, kind: CallKitEndKind)
}

/// The one `PKPushRegistry` for the app's lifetime, and the front door for CallKit's user actions.
/// The push carries only a sealed payload, so:
/// 1. synchronously in the push callback: report a generic "Incoming call" to CallKit, then the PushKit completion;
/// 2. open the payload (local work only), recover the sender, show the name at once;
/// 3. in parallel, fetch `call.status` with a short timeout; on timeout keep ringing;
/// 4. `PushCallTracker` settles report result, user actions taken meanwhile, and duplicates with the WebSocket path.
@MainActor
final class VoIPPushCenter: NSObject, PKPushRegistryDelegate, CallKitCallHandling {
    private let callKit: CallKitController
    private let resolver: VoIPPushResolver
    private let onToken: @Sendable (Data) -> Void
    private var tracker = PushCallTracker()
    private var contexts: [UUID: (contact: Contact, ring: CallSignalPayload)] = [:]
    weak var handler: VoIPPushHandling?
    /// The engine; receives CallKit actions with the server call ID.
    weak var engine: CallKitCallHandling?
    private var registry: PKPushRegistry?

    static let statusTimeout = VoIPPushResolver.defaultStatusTimeout
    /// The server's own ring timeout (45 s) plus a little: a push call still ringing then is ended as unanswered.
    static let ringTimeout: Duration = .seconds(50)

    /// `onToken` hands the VoIP token to the push registrar (kind `voip`).
    init(callKit: CallKitController, resolver: VoIPPushResolver, onToken: @escaping @Sendable (Data) -> Void) {
        self.callKit = callKit
        self.resolver = resolver
        self.onToken = onToken
        super.init()
        callKit.handler = self
    }

    func start() {
        guard registry == nil else { return }
        let r = PKPushRegistry(queue: .main)
        r.delegate = self
        r.desiredPushTypes = [.voIP]
        registry = r
    }

    /// For the WebSocket ring path: false when a push already reported this call to CallKit.
    func shouldReportWebSocketRing(callID: String) -> Bool { tracker.webSocketRing(callID: callID) }

    /// The engine calls this when a call that came from a push is over, to free its bookkeeping.
    func pushCallFinished(kitCallID: UUID) { tracker.finish(kit: kitCallID); contexts[kitCallID] = nil }

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
    // must be copied from the iOS 26.4 SDK header on the macOS CI task or a Mac. Until then the plain method above
    // runs with `mustReport == nil`, which reports every push (always safe). When added, it only needs to call
    // `handle(d:mustReport:completion:)` with the metadata value.

    // MARK: Stage 1 (synchronous)

    func handle(d: String?, mustReport: Bool?, completion: @escaping () -> Void) {
        let kitCallID = UUID()
        let sealed = VoIPPushBody.payload(d: d)
        let reportedGeneric = VoIPPushDecision.immediate(mustReport: mustReport) == .reportGeneric
        tracker.begin(kit: kitCallID)
        if reportedGeneric {
            // Nothing async before this line. The handle is a placeholder: the push has no sender or call ID.
            reportToCallKit(kit: kitCallID, name: String(localized: "Incoming call"),
                            handle: kitCallID.uuidString, video: false)
        }
        completion()
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: Self.ringTimeout)
            self?.capReached(kit: kitCallID)
        }
        Task { @MainActor [weak self] in
            await self?.resolve(kit: kitCallID, sealed: sealed, mustReport: mustReport, reportedGeneric: reportedGeneric)
        }
    }

    private func reportToCallKit(kit: UUID, name: String, handle: String, video: Bool) {
        callKit.reportIncoming(callID: kit, name: name, handle: handle, video: video) { [weak self] error in
            guard let self else { return }
            self.apply(self.tracker.reportFinished(kit: kit, refused: error != nil), kit: kit)
        }
    }

    // MARK: Stage 2 (asynchronous)

    private func displayName(_ n: String) -> String { n == VoIPPushDecision.unknownCallerName ? String(localized: "Unknown") : n }

    private func resolve(kit: UUID, sealed: Data?, mustReport: Bool?, reportedGeneric: Bool) async {
        let r = await resolver.open(sealed: sealed)   // local work only
        let action = VoIPPushDecision.decide(mustReport: mustReport, payload: r.payload,
                                             isStranger: r.isStranger, status: nil)
        switch VoIPPushDecision.followUp(reportedGeneric: reportedGeneric, action: action) {
        case let .end(reason):
            callKit.reportEnded(callID: kit, reason: CXCallEndedReason(reason))
            tracker.finish(kit: kit)
            handler?.voipPushDidEnd(kitCallID: kit, reason: reason, resolution: r)
        case .none:
            tracker.finish(kit: kit)
        case let .updateCaller(name, video), let .reportRing(name, video):
            guard case let .ring(ring, contact) = r.payload else { tracker.finish(kit: kit); return }
            contexts[kit] = (contact, ring)
            if reportedGeneric {
                callKit.updateCaller(callID: kit, name: displayName(name), handle: contact.id, video: video)
            } else {
                reportToCallKit(kit: kit, name: displayName(name), handle: contact.id, video: video)
            }
            let known = handler?.engineKnows(callID: ring.callID) ?? false
            apply(tracker.resolved(kit: kit, callID: ring.callID, engineKnows: known), kit: kit)
            startStatus(kit: kit, ring: ring)
        }
    }

    /// `call.status` runs after the name is shown. On a nil result (timeout) the call keeps ringing.
    private func startStatus(kit: UUID, ring: CallSignalPayload) {
        let resolver = self.resolver
        Task { @MainActor [weak self] in
            let status = await resolver.fetchStatus(callID: ring.callID, timeout: Self.statusTimeout)
            guard let self, let status, let kind = PushCallTracker.lateEndKind(status: status) else { return }
            if let late = self.tracker.endRinging(kit: kit, kind: kind) { self.endAfterRing(kit: kit, late) }
        }
    }

    private func capReached(kit: UUID) {
        switch tracker.capReached(kit: kit) {
        case .none: break
        case let .endRinging(late): endAfterRing(kit: kit, late)
        case .endUnsettled:
            callKit.reportEnded(callID: kit, reason: CXCallEndedReason(CallKitEndKind.unanswered))
            contexts[kit] = nil
        }
    }

    private func endAfterRing(kit: UUID, _ late: PushLateEnd) {
        callKit.reportEnded(callID: kit, reason: CXCallEndedReason(late.kind))
        contexts[kit] = nil
        handler?.voipPushEndedAfterRing(kitCallID: kit, callID: late.callID, kind: late.kind)
    }

    private func apply(_ effect: PushCallEffect?, kit: UUID) {
        guard let effect else { return }
        let ctx = contexts[kit]
        switch effect {
        case .ring:
            if let ctx { handler?.voipPushDidResolveRing(kitCallID: kit, contact: ctx.contact, ring: ctx.ring, answer: false) }
        case .answerNow:
            if let ctx { handler?.voipPushDidResolveRing(kitCallID: kit, contact: ctx.contact, ring: ctx.ring, answer: true) }
        case .decline:
            if let ctx { handler?.voipPushDeclined(kitCallID: kit, contact: ctx.contact, ring: ctx.ring) }
            contexts[kit] = nil
        case .endDuplicate:
            callKit.reportEnded(callID: kit, reason: .duplicate)
            contexts[kit] = nil
        case .finish:
            contexts[kit] = nil   // CallKit refused (Do Not Disturb, blocked, filtered): the engine never hears of it
        }
    }

    // MARK: CallKitCallHandling (actions from CallKit, routed by call origin)

    private func engineID(_ id: UUID) -> UUID {
        // Resolved push call: the engine knows it under the server call ID.
        tracker.callID(forKit: id).flatMap(UUID.init(uuidString:)) ?? id
    }

    func callKitAnswer(callID: UUID) {
        switch tracker.userAction(.answer, kit: callID) {
        case .recorded: break
        case .forward: engine?.callKitAnswer(callID: engineID(callID))
        case .passthrough: engine?.callKitAnswer(callID: callID)
        }
    }

    func callKitEnd(callID: UUID) {
        switch tracker.userAction(.end, kit: callID) {
        case .recorded: break
        case .forward: engine?.callKitEnd(callID: engineID(callID))
        case .passthrough: engine?.callKitEnd(callID: callID)
        }
    }

    func callKitSetMuted(callID: UUID, muted: Bool) { engine?.callKitSetMuted(callID: engineID(callID), muted: muted) }
    func callKitAudioDidActivate(_ session: AVAudioSession) { engine?.callKitAudioDidActivate(session) }
    func callKitAudioDidDeactivate(_ session: AVAudioSession) { engine?.callKitAudioDidDeactivate(session) }
    func callKitReset() { engine?.callKitReset() }
}
#endif
