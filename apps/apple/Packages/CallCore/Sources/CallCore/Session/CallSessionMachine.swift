import Foundation
import AppCore

/// The call state machine: inputs in, effects out, no I/O. Caller and callee paths.
public struct CallSessionMachine: Sendable {
    /// Caps for what a peer can make us buffer before a call is answered.
    static let maxEarlyDevices = 8
    static let maxEarlyCandidatesPerDevice = 64
    static let maxPendingLocalCandidates = 64

    public private(set) var state: CallState?

    private var context: CallContext
    private let clock: Clock

    private var callID = ""
    private var outgoing = true
    private var adaptor = QualityAdaptor(ceiling: .ultraLow, videoWanted: false)
    private var reconnect = ReconnectPolicy(isCaller: true)
    private var peerCeiling: DataMode?

    private var answeringDevice: String?
    private var remoteDescriptionSet = false
    private var pinnedFingerprint: String?
    private var renegotiateOutstanding = false
    private var answerSent = false
    private var answerRequested = false          // callee: the user answered
    private var localOfferSDP: String?            // callee: our prewarmed offer
    private var pendingRenegotiateSince: Date?   // callee: accepted a renegotiate offer, owe the answer
    private var outstandingSince: Date?           // caller: our restart offer is out, answer not yet accepted
    private var lastHonouredRequest: Date?        // caller: last restart request we acted on
    /// Restarts are coalesced: one in flight at a time, and a peer's requests are honoured at most once per window.
    private static let restartWindow = ReconnectPolicy.retryEvery
    private static let staleAnswerAfter: TimeInterval = 10
    private var videoStartsOff = false
    /// The send profile the current remote description was munged with; a change of ptime, FEC or DTX needs a renegotiation.
    private var negotiatedSend: AudioProfile?
    private var pendingLocalCandidates: [IceCandidate] = []
    /// Remote candidates received before we can use them, by sending device.
    private var earlyRemoteCandidates: [String: [IceCandidate]] = [:]
    private var activeSince: Date?
    private var everActive = false
    private var lastSentMode: DataMode?
    private var lastSentTier: VideoTier?
    private var lastSentCeiling: DataMode?   // the ceiling the media layer last got with a `.setVideo`

    public init(context: CallContext, clock: Clock) {
        self.context = context
        self.clock = clock
    }

    public mutating func handle(_ input: CallInput) -> [CallEffect] {
        switch input {
        case .start(let contact, let video, let id): return start(contact, video, id)
        case .incoming(let id, let contact, let device, let ring): return incoming(id, contact, device, ring)
        case .answer(let video): return answer(video)
        case .decline: return decline()
        case .hangup: return hangup()
        case .setPreference(let p): return setPreference(p)
        case .setVideo(let on): return setVideo(on)
        case .setMuted(let on): return live { $0.isMuted = on }.map { $0 + [.setMuted(on)] } ?? []
        case .setSpeaker(let on): return live { $0.isSpeakerOn = on } ?? []
        case .server(let e): return server(e)
        case .remote(let p, let device): return remote(p, device)
        case .remoteRejected(_, let device):
            guard let answering = answeringDevice, device == answering else { return [] }
            return end(.failed, notifyServer: true)
        case .media(let e): return media(e)
        case .localOffer(let sdp): return localOffer(sdp)
        case .localAnswer(let sdp): return localAnswer(sdp)
        case .stats(let s): return stats(s)
        case .pathChanged: return reconnectActions(reconnect.pathChanged(at: clock.now))
        case .tick: return tick()
        case .contextChanged(let c): return contextChanged(c)
        }
    }

    // MARK: - Inputs

    private var ownCeiling: DataMode {
        DataModePolicy.ceiling(server: context.server, settings: context.settings.call, network: context.network,
                               inCall: state?.inCallPreference)
    }
    private var effectiveCeiling: DataMode { min(ownCeiling, peerCeiling ?? .high) }

