import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
private let server = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "h")
private func ctx(isContact: Bool = true, network: NetworkType = .mobile) -> CallContext {
    var s = UserSettings(ipHiding: .nonContacts)
    s.call.callDataMode = .auto
    return CallContext(server: server, settings: s, network: network, isContact: isContact)
}
private let fp = "sha-256 AB:CD:EF"
private func sdp(_ f: String = fp) -> String { "v=0\r\na=fingerprint:\(f)\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" }
private func offer(_ f: String = fp, ceiling: DataMode? = nil) -> CallSignalPayload {
    CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(f), dtlsFingerprint: f, ceiling: ceiling)
}
private let cand = IceCandidate(sdp: "candidate:1", sdpMid: "0", sdpMLineIndex: 0)

private func started(video: Bool = false, context: CallContext = ctx()) -> (CallSessionMachine, ManualClock, [CallEffect]) {
    let clock = ManualClock()
    var m = CallSessionMachine(context: context, clock: clock)
    let fx = m.handle(.start(contact: bob, video: video, callID: "c1"))
    return (m, clock, fx)
}
private func answered(_ m: inout CallSessionMachine, device: String = "d1") {
    _ = m.handle(.server(.answered(callID: "c1", deviceID: device, payload: Data())))
}
private func published(_ fx: [CallEffect]) -> CallState? {
    for e in fx.reversed() { if case .publish(let s) = e { return s } }
    return nil
}

@Suite struct OutgoingCallTests {
    @Test func startEmitsInviteAndPrewarm() {
        let (m, _, fx) = started()
        let ceiling = DataModePolicy.ceiling(server: server, settings: ctx().settings.call, network: .mobile, inCall: nil)
        #expect(fx.contains(.configureMedia(policy: .all, video: false)))
        #expect(fx.contains(.sendInvite(CallSignalPayload(kind: .ring, callID: "c1", video: false, ceiling: ceiling))))
        #expect(published(fx)?.phase == .ringing)
        #expect(m.state?.effectiveMode == ceiling)
    }

    @Test func strangerForcesRelay() {
        let (_, _, fx) = started(context: ctx(isContact: false))
        #expect(fx.contains(.configureMedia(policy: .relay, video: false)))
    }

