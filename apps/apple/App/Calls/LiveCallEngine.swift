import Foundation
import os
import AppCore
import CallCore
#if os(iOS)
import AVFoundation
import CallKit
#endif

/// The real call engine: one `CallRunner` (machine + effects) per call, WebSocket signaling in, ChatStore history out.
/// All decisions live in CallCore; this class connects them to the app, CallKit (iPhone) and the Mac ringer.
/// Owner review required (handles call payload sealing and the DTLS fingerprint path).
@MainActor
final class LiveCallEngine {
    private enum EarlyAction { case answer(video: Bool), end }

    private let chat: any ChatStore
    let signaling: any CallSignaling
    private let sealer: any CallSignalSealer
    private let clock: CallCore.Clock
    private let makeMedia: @MainActor () -> MediaSession
    private let log = Logger(subsystem: "org.example.app", category: "calls")

    private var server: ServerPolicy
    private var settings: UserSettings
    private var network: NetworkType

    private var runner: CallRunner?
    private var eventsTask: Task<Void, Never>?
    /// Whether the UI has been shown this call's state (an unanswered Mac ring stays out of the UI).
    private var shownToUI = false
    private var showsIncoming: Bool
    private var reportedConnected = false
    private var outgoing = false
    /// Answer or end taps that arrived before the engine knew the call (lower-cased server call ID).
    private var earlyActions: [String: EarlyAction] = [:]

    private(set) var state: CallState?
    var onChange: ((CallState?) -> Void)?
    /// Newest call stats, for the info sheet.
    var onStats: ((MediaStats?) -> Void)?
    /// A call was written to history: the chat list and call history should refresh.
    var onHistory: (() -> Void)?

    #if os(iOS)
    let audio = AudioSessionBridge()
    var callKit: CallKitController?
    var voip: VoIPPushCenter?
    /// Server call ID to the CallKit UUID that VoIP push made for it (push calls only).
    private var pushKitIDs: [String: UUID] = [:]
    private var callKitEndedIt = false
    #else
    var ringer: MacRinger?
    #endif

    init(chat: any ChatStore, signaling: any CallSignaling, sealer: any CallSignalSealer,
         server: ServerPolicy, settings: UserSettings, network: NetworkType,
         clock: CallCore.Clock = SystemClock(),
         makeMedia: @escaping @MainActor () -> MediaSession = { LiveMedia.make() }) {
        self.chat = chat; self.signaling = signaling; self.sealer = sealer
        self.server = server; self.settings = settings; self.network = network
        self.clock = clock; self.makeMedia = makeMedia
        #if os(iOS)
        showsIncoming = true    // CallKit rings on iPhone; the call screen shows under it
        #else
        showsIncoming = false   // Mac: the banner and notification ring; the call window opens on answer
        #endif
        eventsTask = Task { [weak self] in
            guard let events = self?.signaling.events else { return }
            for await event in events {
                guard let self else { return }
                await self.route(event)
            }
        }
    }

    func shutdown() {
        eventsTask?.cancel(); eventsTask = nil
        runner?.handle(.hangup)
    }

    // MARK: - Settings and network

    func update(server: ServerPolicy, settings: UserSettings) {
        self.server = server; self.settings = settings
        pushContext()
    }

    func pathChanged() { runner?.pathChanged() }

    private func context(for contactID: String?) -> CallContext {
        let isContact = contactID.map { id in chat.contacts.contains { $0.id == id } } ?? true
        return CallContext(server: server, settings: settings, network: network, isContact: isContact)
    }

    private func pushContext() {
        guard let runner else { return }
        runner.update(context: context(for: runner.contactID))
    }

    // MARK: - Calls

    private var hasLiveCall: Bool { runner.map { !$0.isFinished } ?? false }

    private func makeRunner(callID: String, contactID: String) -> CallRunner {
        let log = self.log
        let r = CallRunner(
            context: context(for: contactID), callID: callID, contactID: contactID,
            signaling: signaling, sealer: sealer, clock: clock, makeMedia: makeMedia,
            publish: { [weak self] in self?.apply($0, callID: callID) },
            record: { [weak self] summary, contactID, outgoing, date in
                self?.chat.recordCall(summary, contactID: contactID, outgoing: outgoing, at: date)
                self?.onHistory?()
            },
            onStats: { [weak self] in self?.onStats?($0) },
            log: { log.debug("\($0, privacy: .public)") })
        runner = r
        shownToUI = false
        reportedConnected = false
        onStats?(nil)
        return r
    }