    private mutating func start(_ contact: Contact, _ video: Bool, _ id: String) -> [CallEffect] {
        if let s = state, !s.isEnded { return [] }
        resetCall(id, outgoing: true)

        let ceiling = ownCeiling
        let videoOn = video && !VideoLadder.startsOff(ceiling: ceiling, network: context.network, settings: context.settings.call)
        adaptor = QualityAdaptor(ceiling: ceiling, videoWanted: videoOn)
        lastSentMode = adaptor.currentMode
        lastSentTier = adaptor.currentTier
        state = CallState(contact: contact, phase: .ringing, isVideoCall: video, isVideoOn: videoOn,
                          isSpeakerOn: video, effectiveMode: ceiling, network: context.network)
        let policy = RelayDecision.policy(context.settings.ipHiding, isContact: context.isContact)
        return [
            .configureMedia(policy: policy, video: video),
            .setNoiseSuppression(context.settings.noiseSuppression),
            .sendInvite(CallSignalPayload(kind: .ring, callID: id, video: video, ceiling: ceiling)),
            .publish(state),
        ]
    }

    private mutating func resetCall(_ id: String, outgoing isOutgoing: Bool) {
        callID = id
        outgoing = isOutgoing
        peerCeiling = nil
        answeringDevice = nil; remoteDescriptionSet = false; answerSent = false
        answerRequested = false; localOfferSDP = nil; pendingRenegotiateSince = nil
        outstandingSince = nil; lastHonouredRequest = nil; negotiatedSend = nil
        pinnedFingerprint = nil; renegotiateOutstanding = false
        pendingLocalCandidates = []; earlyRemoteCandidates = [:]
        activeSince = nil; everActive = false; lastSentMode = nil; lastSentTier = nil; lastSentCeiling = nil
        reconnect = ReconnectPolicy(isCaller: isOutgoing)
    }

    private mutating func incoming(_ id: String, _ contact: Contact, _ device: String, _ ring: CallSignalPayload) -> [CallEffect] {
        if let s = state, !s.isEnded { return [] }
        guard ring.kind == .ring, ring.callID == id else { return [] }
        resetCall(id, outgoing: false)
        answeringDevice = device              // the exchange is bound to the caller's device from the ring
        peerCeiling = ring.ceiling
        let video = ring.video ?? false
        let ceiling = ownCeiling
        videoStartsOff = VideoLadder.startsOff(ceiling: effectiveCeiling, network: context.network, settings: context.settings.call)
        adaptor = QualityAdaptor(ceiling: effectiveCeiling, videoWanted: false)
        lastSentMode = adaptor.currentMode
        lastSentTier = nil
        state = CallState(contact: contact, phase: .incoming, isVideoCall: video, isVideoOn: false,
                          effectiveMode: adaptor.currentMode, network: context.network)
        let policy = RelayDecision.policy(context.settings.ipHiding, isContact: context.isContact)
        return [
            .configureMedia(policy: policy, video: video && !videoStartsOff),
            .setNoiseSuppression(context.settings.noiseSuppression),
            .makeOffer(receive: .for(ceiling), iceRestart: false),
            .publish(state),
        ]
    }

    private mutating func answer(_ video: Bool) -> [CallEffect] {
        guard isLive, !outgoing, state?.phase == .incoming, !answerRequested else { return [] }
        answerRequested = true
        let on = video && (state?.isVideoCall ?? false) && !videoStartsOff
        adaptor = QualityAdaptor(ceiling: effectiveCeiling, videoWanted: on)   // fresh start with the answer's video choice
        let tier = adaptor.currentTier
        lastSentTier = tier
        update { $0.phase = .connecting; $0.isVideoOn = tier != nil; $0.isSpeakerOn = on }
        var fx: [CallEffect] = []
        if let sdp = localOfferSDP { fx += sendAnswer(sdp) }
        if isLive { fx.append(.publish(state)) }
        return fx
    }