    @Test func answeredOfferIsAccepted() {
        var (m, _, _) = started()
        let ceiling = DataModePolicy.ceiling(server: server, settings: ctx().settings.call, network: .mobile, inCall: nil)
        answered(&m)
        let fx = m.handle(.remote(offer(), fromDeviceID: "d1"))
        #expect(fx.contains(.acceptOffer(sdp: sdp(), send: .for(m.state!.effectiveMode), receive: .for(ceiling))))
        let fx2 = m.handle(.localAnswer(sdp("sha-256 11:22")))
        #expect(fx2.contains(.sendSignal(CallSignalPayload(kind: .answer, callID: "c1", sdp: sdp("sha-256 11:22"),
                                                          dtlsFingerprint: "sha-256 11:22", ceiling: ceiling))))
    }

    @Test func fingerprintMismatchEndsCall() {
        var (m, _, _) = started()
        answered(&m)
        var bad = offer()
        bad.dtlsFingerprint = "sha-256 00:00"
        let fx = m.handle(.remote(bad, fromDeviceID: "d1"))
        #expect(m.state?.phase == .ended(.failed))
        #expect(fx.contains(.closeMedia) && fx.contains(.sendHangup))
        #expect(!fx.contains { if case .acceptOffer = $0 { true } else { false } })
    }

    @Test func peerCeilingLimitsSendMode() {
        var (m, _, _) = started()
        answered(&m)
        let fx = m.handle(.remote(offer(ceiling: .dataSaver), fromDeviceID: "d1"))
        #expect(fx.contains { if case .acceptOffer(_, let send, _) = $0 { send.mode == .dataSaver } else { false } })
    }

    @Test func iceConnectedGoesActive() {
        var (m, _, _) = started()
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp()))
        _ = m.handle(.media(.iceState(.connected)))
        #expect(m.state?.phase == .active)
    }

    @Test func noAnswerEnds() {
        var (m, _, _) = started()
        let fx = m.handle(.server(.ended(callID: "c1", reason: "missed")))
        #expect(m.state?.phase == .ended(.missed))
        #expect(fx.contains(.closeMedia))
        #expect(fx.contains(.recordHistory(.audio(duration: 0), outgoing: true)))
    }

    @Test func declinedEnds() {
        var (m, _, _) = started()
        _ = m.handle(.server(.ended(callID: "c1", reason: "declined")))
        #expect(m.state?.phase == .ended(.declined))
    }

    @Test func endedForOtherCallIsIgnored() {
        var (m, _, _) = started()
        #expect(m.handle(.server(.ended(callID: "other", reason: "missed"))).isEmpty)
        #expect(m.state?.phase == .ringing)
    }

    @Test func callerTrickles() {
        var (m, _, _) = started()
        // Before call.answered: buffered.
        #expect(m.handle(.media(.localCandidate(cand))).isEmpty)
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1"))
        let fx = m.handle(.localAnswer(sdp()))
        let candidateSignal = CallEffect.sendSignal(CallSignalPayload(kind: .candidate, callID: "c1", candidate: cand))
        #expect(fx.contains(candidateSignal))
        // The answer goes out before the buffered candidate.
        let answerIdx = fx.firstIndex { if case .sendSignal(let p) = $0 { p.kind == .answer } else { false } }
        #expect(answerIdx == fx.firstIndex(of: candidateSignal).map { $0 - 1 })
        // After: sent at once.
        let c2 = IceCandidate(sdp: "candidate:2", sdpMid: "0", sdpMLineIndex: 0)
        #expect(m.handle(.media(.localCandidate(c2))) ==
                [.sendSignal(CallSignalPayload(kind: .candidate, callID: "c1", candidate: c2))])
    }

    @Test func remoteCandidatesKeepOnlyAnsweringDevice() {
        var (m, _, _) = started()
        let a = CallSignalPayload(kind: .candidate, callID: "c1", candidate: IceCandidate(sdp: "A", sdpMid: "0", sdpMLineIndex: 0))
        let b = CallSignalPayload(kind: .candidate, callID: "c1", candidate: IceCandidate(sdp: "B", sdpMid: "0", sdpMLineIndex: 0))
        #expect(m.handle(.remote(a, fromDeviceID: "d1")).isEmpty)
        #expect(m.handle(.remote(b, fromDeviceID: "d2")).isEmpty)
        answered(&m, device: "d2")
        let fx = m.handle(.remote(offer(), fromDeviceID: "d2"))
        #expect(fx.contains(.addCandidate(b.candidate!)))
        #expect(!fx.contains(.addCandidate(a.candidate!)))
        // Later candidates: only from the answering device.
        #expect(m.handle(.remote(a, fromDeviceID: "d1")).isEmpty)
        #expect(m.handle(.remote(b, fromDeviceID: "d2")) == [.addCandidate(b.candidate!)])
    }

    @Test func elapsedCountsFromActive() {
        var (m, clock, _) = started()
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp()))
        clock.advance(3)
        _ = m.handle(.tick)
        #expect(m.state?.elapsed == 0)   // not active yet
        _ = m.handle(.media(.iceState(.connected)))
        clock.advance(1); _ = m.handle(.tick)
        clock.advance(1); _ = m.handle(.tick)
        #expect(m.state?.elapsed == 2)
    }

    @Test func hangupAfterActiveRecordsDuration() {
        var (m, clock, _) = started(video: true, context: ctx(network: .wifi))
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp()))
        _ = m.handle(.media(.iceState(.connected)))
        clock.advance(7)
        let fx = m.handle(.hangup)
        #expect(fx.contains(.sendHangup) && fx.contains(.closeMedia))
        #expect(fx.contains(.recordHistory(.video(duration: 7), outgoing: true)))
        #expect(m.state?.phase == .ended(.hungUp))
        // Exactly once.
        #expect(m.handle(.server(.ended(callID: "c1", reason: "hangup"))).isEmpty)
    }

    @Test func disconnectShowsReconnectingThenRecovers() {
        var (m, clock, _) = started()
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp()))
        _ = m.handle(.media(.iceState(.connected)))
        _ = m.handle(.media(.iceState(.disconnected)))
        #expect(m.state?.phase == .reconnecting && m.state?.banner == .reconnecting)
        clock.advance(2.5)
        let fx = m.handle(.tick)
        #expect(fx.contains { if case .makeOffer(_, let r) = $0 { r } else { false } })
        _ = m.handle(.media(.iceState(.connected)))
        #expect(m.state?.phase == .active && m.state?.banner == nil)
    }

    @Test func connectionLostAfter30s() {
        var (m, clock, _) = started()
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp()))
        _ = m.handle(.media(.iceState(.connected)))
        _ = m.handle(.media(.iceState(.disconnected)))
        clock.advance(30)
        let fx = m.handle(.tick)
        #expect(m.state?.phase == .ended(.connectionLost))
        #expect(fx.contains(.sendHangup) && fx.contains(.closeMedia))
    }

    @Test func statsDriveAdaptorAndBytes() {
        var (m, _, _) = started(video: true, context: ctx(network: .wifi))
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp()))
        _ = m.handle(.media(.iceState(.connected)))
        let fx = m.handle(.stats(MediaStats(availableOutgoingBitrate: 20_000, bytesSent: 1000, bytesReceived: 500,
                                            packetsSent: 10, packetsReceived: 5)))
        #expect(m.state?.effectiveMode == .ultraLow && m.state?.quality == .weak)
        #expect(fx.contains(.setAudioMaxBitrate(10_000)) && fx.contains(.setVideo(nil, ceiling: .high)))
        #expect(m.state?.bytesUsed == Int64(1500 + 28 * 15))
    }

    @Test func userControls() {
        var (m, _, _) = started()
        #expect(m.handle(.setMuted(true)).contains(.setMuted(true)))
        #expect(m.state?.isMuted == true)
        _ = m.handle(.setSpeaker(true))
        #expect(m.state?.isSpeakerOn == true)
        _ = m.handle(.setPreference(.fixed(.dataSaver)))
        #expect(m.state?.effectiveMode == .dataSaver && m.state?.inCallPreference == .fixed(.dataSaver))
    }

    @Test func remoteRejectedEndsCall() {
        var (m, _, _) = started()
        answered(&m)
        let fx = m.handle(.remoteRejected(.wrongSender, fromDeviceID: "d1"))
        #expect(m.state?.phase == .ended(.failed) && fx.contains(.sendHangup))
    }

    // MARK: - Fix round 1

    private func activeMachine() -> (CallSessionMachine, ManualClock) {
        var (m, clock, _) = started()
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp()))
        _ = m.handle(.media(.iceState(.connected)))
        return (m, clock)
    }
    private func renegotiating(_ m: inout CallSessionMachine, _ clock: ManualClock) {
        _ = m.handle(.media(.iceState(.disconnected)))
        clock.advance(2.5)
        _ = m.handle(.tick)
        _ = m.handle(.localOffer(sdp()))
    }
    private func reAnswer(_ f: String, sdpFP: String? = nil) -> CallSignalPayload {
        CallSignalPayload(kind: .renegotiateAnswer, callID: "c1", sdp: sdp(sdpFP ?? f), dtlsFingerprint: f)
    }

    @Test func renegotiateAnswerAccepted() {
        var (m, clock) = activeMachine()
        renegotiating(&m, clock)
        let fx = m.handle(.remote(reAnswer(fp), fromDeviceID: "d1"))
        #expect(fx.contains { if case .acceptAnswer = $0 { true } else { false } })
        // Only one answer per outstanding renegotiate.
        #expect(m.handle(.remote(reAnswer(fp), fromDeviceID: "d1")).isEmpty)
    }

    @Test func renegotiateAnswerFromOtherDeviceIgnored() {
        var (m, clock) = activeMachine()
        renegotiating(&m, clock)
        #expect(m.handle(.remote(reAnswer(fp), fromDeviceID: "d2")).isEmpty)
        #expect(m.state?.isEnded == false)
    }

    @Test func renegotiateAnswerWithoutOutstandingIgnored() {
        var (m, _) = activeMachine()
        #expect(m.handle(.remote(reAnswer(fp), fromDeviceID: "d1")).isEmpty)
        #expect(m.state?.isEnded == false)
    }

    @Test func renegotiateAnswerFingerprintNotMatchingSdpEnds() {
        var (m, clock) = activeMachine()
        renegotiating(&m, clock)
        let fx = m.handle(.remote(reAnswer(fp, sdpFP: "sha-256 99:99"), fromDeviceID: "d1"))
        #expect(m.state?.phase == .ended(.failed) && fx.contains(.sendHangup))
    }

    @Test func renegotiateAnswerFingerprintNotPinnedEnds() {
        var (m, clock) = activeMachine()
        renegotiating(&m, clock)
        let other = "sha-256 77:77"   // consistent with its own SDP, but not the pinned one
        let fx = m.handle(.remote(reAnswer(other), fromDeviceID: "d1"))
        #expect(m.state?.phase == .ended(.failed) && fx.contains(.sendHangup))
        #expect(!fx.contains { if case .acceptAnswer = $0 { true } else { false } })
    }

    @Test func modeChangeOnlyFromAnsweringDevice() {
        var (m, _) = activeMachine()
        let mc = CallSignalPayload(kind: .modeChange, callID: "c1", ceiling: .ultraLow)
        #expect(m.handle(.remote(mc, fromDeviceID: "d2")).isEmpty)
        #expect(!m.handle(.remote(mc, fromDeviceID: "d1")).isEmpty)
        #expect(m.state?.effectiveMode == .ultraLow)
    }

    @Test func remoteRejectedOnlyFromAnsweringDevice() {
        var (m, _, _) = started()
        #expect(m.handle(.remoteRejected(.undecryptable, fromDeviceID: "d1")).isEmpty)   // before answered
        answered(&m)
        #expect(m.handle(.remoteRejected(.undecryptable, fromDeviceID: "d2")).isEmpty)
        #expect(m.state?.isEnded == false)
        _ = m.handle(.remoteRejected(.undecryptable, fromDeviceID: "d1"))
        #expect(m.state?.phase == .ended(.failed))
    }

    @Test func recoverBeforeActiveStartsClock() {
        var (m, clock, _) = started()
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp()))
        _ = m.handle(.media(.iceState(.disconnected)))     // never was active
        _ = m.handle(.media(.iceState(.connected)))        // recovered
        #expect(m.state?.phase == .active)
        clock.advance(4)
        _ = m.handle(.tick)
        #expect(m.state?.elapsed == 4)
        let fx = m.handle(.hangup)
        #expect(fx.contains(.recordHistory(.audio(duration: 4), outgoing: true)))
    }

    @Test func endClearsBuffers() {
        var (m, _, _) = started()
        _ = m.handle(.media(.localCandidate(cand)))
        _ = m.handle(.remote(CallSignalPayload(kind: .candidate, callID: "c1", candidate: cand), fromDeviceID: "d1"))
        answered(&m)
        #expect(m.hasBufferedState)
        _ = m.handle(.hangup)
        #expect(!m.hasBufferedState)
    }
}
