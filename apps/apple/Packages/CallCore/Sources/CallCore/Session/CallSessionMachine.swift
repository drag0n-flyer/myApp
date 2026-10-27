import Foundation
import AppCore

/// The call state machine: inputs in, effects out, no I/O. Outgoing (caller) paths.
public struct CallSessionMachine: Sendable {
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
    private var answerSent = false
    private var pendingLocalCandidates: [IceCandidate] = []
    /// Remote candidates received before we can use them, by sending device.
    private var earlyRemoteCandidates: [String: [IceCandidate]] = [:]
    private var activeSince: Date?
    private var everActive = false
    private var lastSentMode: DataMode?
    private var lastSentTier: VideoTier?

    public init(context: CallContext, clock: Clock) {
        self.context = context
        self.clock = clock
    }

    public mutating func handle(_ input: CallInput) -> [CallEffect] {
        switch input {
        case .start(let contact, let video, let id): return start(contact, video, id)
        case .incoming, .answer, .decline: return []   // incoming calls: task S2
        case .hangup: return hangup()
        case .setPreference(let p): return setPreference(p)
        case .setVideo(let on): return setVideo(on)
        case .setMuted(let on): return live { $0.isMuted = on }.map { $0 + [.setMuted(on)] } ?? []
        case .setSpeaker(let on): return live { $0.isSpeakerOn = on } ?? []
        case .server(let e): return server(e)
        case .remote(let p, let device): return remote(p, device)
        case .remoteRejected: return end(.failed, notifyServer: true)
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
        callID = id
        outgoing = true
        peerCeiling = nil
        answeringDevice = nil; remoteDescriptionSet = false; answerSent = false
        pendingLocalCandidates = []; earlyRemoteCandidates = [:]
        activeSince = nil; everActive = false; lastSentMode = nil; lastSentTier = nil
        reconnect = ReconnectPolicy(isCaller: true)

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

    private mutating func server(_ e: CallServerEvent) -> [CallEffect] {
        switch e {
        case .answered(let id, let device, _):
            guard id == callID, isLive, answeringDevice == nil else { return [] }
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

    private mutating func remote(_ p: CallSignalPayload, _ device: String?) -> [CallEffect] {
        guard p.callID == callID, isLive else { return [] }
        switch p.kind {
        case .candidate:
            guard let c = p.candidate else { return [] }
            guard let answering = answeringDevice else {
                earlyRemoteCandidates[device ?? "", default: []].append(c)   // before call.answered: per device
                return []
            }
            if let device, device != answering { return [] }
            if remoteDescriptionSet { return [.addCandidate(c)] }
            earlyRemoteCandidates[answering, default: []].append(c)
            return []
        case .offer:
            guard let answering = answeringDevice, !remoteDescriptionSet, device == nil || device == answering else { return [] }
            guard let sdp = p.sdp, let claimed = p.dtlsFingerprint,
                  SDPMunger.fingerprint(in: sdp) == claimed else {
                return end(.failed, notifyServer: true)
            }
            if let c = p.ceiling { setPeerCeiling(c) }
            remoteDescriptionSet = true
            var fx: [CallEffect] = [.acceptOffer(sdp: sdp, send: .for(state?.effectiveMode ?? ownCeiling),
                                                receive: .for(ownCeiling))]
            for c in earlyRemoteCandidates.removeValue(forKey: answering) ?? [] { fx.append(.addCandidate(c)) }
            update { $0.phase = .connecting }
            fx.append(.publish(state))
            return fx
        case .renegotiateAnswer:
            guard remoteDescriptionSet, let sdp = p.sdp else { return [] }
            return [.acceptAnswer(sdp: sdp, send: .for(state?.effectiveMode ?? ownCeiling))]
        case .modeChange:
            if let c = p.ceiling { setPeerCeiling(c) }
            return [.publish(state)]
        case .ring, .answer, .renegotiate:
            return []
        }
    }

    private mutating func localAnswer(_ sdp: String) -> [CallEffect] {
        guard isLive, remoteDescriptionSet, !answerSent else { return [] }
        answerSent = true
        var fx: [CallEffect] = [.sendSignal(CallSignalPayload(
            kind: .answer, callID: callID, sdp: sdp, dtlsFingerprint: SDPMunger.fingerprint(in: sdp), ceiling: ownCeiling))]
        for c in pendingLocalCandidates { fx.append(.sendSignal(CallSignalPayload(kind: .candidate, callID: callID, candidate: c))) }
        pendingLocalCandidates = []
        return fx
    }

    private mutating func localOffer(_ sdp: String) -> [CallEffect] {
        // An ICE-restart offer from this side.
        guard isLive, answerSent else { return [] }
        return [.sendSignal(CallSignalPayload(kind: .renegotiate, callID: callID, sdp: sdp, ceiling: ownCeiling))]
    }

    private mutating func media(_ e: MediaEvent) -> [CallEffect] {
        guard isLive else { return [] }
        switch e {
        case .localCandidate(let c):
            if answerSent { return [.sendSignal(CallSignalPayload(kind: .candidate, callID: callID, candidate: c))] }
            pendingLocalCandidates.append(c)
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
                fx.append(.makeOffer(receive: .for(ownCeiling), iceRestart: true))
            case .requestRestart:
                break   // callee path: task S2
            case .recovered:
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
        var fx: [CallEffect] = []
        if out.mode != lastSentMode {
            lastSentMode = out.mode
            fx.append(.setAudioMaxBitrate(AudioProfile.for(out.mode).maxAverageBitrate))
        }
        if out.video != lastSentTier {
            lastSentTier = out.video
            fx.append(.setVideo(out.video))
        }
        fx.append(.publish(state))
        return fx
    }

    private mutating func setPreference(_ p: ModePreference) -> [CallEffect] {
        guard isLive else { return [] }
        update { $0.inCallPreference = p }
        adaptor.setCeiling(effectiveCeiling)
        let mode = adaptor.currentMode
        update { $0.effectiveMode = mode }
        return [.publish(state)]
    }

    private mutating func setVideo(_ on: Bool) -> [CallEffect] {
        guard isLive else { return [] }
        adaptor.setVideoWanted(on)
        let tier = adaptor.currentTier
        lastSentTier = tier
        update { $0.isVideoOn = tier != nil }
        return [.setVideo(tier), .publish(state)]
    }

    private mutating func contextChanged(_ c: CallContext) -> [CallEffect] {
        context = c
        guard isLive else { return [] }
        adaptor.setCeiling(effectiveCeiling)
        let mode = adaptor.currentMode
        update { $0.network = c.network; $0.effectiveMode = mode }
        return [.publish(state)]
    }

    private mutating func hangup() -> [CallEffect] {
        guard isLive else { return [] }
        return end(.hungUp, notifyServer: true)
    }

    // MARK: - Helpers

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
        var fx: [CallEffect] = []
        if notifyServer { fx.append(.sendHangup) }
        fx += [.closeMedia, .recordHistory(summary, outgoing: outgoing), .publish(state)]
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