    private mutating func decline() -> [CallEffect] {
        guard isLive, !outgoing, state?.phase == .incoming else { return [] }
        return [.sendDecline] + end(.declined, notifyServer: false)
    }

    /// Callee: sends our prewarmed offer, then the candidates gathered while ringing.
    private mutating func sendAnswer(_ sdp: String) -> [CallEffect] {
        guard let fingerprint = SDPMunger.fingerprint(in: sdp) else { return end(.failed, notifyServer: true) }
        answerSent = true
        var fx: [CallEffect] = [.sendAnswer(CallSignalPayload(kind: .offer, callID: callID, sdp: sdp,
                                                             dtlsFingerprint: fingerprint, ceiling: ownCeiling))]
        for c in pendingLocalCandidates { fx.append(.sendSignal(CallSignalPayload(kind: .candidate, callID: callID, candidate: c))) }
        pendingLocalCandidates = []
        return fx
    }

    private mutating func bufferEarly(_ c: IceCandidate, from device: String) {
        if earlyRemoteCandidates[device] == nil, earlyRemoteCandidates.count >= Self.maxEarlyDevices { return }
        if (earlyRemoteCandidates[device]?.count ?? 0) >= Self.maxEarlyCandidatesPerDevice { return }
        earlyRemoteCandidates[device, default: []].append(c)
    }

    private mutating func server(_ e: CallServerEvent) -> [CallEffect] {
        switch e {
        case .answered(let id, let device, _):
            guard outgoing, id == callID, isLive, answeringDevice == nil else { return [] }
            answeringDevice = device
            // Keep only the answering device's buffered candidates.
            earlyRemoteCandidates = earlyRemoteCandidates.filter { $0.key == device }
            return []
        case .ended(let id, let reason):
            guard id == callID, isLive else { return [] }
            return end(Self.reason(reason), notifyServer: false)
        case .ring, .signal:
            return []   // payloads reach the machine as `.remote` after the engine opens them
        }
    }

