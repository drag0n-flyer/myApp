import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
private let server = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "h")
private func ctx(isContact: Bool = true, network: NetworkType = .wifi) -> CallContext {
    CallContext(server: server, settings: UserSettings(ipHiding: .nonContacts), network: network, isContact: isContact)
}
private let myFP = "sha-256 11:22:33"
private let callerFP = "sha-256 AB:CD:EF"
private func sdp(_ f: String) -> String { "v=0\r\na=fingerprint:\(f)\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" }
private func ring(video: Bool, ceiling: DataMode? = nil) -> CallSignalPayload {
    CallSignalPayload(kind: .ring, callID: "c1", video: video, ceiling: ceiling)
}
private func answerPayload(_ f: String = callerFP, sdpFP: String? = nil, ceiling: DataMode? = nil) -> CallSignalPayload {
    CallSignalPayload(kind: .answer, callID: "c1", sdp: sdp(sdpFP ?? f), dtlsFingerprint: f, ceiling: ceiling)
}
private func cand(_ n: Int) -> IceCandidate { IceCandidate(sdp: "candidate:\(n)", sdpMid: "0", sdpMLineIndex: 0) }
private func candPayload(_ n: Int) -> CallSignalPayload { CallSignalPayload(kind: .candidate, callID: "c1", candidate: cand(n)) }
private func isAcceptOffer(_ e: CallEffect) -> Bool { if case .acceptOffer = e { true } else { false } }
private func isAcceptAnswer(_ e: CallEffect) -> Bool { if case .acceptAnswer = e { true } else { false } }

private func ringing(video: Bool = true, context: CallContext = ctx(), ceiling: DataMode? = nil)
    -> (CallSessionMachine, ManualClock, [CallEffect]) {
    let clock = ManualClock()
    var m = CallSessionMachine(context: context, clock: clock)
    let fx = m.handle(.incoming(callID: "c1", contact: bob, fromDeviceID: "dA", ring: ring(video: video, ceiling: ceiling)))
    return (m, clock, fx)
}
/// Ringing, local offer ready, answered, caller's answer accepted, ICE connected.
private func active(video: Bool = false) -> (CallSessionMachine, ManualClock) {
    var (m, clock, _) = ringing(video: video)
    _ = m.handle(.localOffer(sdp(myFP)))
    _ = m.handle(.answer(video: video))
    _ = m.handle(.remote(answerPayload(), fromDeviceID: "dA"))
    _ = m.handle(.media(.iceState(.connected)))
    return (m, clock)
}

@Suite struct IncomingCallTests {
    /// A push-woken device never saw the WebSocket ring, so it does not know the caller's device. It binds to the
    /// device whose answer arrives (the payload is already checked to come from the caller's account).
    @Test func pushRingBindsToTheFirstAnsweringDevice() {
        let clock = ManualClock()
        var m = CallSessionMachine(context: ctx(), clock: clock)
        _ = m.handle(.incoming(callID: "c1", contact: bob, fromDeviceID: "", ring: ring(video: false)))
        _ = m.handle(.localOffer(sdp(myFP)))
        _ = m.handle(.answer(video: false))
        let fx = m.handle(.remote(answerPayload(), fromDeviceID: "dZ"))
        #expect(fx.contains(where: isAcceptAnswer))
        // later signals from another device are ignored
        #expect(m.handle(.remote(candPayload(1), fromDeviceID: "dOther")).isEmpty)
        #expect(m.handle(.remote(candPayload(1), fromDeviceID: "dZ")).contains(.addCandidate(cand(1))))
    }

    @Test func knownDeviceStaysBound() {
        var (m, _, _) = ringing()
        _ = m.handle(.localOffer(sdp(myFP)))
        _ = m.handle(.answer(video: false))
        #expect(!m.handle(.remote(answerPayload(), fromDeviceID: "dOther")).contains(where: isAcceptAnswer))
    }