    /// Takes the machine's state to the UI and reacts to the end of the call.
    private func apply(_ s: CallState?, callID: String) {
        guard runner?.callID == callID else { return }
        let hidden = s?.phase == .incoming && !showsIncoming
        if !hidden { shownToUI = true }
        state = shownToUI ? s : nil
        if shownToUI { onChange?(s) }
        guard let s else { return }
        if s.phase == .active, !reportedConnected {
            reportedConnected = true
            platformConnected(callID: callID)
        }
        if case .ended(let reason) = s.phase { platformEnded(callID: callID, reason: reason) }
    }

    private func beginIncoming(callID: String, contact: Contact, device: String, ring: CallSignalPayload) {
        let r = makeRunner(callID: callID, contactID: contact.id)
        outgoing = false
        #if os(iOS)
        audio.configure()
        #endif
        r.handle(.incoming(callID: callID, contact: contact, fromDeviceID: device, ring: ring))
        if let early = earlyActions.removeValue(forKey: callID.lowercased()) {
            switch early {
            case .answer(let video): r.handle(.answer(video: video))
            case .end: r.handle(.hangup)
            }
        }
    }

    // MARK: Server events

    private func route(_ event: CallServerEvent) async {
        if case .ring(let id, let from, let device, let video, let payload) = event {
            await handleRing(callID: id, from: from, device: device, video: video, payload: payload)
        } else if let runner {
            await runner.ingest(event)
        }
    }

    private func handleRing(callID: String, from account: String, device: String, video: Bool, payload: Data) async {
        if let runner, runner.callID == callID { return }   // the push path already has it
        guard !hasLiveCall else { log.debug("ring ignored: already in a call"); return }
        guard let ring = try? await sealer.open(payload, from: account),
              ring.kind == .ring, ring.callID == callID else { return }
        let contact = chat.contact(id: account)
            ?? Contact(id: account, displayName: String(localized: "Unknown"), username: "")
        guard chat.incomingCall(from: contact, video: video) == .ring else {
            chat.recordCall(video ? .missedVideo : .missedAudio, contactID: account, outgoing: false, at: clock.now)
            onHistory?()
            return
        }
        beginIncoming(callID: callID, contact: contact, device: device, ring: ring)
        #if os(iOS)
        if voip?.shouldReportWebSocketRing(callID: callID) ?? true {
            guard let kit = UUID(uuidString: callID), let callKit else { return }
            callKit.reportIncoming(callID: kit, name: contact.displayName, handle: contact.id, video: video) { [weak self] error in
                // CallKit refused (Do Not Disturb, blocked): the call never rings, so it is missed.
                guard error != nil else { return }
                self?.runner?.handle(.server(.ended(callID: callID, reason: "missed")))
            }
        }
        #else
        ringer?.ring(callID: callID, caller: contact, video: video)
        #endif
    }

    // MARK: Platform

    private func platformConnected(callID: String) {
        #if os(iOS)
        if outgoing, let kit = kitID(callID) { callKit?.reportConnected(callID: kit) }
        #endif
    }

    private func platformEnded(callID: String, reason: CallEndReason) {
        #if os(iOS)
        if let kit = kitID(callID) {
            if !callKitEndedIt { callKit?.reportEnded(callID: kit, reason: CXCallEndedReason(CallKitEndKind(reason))) }
            if let pushKit = pushKitIDs.removeValue(forKey: callID) { voip?.pushCallFinished(kitCallID: pushKit) }
        }
        callKitEndedIt = false
        #else
        ringer?.ended(callID: callID)
        #endif
        earlyActions[callID.lowercased()] = nil
    }

    #if os(iOS)
    private func kitID(_ callID: String) -> UUID? { pushKitIDs[callID] ?? UUID(uuidString: callID) }

    private func matches(_ kit: UUID) -> String? {
        guard let id = runner?.callID, id.lowercased() == kit.uuidString.lowercased() else { return nil }
        return id
    }
    #endif
}

// MARK: - CallEngine

extension LiveCallEngine: @MainActor CallEngine {
    func start(with contact: Contact, video: Bool) {
        guard !hasLiveCall else { return }
        let callID = UUID().uuidString.lowercased()
        let r = makeRunner(callID: callID, contactID: contact.id)
        outgoing = true
        #if os(iOS)
        audio.configure()
        if let kit = UUID(uuidString: callID) { callKit?.requestStart(contact: contact, video: video, callID: kit) }
        #endif
        r.handle(.start(contact: contact, video: video, callID: callID))
    }

    func setPreference(_ preference: ModePreference) { runner?.handle(.setPreference(preference)) }
    func setMuted(_ on: Bool) { runner?.handle(.setMuted(on)) }
    func setSpeaker(_ on: Bool) {
        runner?.handle(.setSpeaker(on))
        #if os(iOS)
        audio.setSpeaker(on)
        #endif
    }
    func setVideo(_ on: Bool) { runner?.handle(.setVideo(on)) }
    func end() { runner?.handle(.hangup) }
    func answer(video: Bool) { runner?.handle(.answer(video: video)) }
    func decline() { runner?.handle(.decline) }