    private mutating func remote(_ p: CallSignalPayload, _ device: String) -> [CallEffect] {
        guard p.callID == callID, isLive else { return [] }
        switch p.kind {
        case .candidate:
            guard let c = p.candidate else { return [] }
            guard let answering = answeringDevice else {
                bufferEarly(c, from: device)   // before call.answered: per device, capped
                return []
            }
            guard device == answering else { return [] }
            if remoteDescriptionSet { return [.addCandidate(c)] }
            bufferEarly(c, from: answering)
            return []
        case .offer:
            guard outgoing, let answering = answeringDevice, !remoteDescriptionSet, device == answering else { return [] }
            guard let sdp = p.sdp, let claimed = p.dtlsFingerprint,
                  FingerprintCheck.isValid(sdp: sdp, claimed: claimed) else {
                return end(.failed, notifyServer: true)
            }
            if let c = p.ceiling { setPeerCeiling(c) }
            remoteDescriptionSet = true
            pinnedFingerprint = claimed
            negotiatedSend = .for(state?.effectiveMode ?? ownCeiling)
            var fx: [CallEffect] = [.acceptOffer(sdp: sdp, send: .for(state?.effectiveMode ?? ownCeiling),
                                                receive: .for(ownCeiling))]
            for c in earlyRemoteCandidates.removeValue(forKey: answering) ?? [] { fx.append(.addCandidate(c)) }
            update { $0.phase = .connecting }
            fx += syncMedia()   // the peer's ceiling may be lower than ours: clamp video now
            fx.append(.publish(state))
            return fx
        case .answer:
            guard !outgoing, answerSent, !remoteDescriptionSet, device == answeringDevice, let answering = answeringDevice else { return [] }
            guard let sdp = p.sdp, let claimed = p.dtlsFingerprint, FingerprintCheck.isValid(sdp: sdp, claimed: claimed) else {
                return end(.failed, notifyServer: true)
            }
            if let c = p.ceiling { setPeerCeiling(c) }
            remoteDescriptionSet = true
            pinnedFingerprint = claimed
            negotiatedSend = .for(state?.effectiveMode ?? ownCeiling)
            var fx: [CallEffect] = [.acceptAnswer(sdp: sdp, send: .for(state?.effectiveMode ?? ownCeiling))]
            for c in earlyRemoteCandidates.removeValue(forKey: answering) ?? [] { fx.append(.addCandidate(c)) }
            fx += syncMedia()   // the peer's ceiling may be lower than ours: clamp video now
            fx.append(.publish(state))
            return fx
        case .renegotiate:
            guard device == answeringDevice, remoteDescriptionSet, answerSent else { return [] }
            // `renegotiate` without an SDP is a restart REQUEST from the callee: only the caller acts on it, and it
            // answers with its own ICE-restart offer. `renegotiate` with an SDP is an OFFER (mode change or restart).
            guard let sdp = p.sdp else {
                guard outgoing else { return [] }
                let now = clock.now
                // Never start our own offer while we still owe an answer (have-remote-offer).
                if let since = pendingRenegotiateSince, now.timeIntervalSince(since) < Self.staleAnswerAfter { return [] }
                if renegotiateOutstanding, let since = outstandingSince, now.timeIntervalSince(since) < Self.restartWindow { return [] }
                if let last = lastHonouredRequest, now.timeIntervalSince(last) < Self.restartWindow { return [] }
                lastHonouredRequest = now
                renegotiateOutstanding = true
                outstandingSince = now
                return [.makeOffer(receive: .for(ownCeiling), iceRestart: true)]
            }
            let now = clock.now
            // One offer to answer at a time: a second one before we answered the first is not accepted.
            if let since = pendingRenegotiateSince, now.timeIntervalSince(since) < Self.staleAnswerAfter { return [] }
            guard FingerprintCheck.isValid(sdp: sdp, claimed: p.dtlsFingerprint, pinned: pinnedFingerprint, requirePin: true) else {
                return end(.failed, notifyServer: true)
            }
            var fx: [CallEffect] = []
            if renegotiateOutstanding {
                // Glare: the caller's offer wins. The caller ignores the callee's offer while its own is fresh;
                // the callee (or a caller whose offer went stale) rolls its own offer back first.
                // DEVIATION (owner to confirm): the plan only has the callee roll back. A caller rolls back too when
                // its own offer is stale (>= restartWindow with no answer), so a lost answer cannot block the peer's
                // offer forever. A fresh caller offer still always wins.
                let fresh = outstandingSince.map { now.timeIntervalSince($0) < Self.restartWindow } ?? false
                if outgoing && fresh { return [] }
                fx.append(.rollback)
                renegotiateOutstanding = false
                outstandingSince = nil
            }
            if let c = p.ceiling { setPeerCeiling(c) }
            pendingRenegotiateSince = now
            let send = AudioProfile.for(state?.effectiveMode ?? ownCeiling)
            negotiatedSend = send
            fx.append(.acceptOffer(sdp: sdp, send: send, receive: .for(ownCeiling)))
            return fx + syncMedia()
        case .renegotiateAnswer:
            guard device == answeringDevice, remoteDescriptionSet, renegotiateOutstanding, let sdp = p.sdp else { return [] }
            // The fingerprint must match the SDP and the one pinned from the original offer or answer.
            guard FingerprintCheck.isValid(sdp: sdp, claimed: p.dtlsFingerprint, pinned: pinnedFingerprint, requirePin: true) else {
                return end(.failed, notifyServer: true)
            }
            renegotiateOutstanding = false
            outstandingSince = nil
            let send = AudioProfile.for(state?.effectiveMode ?? ownCeiling)
            negotiatedSend = send
            return [.acceptAnswer(sdp: sdp, send: send)]
        case .modeChange:
            guard device == answeringDevice else { return [] }
            if let c = p.ceiling { setPeerCeiling(c) }
            return syncMedia() + [.publish(state)] + renegotiationIfNeeded()
        case .ring:
            return []
        }
    }