    @Test func incomingPrewarmsAndOffersLocally() {
        let (m, _, fx) = ringing(video: true)
        let ceiling = DataModePolicy.ceiling(server: server, settings: ctx().settings.call, network: .wifi, inCall: nil)
        #expect(fx.contains(.configureMedia(policy: .all, video: true)))
        #expect(fx.contains(.makeOffer(receive: .for(ceiling), iceRestart: false)))
        #expect(m.state?.phase == .incoming && m.state?.isVideoCall == true)
        #expect(!fx.contains { switch $0 { case .sendInvite, .sendAnswer, .sendSignal, .sendDecline, .sendHangup: true; default: false } })
        let cfg = fx.firstIndex(of: .configureMedia(policy: .all, video: true))!
        #expect(cfg < fx.firstIndex(of: .makeOffer(receive: .for(ceiling), iceRestart: false))!)
    }

    @Test func mobileDataStartsVideoOff() {
        let (_, _, fx) = ringing(video: true, context: ctx(network: .mobile))
        #expect(fx.contains(.configureMedia(policy: .all, video: false)))
    }

    @Test func strangerIncomingForcesRelay() {
        let (_, _, fx) = ringing(video: false, context: ctx(isContact: false))
        #expect(fx.contains(.configureMedia(policy: .relay, video: false)))
    }