    func setNetwork(_ type: NetworkType) {
        network = type
        pushContext()
    }

    func reset() {
        guard runner?.isFinished ?? true else { return }
        runner = nil
        state = nil
        shownToUI = false
        onStats?(nil)
        onChange?(nil)
    }
}

// MARK: - Mac ringing (called by AppModel's `MacRingHandling` when live)

#if os(macOS)
extension LiveCallEngine {
    func ringAnswer(callID: String, video: Bool) {
        if let runner, runner.callID == callID { runner.handle(.answer(video: video)) }
        else { earlyActions[callID.lowercased()] = .answer(video: video) }   // launched by the notification: the ring comes next
    }

    func ringDecline(callID: String) {
        if let runner, runner.callID == callID { runner.handle(.decline) }
        else { Task { try? await signaling.decline(callID: callID) } }
    }

    func ringStatus(callID: String) async -> CallStatus? { try? await signaling.status(callID: callID) }

    /// The ring ran out, or the call was already over when the user answered.
    func ringFinished(callID: String, kind: CallKitEndKind) {
        guard let runner, runner.callID == callID, !runner.isFinished else { return }
        runner.handle(.server(.ended(callID: callID, reason: kind == .unanswered ? "missed" : "hangup")))
    }
}
#endif

// MARK: - CallKit and VoIP push (iPhone)

#if os(iOS)
extension LiveCallEngine: CallKitCallHandling {
    func callKitAnswer(callID: UUID) {
        if matches(callID) != nil {
            runner?.handle(.answer(video: runner?.state?.isVideoCall ?? false))
        } else {
            earlyActions[callID.uuidString.lowercased()] = .answer(video: false)
        }
    }

    func callKitEnd(callID: UUID) {
        if matches(callID) != nil {
            callKitEndedIt = true
            runner?.handle(.hangup)
        } else {
            earlyActions[callID.uuidString.lowercased()] = .end
        }
    }

    func callKitSetMuted(callID: UUID, muted: Bool) {
        if matches(callID) != nil { runner?.handle(.setMuted(muted)) }
    }

    func callKitAudioDidActivate(_ session: AVAudioSession) { audio.didActivate(session) }
    func callKitAudioDidDeactivate(_ session: AVAudioSession) { audio.didDeactivate(session) }

    func callKitReset() {
        callKitEndedIt = true
        runner?.handle(.hangup)
        earlyActions = [:]
    }
}

extension LiveCallEngine: VoIPPushHandling {
    func engineKnows(callID: String) -> Bool { runner?.callID == callID && hasLiveCall }

    func voipPushDidResolveRing(kitCallID: UUID, contact: Contact, ring: CallSignalPayload, answer: Bool) {
        guard !hasLiveCall else {
            callKit?.reportEnded(callID: kitCallID, reason: .unanswered)
            voip?.pushCallFinished(kitCallID: kitCallID)
            return
        }
        pushKitIDs[ring.callID] = kitCallID
        if answer { earlyActions[ring.callID.lowercased()] = .answer(video: ring.video ?? false) }
        // A push carries no sender device: the machine binds to the device whose answer arrives.
        beginIncoming(callID: ring.callID, contact: contact, device: "", ring: ring)
    }

    func voipPushDeclined(kitCallID: UUID, contact: Contact, ring: CallSignalPayload) {
        Task { try? await signaling.decline(callID: ring.callID) }
        recordMissed(ring: ring, contact: contact)
        voip?.pushCallFinished(kitCallID: kitCallID)
    }

    func voipPushDidEnd(kitCallID: UUID, reason: EndReason, resolution: VoIPPushResolution) {
        if reason == .unanswered, case let .ring(ring, contact) = resolution.payload {
            recordMissed(ring: ring, contact: contact)   // strangers' calls land in their message request
        }
    }

    func voipPushEndedAfterRing(kitCallID: UUID, callID: String, kind: CallKitEndKind) {
        let why: String = switch kind {
        case .unanswered: "missed"
        case .answeredElsewhere: "answered_elsewhere"
        case .failed, .remoteEnded: "hangup"
        }
        pushKitIDs[callID] = kitCallID
        if runner?.callID == callID {
            callKitEndedIt = true   // the system call is already ended
            runner?.handle(.server(.ended(callID: callID, reason: why)))
        } else {
            voip?.pushCallFinished(kitCallID: kitCallID)
        }
    }

    private func recordMissed(ring: CallSignalPayload, contact: Contact) {
        chat.recordCall((ring.video ?? false) ? .missedVideo : .missedAudio, contactID: contact.id, outgoing: false, at: clock.now)
        onHistory?()
    }
}
#endif