    private mutating func localAnswer(_ sdp: String) -> [CallEffect] {
        if pendingRenegotiateSince != nil {
            guard isLive else { return [] }
            pendingRenegotiateSince = nil
            guard let fingerprint = SDPMunger.fingerprint(in: sdp) else { return end(.failed, notifyServer: true) }
            return [.sendSignal(CallSignalPayload(kind: .renegotiateAnswer, callID: callID, sdp: sdp,
                                                  dtlsFingerprint: fingerprint, ceiling: ownCeiling))]
        }
        guard outgoing, isLive, remoteDescriptionSet, !answerSent else { return [] }
        answerSent = true
        var fx: [CallEffect] = [.sendSignal(CallSignalPayload(
            kind: .answer, callID: callID, sdp: sdp, dtlsFingerprint: SDPMunger.fingerprint(in: sdp), ceiling: ownCeiling))]
        for c in pendingLocalCandidates { fx.append(.sendSignal(CallSignalPayload(kind: .candidate, callID: callID, candidate: c))) }
        pendingLocalCandidates = []
        return fx
    }

    private mutating func localOffer(_ sdp: String) -> [CallEffect] {
        if !outgoing, !answerSent {
            // Callee: our prewarmed offer. It goes out only once the user answers.
            guard isLive, localOfferSDP == nil else { return [] }
            localOfferSDP = sdp
            return answerRequested ? sendAnswer(sdp) + [.publish(state)] : []
        }
        // After the answer, either side: an offer we asked for (ICE restart or mode change).
        guard isLive, answerSent, renegotiateOutstanding else { return [] }
        guard let fingerprint = SDPMunger.fingerprint(in: sdp) else { return end(.failed, notifyServer: true) }
        return [.sendSignal(CallSignalPayload(kind: .renegotiate, callID: callID, sdp: sdp,
                                              dtlsFingerprint: fingerprint, ceiling: ownCeiling))]
    }

    private mutating func media(_ e: MediaEvent) -> [CallEffect] {
        guard isLive else { return [] }
        switch e {
        case .localCandidate(let c):
            if answerSent { return [.sendSignal(CallSignalPayload(kind: .candidate, callID: callID, candidate: c))] }
            if pendingLocalCandidates.count < Self.maxPendingLocalCandidates { pendingLocalCandidates.append(c) }
            return []
        case .remoteVideo:
            return []
        case .iceState(let s):
            let actions = reconnect.ice(s, at: clock.now)
            var fx = reconnectActions(actions)
            if (s == .connected || s == .completed), state?.phase == .connecting || state?.phase == .ringing {
                markActive()
                update { $0.phase = .active }
                fx.append(.publish(state))
            }
            return fx
        }
    }

    private mutating func reconnectActions(_ actions: [ReconnectAction]) -> [CallEffect] {
        guard isLive else { return [] }
        var fx: [CallEffect] = []
        for a in actions {
            switch a {
            case .showReconnecting:
                update { $0.phase = .reconnecting; $0.banner = .reconnecting }
                fx.append(.publish(state))
            case .restartIce:
                let now = clock.now
                // Never start our own offer while we still owe an answer (have-remote-offer).
                if let since = pendingRenegotiateSince, now.timeIntervalSince(since) < Self.staleAnswerAfter { break }
                if renegotiateOutstanding, let since = outstandingSince, now.timeIntervalSince(since) < Self.restartWindow { break }
                renegotiateOutstanding = true
                outstandingSince = now
                fx.append(.makeOffer(receive: .for(ownCeiling), iceRestart: true))
            case .requestRestart:
                if let since = pendingRenegotiateSince, clock.now.timeIntervalSince(since) < Self.staleAnswerAfter { break }
                fx.append(.sendSignal(CallSignalPayload(kind: .renegotiate, callID: callID)))   // callee asks; no SDP
            case .recovered:
                markActive()
                update { $0.phase = .active; $0.banner = nil }
                fx.append(.publish(state))
            case .giveUp:
                update { $0.banner = .connectionLost }
                fx += end(.connectionLost, notifyServer: true)
            }
        }
        return fx
    }