    @Test func answerSendsOffer() {
        var (m, _, _) = ringing()
        let ceiling = DataModePolicy.ceiling(server: server, settings: ctx().settings.call, network: .wifi, inCall: nil)
        #expect(m.handle(.localOffer(sdp(myFP))).isEmpty)           // nothing leaves before the answer
        #expect(m.handle(.media(.localCandidate(cand(1)))).isEmpty)
        #expect(m.handle(.media(.localCandidate(cand(2)))).isEmpty)
        let fx = m.handle(.answer(video: true))
        let offer = CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(myFP), dtlsFingerprint: myFP, ceiling: ceiling)
        #expect(fx.first(where: { if case .sendAnswer = $0 { true } else { false } }) == .sendAnswer(offer))
        let tail = fx.drop { if case .sendAnswer = $0 { false } else { true } }.dropFirst()
        #expect(tail.filter { if case .sendSignal = $0 { true } else { false } } == [
            .sendSignal(CallSignalPayload(kind: .candidate, callID: "c1", candidate: cand(1))),
            .sendSignal(CallSignalPayload(kind: .candidate, callID: "c1", candidate: cand(2))),
        ])
        #expect(m.state?.phase == .connecting && m.state?.isVideoOn == true)
        // After the answer, candidates go out at once.
        #expect(m.handle(.media(.localCandidate(cand(3)))) ==
                [.sendSignal(CallSignalPayload(kind: .candidate, callID: "c1", candidate: cand(3)))])
    }

    @Test func answerBeforeLocalOfferWaits() {
        var (m, _, _) = ringing()
        let early = m.handle(.answer(video: false))
        #expect(!early.contains { if case .sendAnswer = $0 { true } else { false } })
        let fx = m.handle(.localOffer(sdp(myFP)))
        #expect(fx.contains { if case .sendAnswer(let p) = $0 { p.kind == .offer && p.dtlsFingerprint == myFP } else { false } })
    }

    @Test func localOfferWithoutFingerprintEndsCall() {
        var (m, _, _) = ringing()
        _ = m.handle(.answer(video: false))
        let fx = m.handle(.localOffer("v=0\r\n"))
        #expect(m.state?.phase == .ended(.failed) && fx.contains(.sendHangup))
        #expect(!fx.contains { if case .sendAnswer = $0 { true } else { false } })
    }

    @Test func remoteAnswerAccepted() {
        var (m, _, _) = ringing()
        _ = m.handle(.localOffer(sdp(myFP)))
        _ = m.handle(.answer(video: false))
        let fx = m.handle(.remote(answerPayload(), fromDeviceID: "dA"))
        #expect(fx.contains(.acceptAnswer(sdp: sdp(callerFP), send: .for(m.state!.effectiveMode))))
    }

    @Test func remoteAnswerChecks() {
        // Wrong device: ignored.
        var (m, _, _) = ringing()
        _ = m.handle(.localOffer(sdp(myFP)))
        _ = m.handle(.answer(video: false))
        #expect(m.handle(.remote(answerPayload(), fromDeviceID: "dB")).isEmpty)
        #expect(m.state?.isEnded == false)
        // Fingerprint that does not match the SDP: call ends.
        let fx = m.handle(.remote(answerPayload(sdpFP: "sha-256 99:99"), fromDeviceID: "dA"))
        #expect(m.state?.phase == .ended(.failed) && fx.contains(.sendHangup))
        // An answer before we answered is ignored.
        var (n, _, _) = ringing()
        _ = n.handle(.localOffer(sdp(myFP)))
        #expect(n.handle(.remote(answerPayload(), fromDeviceID: "dA")).isEmpty)
    }

    @Test func callerCandidatesBoundToCallerDevice() {
        var (m, _, _) = ringing()
        #expect(m.handle(.remote(candPayload(1), fromDeviceID: "dA")).isEmpty)   // buffered
        #expect(m.handle(.remote(candPayload(2), fromDeviceID: "dB")).isEmpty)   // not the caller's device
        _ = m.handle(.localOffer(sdp(myFP)))
        _ = m.handle(.answer(video: false))
        let fx = m.handle(.remote(answerPayload(), fromDeviceID: "dA"))
        #expect(fx.contains(.addCandidate(cand(1))) && !fx.contains(.addCandidate(cand(2))))
        #expect(m.handle(.remote(candPayload(3), fromDeviceID: "dA")) == [.addCandidate(cand(3))])
        #expect(m.handle(.remote(candPayload(4), fromDeviceID: "dB")).isEmpty)
    }

    @Test func answeredElsewhere() {
        var (m, _, _) = ringing()
        let fx = m.handle(.server(.ended(callID: "c1", reason: "answered_elsewhere")))
        #expect(m.state?.phase == .ended(.answeredElsewhere))
        #expect(fx.contains(.closeMedia))
        #expect(!fx.contains { if case .recordHistory = $0 { true } else { false } })
    }

    @Test func declineByUser() {
        var (m, _, _) = ringing(video: true)
        let fx = m.handle(.decline)
        #expect(fx.contains(.sendDecline) && fx.contains(.closeMedia))
        #expect(m.state?.phase == .ended(.declined))
        #expect(fx.contains(.recordHistory(.missedVideo, outgoing: false)))
        var (a, _, _) = ringing(video: false)
        #expect(a.handle(.decline).contains(.recordHistory(.missedAudio, outgoing: false)))
    }

    @Test func missedIncoming() {
        var (m, _, _) = ringing(video: true)
        let fx = m.handle(.server(.ended(callID: "c1", reason: "missed")))
        #expect(m.state?.phase == .ended(.missed))
        #expect(fx.contains(.recordHistory(.missedVideo, outgoing: false)))
    }

    @Test func answeredCallRecordsDuration() {
        var (m, clock) = active()
        clock.advance(9)
        let fx = m.handle(.hangup)
        #expect(fx.contains(.recordHistory(.audio(duration: 9), outgoing: false)))
    }

    @Test func peerCeilingFromRingLimitsSendMode() {
        var (m, _, _) = ringing(ceiling: .dataSaver)
        _ = m.handle(.localOffer(sdp(myFP)))
        _ = m.handle(.answer(video: false))
        let fx = m.handle(.remote(answerPayload(), fromDeviceID: "dA"))
        #expect(fx.contains(.acceptAnswer(sdp: sdp(callerFP), send: .for(.dataSaver))))
    }

    // MARK: Renegotiation on the callee

    @Test func calleeRequestsRestart() {
        var (m, clock) = active()
        _ = m.handle(.media(.iceState(.disconnected)))
        clock.advance(2.5)
        let fx = m.handle(.tick)
        #expect(fx.contains(.sendSignal(CallSignalPayload(kind: .renegotiate, callID: "c1"))))
    }

    @Test func calleeAnswersRenegotiateWithPinnedFingerprint() {
        var (m, _) = active()
        let re = CallSignalPayload(kind: .renegotiate, callID: "c1", sdp: sdp(callerFP), dtlsFingerprint: callerFP)
        #expect(m.handle(.remote(re, fromDeviceID: "dB")).isEmpty)               // wrong device
        let fx = m.handle(.remote(re, fromDeviceID: "dA"))
        #expect(fx.contains { isAcceptOffer($0) })
        let out = m.handle(.localAnswer(sdp(myFP)))
        #expect(out.contains(.sendSignal(CallSignalPayload(kind: .renegotiateAnswer, callID: "c1", sdp: sdp(myFP),
                                                          dtlsFingerprint: myFP, ceiling: DataModePolicy.ceiling(
                                                            server: server, settings: ctx().settings.call, network: .wifi, inCall: nil)))))
    }

    @Test func calleeRejectsRenegotiateWithWrongFingerprint() {
        for (claimed, sdpFP) in [("sha-256 77:77", "sha-256 77:77"), (callerFP, "sha-256 99:99")] {
            var (m, _) = active()
            let re = CallSignalPayload(kind: .renegotiate, callID: "c1", sdp: sdp(sdpFP), dtlsFingerprint: claimed)
            let fx = m.handle(.remote(re, fromDeviceID: "dA"))
            #expect(m.state?.phase == .ended(.failed) && fx.contains(.sendHangup))
            #expect(!fx.contains { isAcceptOffer($0) })
        }
    }

    @Test func calleeIgnoresRenegotiateBeforeActive() {
        var (m, _, _) = ringing()
        let re = CallSignalPayload(kind: .renegotiate, callID: "c1", sdp: sdp(callerFP), dtlsFingerprint: callerFP)
        #expect(m.handle(.remote(re, fromDeviceID: "dA")).isEmpty)
    }

    @Test func callerRespondsToRestartRequest() {
        var m = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = m.handle(.start(contact: bob, video: false, callID: "c1"))
        _ = m.handle(.server(.answered(callID: "c1", deviceID: "d1", payload: Data())))
        let offer = CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(myFP), dtlsFingerprint: myFP)
        _ = m.handle(.remote(offer, fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp(callerFP)))
        let req = CallSignalPayload(kind: .renegotiate, callID: "c1")
        #expect(m.handle(.remote(req, fromDeviceID: "d2")).isEmpty)
        let fx = m.handle(.remote(req, fromDeviceID: "d1"))
        #expect(fx.contains { if case .makeOffer(_, let r) = $0 { r } else { false } })
        // A renegotiate that carries an SDP is not accepted by the caller.
        let withSDP = CallSignalPayload(kind: .renegotiate, callID: "c1", sdp: sdp(myFP), dtlsFingerprint: myFP)
        #expect(m.handle(.remote(withSDP, fromDeviceID: "d1")).isEmpty)
    }

    // MARK: Buffers

    @Test func endClearsCalleeBuffers() {
        var (m, _, _) = ringing()
        _ = m.handle(.media(.localCandidate(cand(1))))
        _ = m.handle(.remote(candPayload(2), fromDeviceID: "dA"))
        #expect(m.hasBufferedState)
        _ = m.handle(.decline)
        #expect(!m.hasBufferedState)
    }

    @Test func earlyCandidatesCappedPerDevice() {
        var (m, _, _) = ringing()
        for n in 0..<200 { _ = m.handle(.remote(candPayload(n), fromDeviceID: "dA")) }
        _ = m.handle(.localOffer(sdp(myFP)))
        _ = m.handle(.answer(video: false))
        let fx = m.handle(.remote(answerPayload(), fromDeviceID: "dA"))
        #expect(fx.filter { if case .addCandidate = $0 { true } else { false } }.count == CallSessionMachine.maxEarlyCandidatesPerDevice)
    }

    @Test func earlyDevicesCappedOnCaller() {
        var m = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = m.handle(.start(contact: bob, video: false, callID: "c1"))
        let total = CallSessionMachine.maxEarlyDevices + 12
        for d in 0..<total { _ = m.handle(.remote(candPayload(d), fromDeviceID: "d\(d)")) }
        let last = "d\(total - 1)"
        _ = m.handle(.server(.answered(callID: "c1", deviceID: last, payload: Data())))
        let offer = CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(myFP), dtlsFingerprint: myFP)
        let fx = m.handle(.remote(offer, fromDeviceID: last))
        #expect(!fx.contains { if case .addCandidate = $0 { true } else { false } })   // beyond the cap: dropped
        // A device inside the cap is still kept.
        var n = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = n.handle(.start(contact: bob, video: false, callID: "c1"))
        for d in 0..<total { _ = n.handle(.remote(candPayload(d), fromDeviceID: "d\(d)")) }
        _ = n.handle(.server(.answered(callID: "c1", deviceID: "d0", payload: Data())))
        #expect(n.handle(.remote(offer, fromDeviceID: "d0")).contains(.addCandidate(cand(0))))
    }

    @Test func localPendingCandidatesCapped() {
        var (m, _, _) = ringing()
        for n in 0..<500 { _ = m.handle(.media(.localCandidate(cand(n)))) }
        _ = m.handle(.localOffer(sdp(myFP)))
        let fx = m.handle(.answer(video: false))
        #expect(fx.filter { if case .sendSignal = $0 { true } else { false } }.count == CallSessionMachine.maxPendingLocalCandidates)
    }

    // MARK: Fix round 1

    @Test func calleeVideoStartOffUsesEffectiveCeiling() {
        let (_, _, fx) = ringing(video: true, ceiling: .dataSaver)
        #expect(fx.contains(.configureMedia(policy: .all, video: false)))
    }

    @Test func callerIgnoresRestartRequestWhileOfferOutstanding() {
        var m = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = m.handle(.start(contact: bob, video: false, callID: "c1"))
        _ = m.handle(.server(.answered(callID: "c1", deviceID: "d1", payload: Data())))
        _ = m.handle(.remote(CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(myFP), dtlsFingerprint: myFP), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp(callerFP)))
        let req = CallSignalPayload(kind: .renegotiate, callID: "c1")
        #expect(!m.handle(.remote(req, fromDeviceID: "d1")).isEmpty)   // first request honoured
        #expect(m.handle(.remote(req, fromDeviceID: "d1")).isEmpty)    // offer outstanding
    }

    @Test func callerRateLimitsRequestsAndGatesOwnRestart() {
        let clock = ManualClock()
        var m = CallSessionMachine(context: ctx(), clock: clock)
        _ = m.handle(.start(contact: bob, video: false, callID: "c1"))
        _ = m.handle(.server(.answered(callID: "c1", deviceID: "d1", payload: Data())))
        _ = m.handle(.remote(CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(myFP), dtlsFingerprint: myFP), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp(callerFP)))
        _ = m.handle(.media(.iceState(.connected)))
        let req = CallSignalPayload(kind: .renegotiate, callID: "c1")
        _ = m.handle(.remote(req, fromDeviceID: "d1"))
        _ = m.handle(.localOffer(sdp(callerFP)))
        let answer = CallSignalPayload(kind: .renegotiateAnswer, callID: "c1", sdp: sdp(myFP), dtlsFingerprint: myFP)
        _ = m.handle(.remote(answer, fromDeviceID: "d1"))          // restart complete
        clock.advance(1)
        #expect(m.handle(.remote(req, fromDeviceID: "d1")).isEmpty)    // within the 5 s window
        clock.advance(4)
        #expect(!m.handle(.remote(req, fromDeviceID: "d1")).isEmpty)   // window passed
        // Own restart is gated while an offer is outstanding.
        _ = m.handle(.media(.iceState(.disconnected)))
        clock.advance(2.5)
        #expect(!m.handle(.tick).contains { if case .makeOffer = $0 { true } else { false } })
        clock.advance(5)
        #expect(m.handle(.tick).contains { if case .makeOffer = $0 { true } else { false } })   // stale offer: retry
    }

    @Test func callerFailsIfRestartOfferHasNoFingerprint() {
        var m = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = m.handle(.start(contact: bob, video: false, callID: "c1"))
        _ = m.handle(.server(.answered(callID: "c1", deviceID: "d1", payload: Data())))
        _ = m.handle(.remote(CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(myFP), dtlsFingerprint: myFP), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp(callerFP)))
        _ = m.handle(.remote(CallSignalPayload(kind: .renegotiate, callID: "c1"), fromDeviceID: "d1"))
        let fx = m.handle(.localOffer("v=0\r\n"))
        #expect(m.state?.phase == .ended(.failed) && fx.contains(.sendHangup))
    }

    @Test func calleeDoesNotAcceptSecondOfferBeforeAnswering() {
        var (m, _) = active()
        let re = CallSignalPayload(kind: .renegotiate, callID: "c1", sdp: sdp(callerFP), dtlsFingerprint: callerFP)
        #expect(m.handle(.remote(re, fromDeviceID: "dA")).contains { isAcceptOffer($0) })
        #expect(m.handle(.remote(re, fromDeviceID: "dA")).isEmpty)
        _ = m.handle(.localAnswer(sdp(myFP)))
        #expect(m.handle(.remote(re, fromDeviceID: "dA")).contains { isAcceptOffer($0) })   // next round is fine
    }

    @Test func calleeDoesNotRequestWhileAnsweringAnOffer() {
        var (m, clock) = active()
        let re = CallSignalPayload(kind: .renegotiate, callID: "c1", sdp: sdp(callerFP), dtlsFingerprint: callerFP)
        _ = m.handle(.remote(re, fromDeviceID: "dA"))
        _ = m.handle(.media(.iceState(.disconnected)))
        clock.advance(2.5)
        #expect(!m.handle(.tick).contains { if case .sendSignal = $0 { true } else { false } })
    }

    @Test func simultaneousRestartsConverge() {
        let callerClock = ManualClock(), calleeClock = ManualClock()
        var caller = CallSessionMachine(context: ctx(), clock: callerClock)
        var callee = CallSessionMachine(context: ctx(), clock: calleeClock)
        _ = caller.handle(.start(contact: bob, video: false, callID: "c1"))
        _ = callee.handle(.incoming(callID: "c1", contact: bob, fromDeviceID: "caller-dev", ring: ring(video: false)))
        _ = callee.handle(.localOffer(sdp(myFP)))
        let offerFX = callee.handle(.answer(video: false))
        guard case .sendAnswer(let offerPayload)? = offerFX.first(where: { if case .sendAnswer = $0 { true } else { false } }) else {
            Issue.record("no offer"); return
        }
        _ = caller.handle(.server(.answered(callID: "c1", deviceID: "callee-dev", payload: Data())))
        _ = caller.handle(.remote(offerPayload, fromDeviceID: "callee-dev"))
        let answerFX = caller.handle(.localAnswer(sdp(callerFP)))
        guard case .sendSignal(let answerPayload)? = answerFX.first(where: { if case .sendSignal = $0 { true } else { false } }) else {
            Issue.record("no answer"); return
        }
        _ = callee.handle(.remote(answerPayload, fromDeviceID: "caller-dev"))
        _ = caller.handle(.media(.iceState(.connected))); _ = callee.handle(.media(.iceState(.connected)))
        _ = caller.handle(.media(.iceState(.disconnected))); _ = callee.handle(.media(.iceState(.disconnected)))
        callerClock.advance(2.5); calleeClock.advance(2.5)
        let callerFX = caller.handle(.tick), calleeFX = callee.handle(.tick)
        #expect(callerFX.contains { if case .makeOffer(_, let r) = $0 { r } else { false } })
        guard case .sendSignal(let request)? = calleeFX.first(where: { if case .sendSignal = $0 { true } else { false } }) else {
            Issue.record("no request"); return
        }
        // The caller's offer goes out; the callee's request crosses it and is ignored.
        let restartOffer = caller.handle(.localOffer(sdp(callerFP)))
        #expect(caller.handle(.remote(request, fromDeviceID: "callee-dev")).isEmpty)
        guard case .sendSignal(let offer)? = restartOffer.first else { Issue.record("no restart offer"); return }
        #expect(callee.handle(.remote(offer, fromDeviceID: "caller-dev")).contains { isAcceptOffer($0) })
        let reply = callee.handle(.localAnswer(sdp(myFP)))
        guard case .sendSignal(let answer)? = reply.first else { Issue.record("no reply"); return }
        #expect(answer.kind == .renegotiateAnswer)
        #expect(caller.handle(.remote(answer, fromDeviceID: "callee-dev")).contains { isAcceptAnswer($0) })
    }
}
