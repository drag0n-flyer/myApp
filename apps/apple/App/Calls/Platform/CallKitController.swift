#if os(iOS)
import AVFoundation
import CallKit
import AppCore
import CallCore

/// What CallKit asks of the call engine. `LiveCallEngine` conforms. All calls arrive on the main actor.
@MainActor
protocol CallKitCallHandling: AnyObject {
    func callKitAnswer(callID: UUID)
    /// Decline while ringing, hang up once answered; the engine decides from its own state.
    func callKitEnd(callID: UUID)
    func callKitSetMuted(callID: UUID, muted: Bool)
    /// May arrive before the engine knows the call (answer before the payload opened): keep it, do not drop it.
    func callKitAudioDidActivate(_ session: AVAudioSession)
    func callKitAudioDidDeactivate(_ session: AVAudioSession)
    /// CallKit dropped every call (provider reset): the engine must end its calls and stop audio.
    func callKitReset()
}

/// Owns the one `CXProvider` and the `CXCallController`. The server call ID (a UUIDv4 string) is the CallKit UUID.
@MainActor
final class CallKitController: NSObject, CXProviderDelegate {
    /// `VoIPPushCenter` sets itself here to route push-created calls. Do not replace it with the engine.
    weak var handler: CallKitCallHandling?
    private let provider: CXProvider
    private let controller = CXCallController()
    /// Display names of outgoing calls between `requestStart` and CallKit's start action.
    private var outgoingNames: [UUID: String] = [:]

    static func configuration(appName: String) -> CXProviderConfiguration {
        let c = CXProviderConfiguration()
        _ = appName   // iOS 14+ takes the name from the app; kept for call-site clarity
        c.supportsVideo = true
        c.maximumCallsPerCallGroup = 1
        c.supportedHandleTypes = [.generic]
        c.includesCallsInRecents = false   // privacy, owner decision 3
        return c
    }

    init(appName: String = AppBrand.name) {
        provider = CXProvider(configuration: Self.configuration(appName: appName))
        super.init()
        provider.setDelegate(self, queue: .main)
    }

    // MARK: Reporting

    /// Synchronous part of reporting an incoming call. PushKit calls this before anything async.
    /// `completion` runs when CallKit has accepted or refused the report.
    func reportIncoming(callID: UUID, name: String, handle: String, video: Bool,
                        completion: @escaping @MainActor (Error?) -> Void) {
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.localizedCallerName = name
        update.hasVideo = video
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false
        // CallKit calls back on the provider's queue, which is the main queue.
        nonisolated(unsafe) let done = completion
        provider.reportNewIncomingCall(with: callID, update: update) { error in
            nonisolated(unsafe) let error = error
            MainActor.assumeIsolated { done(error) }
        }
    }

    func reportIncoming(callID: UUID, name: String, handle: String, video: Bool) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            reportIncoming(callID: callID, name: name, handle: handle, video: video) { error in
                if let error { k.resume(throwing: error) } else { k.resume() }
            }
        }
    }

    /// The push was reported with a generic display; now that the payload is open, show the caller's name.
    func updateCaller(callID: UUID, name: String, handle: String, video: Bool) {
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.localizedCallerName = name
        update.hasVideo = video
        provider.reportCall(with: callID, updated: update)
    }

    func reportEnded(callID: UUID, reason: CXCallEndedReason) {
        provider.reportCall(with: callID, endedAt: nil, reason: reason)
    }

    /// The other side answered an outgoing call, or media connected: lets CallKit show the call as connected.
    func reportConnected(callID: UUID) {
        provider.reportOutgoingCall(with: callID, connectedAt: nil)
    }

    /// Outgoing calls: tells CallKit about the call so the system UI and audio session cooperate.
    func requestStart(contact: Contact, video: Bool, callID: UUID) {
        let action = CXStartCallAction(call: callID, handle: CXHandle(type: .generic, value: contact.id))
        outgoingNames[callID] = contact.displayName
        action.isVideo = video
        controller.request(CXTransaction(action: action)) { _ in }
    }

    /// App-initiated end (hang up button in our own UI).
    func requestEnd(callID: UUID) {
        controller.request(CXTransaction(action: CXEndCallAction(call: callID))) { _ in }
    }

    // MARK: CXProviderDelegate (delivered on the main queue)

    nonisolated func providerDidReset(_ provider: CXProvider) {
        MainActor.assumeIsolated { handler?.callKitReset() }
    }

    // Only Sendable values (the call id, flags) cross into the main-actor closures. `action` and `provider` stay
    // out of them: CallKit lets `reportCall` and `fulfill` be called from the delegate queue.
    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        let callUUID = action.callUUID
        let name = MainActor.assumeIsolated { outgoingNames.removeValue(forKey: callUUID) }
        let update = CXCallUpdate()
        update.remoteHandle = action.handle
        update.localizedCallerName = name
        update.hasVideo = action.isVideo
        provider.reportCall(with: callUUID, updated: update)
        provider.reportOutgoingCall(with: callUUID, startedConnectingAt: nil)
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        let callUUID = action.callUUID
        MainActor.assumeIsolated { handler?.callKitAnswer(callID: callUUID) }
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        let callUUID = action.callUUID
        MainActor.assumeIsolated { handler?.callKitEnd(callID: callUUID) }
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        let callUUID = action.callUUID
        let muted = action.isMuted
        MainActor.assumeIsolated { handler?.callKitSetMuted(callID: callUUID, muted: muted) }
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        MainActor.assumeIsolated { handler?.callKitAudioDidActivate(audioSession) }
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        MainActor.assumeIsolated { handler?.callKitAudioDidDeactivate(audioSession) }
    }
}

extension CXCallEndedReason {
    init(_ reason: EndReason) {
        switch reason {
        case .failed: self = .failed
        case .remoteEnded: self = .remoteEnded
        case .unanswered: self = .unanswered
        }
    }
}

extension CXCallEndedReason {
    init(_ kind: CallKitEndKind) {
        switch kind {
        case .failed: self = .failed
        case .remoteEnded: self = .remoteEnded
        case .unanswered: self = .unanswered
        case .answeredElsewhere: self = .answeredElsewhere
        }
    }
    /// The push-created call duplicates one the engine already shows: end it without a missed-call entry.
    static let duplicate: CXCallEndedReason = .answeredElsewhere
}
#endif