    private mutating func tick() -> [CallEffect] {
        guard isLive else { return [] }
        var fx: [CallEffect] = []
        if let since = activeSince, state?.phase == .active || state?.phase == .reconnecting {
            let e = clock.now.timeIntervalSince(since)
            if e != state?.elapsed { update { $0.elapsed = e }; fx.append(.publish(state)) }
        }
        fx += reconnectActions(reconnect.tick(at: clock.now))
        return fx
    }

    private mutating func stats(_ s: MediaStats) -> [CallEffect] {
        guard isLive, state?.phase == .active || state?.phase == .reconnecting else { return [] }
        let out = adaptor.ingest(s)
        update {
            $0.effectiveMode = out.mode
            $0.quality = out.quality
            $0.isVideoOn = out.video != nil
            if $0.phase != .reconnecting { $0.banner = out.banner }
            $0.bytesUsed = s.bytesSent + s.bytesReceived + 28 * (s.packetsSent + s.packetsReceived)
        }
        var fx = syncMedia()
        fx.append(.publish(state))
        return fx + renegotiationIfNeeded()
    }

    /// Instant media changes: bitrate and video tier follow the adaptor without renegotiation.
    private mutating func syncMedia() -> [CallEffect] {
        var fx: [CallEffect] = []
        let mode = adaptor.currentMode, tier = adaptor.currentTier
        if mode != lastSentMode {
            lastSentMode = mode
            fx.append(.setAudioMaxBitrate(AudioProfile.for(mode).maxAverageBitrate))
        }
        // The media layer clamps the tier to the ceiling it is given, so a ceiling change re-sends a running tier.
        let ceiling = effectiveCeiling
        if tier != lastSentTier || (tier != nil && ceiling != lastSentCeiling) {
            lastSentTier = tier
            lastSentCeiling = ceiling
            fx.append(.setVideo(tier, ceiling: ceiling))
        }
        return fx
    }

    /// A change of my send ptime, FEC or DTX needs a new offer; bitrate-only changes do not.
    private mutating func renegotiationIfNeeded() -> [CallEffect] {
        guard isLive, state?.phase == .active, remoteDescriptionSet, answerSent, let negotiated = negotiatedSend else { return [] }
        let want = AudioProfile.for(state?.effectiveMode ?? ownCeiling)
        guard want.ptime != negotiated.ptime || want.inbandFEC != negotiated.inbandFEC || want.dtx != negotiated.dtx else { return [] }
        let now = clock.now
        if renegotiateOutstanding, let since = outstandingSince, now.timeIntervalSince(since) < Self.restartWindow { return [] }
        if let since = pendingRenegotiateSince, now.timeIntervalSince(since) < Self.staleAnswerAfter { return [] }
        renegotiateOutstanding = true
        outstandingSince = now
        return [.makeOffer(receive: .for(ownCeiling), iceRestart: false)]
    }

    private func modeChangeSignal() -> [CallEffect] {
        guard answerSent else { return [] }   // before that, the ring/offer/answer ceiling already carries it
        return [.sendSignal(CallSignalPayload(kind: .modeChange, callID: callID, ceiling: ownCeiling))]
    }

    private mutating func setPreference(_ p: ModePreference) -> [CallEffect] {
        guard isLive else { return [] }
        let before = ownCeiling
        update { $0.inCallPreference = p }
        adaptor.setCeiling(effectiveCeiling)
        let mode = adaptor.currentMode
        update { $0.effectiveMode = mode }
        var fx = syncMedia()
        if ownCeiling != before { fx += modeChangeSignal() }
        fx.append(.publish(state))
        return fx + renegotiationIfNeeded()
    }

    private mutating func setVideo(_ on: Bool) -> [CallEffect] {
        guard isLive else { return [] }
        adaptor.setVideoWanted(on)
        let tier = adaptor.currentTier
        lastSentTier = tier
        lastSentCeiling = effectiveCeiling
        update { $0.isVideoOn = tier != nil }
        return [.setVideo(tier, ceiling: effectiveCeiling), .publish(state)]
    }

    private mutating func contextChanged(_ c: CallContext) -> [CallEffect] {
        let before = ownCeiling
        context = c
        guard isLive else { return [] }
        adaptor.setCeiling(effectiveCeiling)
        let mode = adaptor.currentMode
        update { $0.network = c.network; $0.effectiveMode = mode }
        var fx = syncMedia()
        if ownCeiling != before { fx += modeChangeSignal() }
        fx.append(.publish(state))
        return fx + renegotiationIfNeeded()
    }

    private mutating func hangup() -> [CallEffect] {
        guard isLive else { return [] }
        if !outgoing, state?.phase == .incoming { return decline() }
        return end(.hungUp, notifyServer: true)
    }

    // MARK: - Helpers

    var hasBufferedState: Bool {
        !pendingLocalCandidates.isEmpty || !earlyRemoteCandidates.isEmpty || answeringDevice != nil
    }

    private var isLive: Bool { state.map { !$0.isEnded } ?? false }

    private mutating func setPeerCeiling(_ c: DataMode) {
        peerCeiling = c
        adaptor.setCeiling(effectiveCeiling)
        let mode = adaptor.currentMode
        update { $0.effectiveMode = mode }
    }

    private mutating func markActive() {
        if !everActive { everActive = true; activeSince = clock.now }
    }

    private mutating func update(_ change: (inout CallState) -> Void) {
        guard var s = state else { return }
        change(&s)
        state = s
    }

    private mutating func live(_ change: (inout CallState) -> Void) -> [CallEffect]? {
        guard isLive else { return nil }
        update(change)
        return [.publish(state)]
    }

    private mutating func end(_ reason: CallEndReason, notifyServer: Bool) -> [CallEffect] {
        guard isLive, let s = state else { return [] }
        let duration = activeSince.map { clock.now.timeIntervalSince($0) } ?? 0
        let wasActive = everActive
        update {
            $0.phase = .ended(reason)
            if reason != .connectionLost { $0.banner = nil }
            if wasActive { $0.elapsed = duration }
        }
        let summary: CallSummary
        if everActive {
            summary = s.isVideoCall ? .video(duration: duration) : .audio(duration: duration)
        } else {
            summary = outgoing ? .audio(duration: 0) : (s.isVideoCall ? .missedVideo : .missedAudio)
        }
        pendingLocalCandidates = []
        earlyRemoteCandidates = [:]
        localOfferSDP = nil
        pendingRenegotiateSince = nil
        outstandingSince = nil
        lastHonouredRequest = nil
        answeringDevice = nil
        pinnedFingerprint = nil
        renegotiateOutstanding = false
        var fx: [CallEffect] = []
        if notifyServer { fx.append(.sendHangup) }
        fx.append(.closeMedia)
        if reason != .answeredElsewhere { fx.append(.recordHistory(summary, outgoing: outgoing)) }
        fx.append(.publish(state))
        return fx
    }

    private static func reason(_ server: String) -> CallEndReason {
        switch server {
        case "declined": .declined
        case "missed": .missed
        case "hangup": .hungUp
        case "answered_elsewhere": .answeredElsewhere
        default: .failed
        }
    }
}
