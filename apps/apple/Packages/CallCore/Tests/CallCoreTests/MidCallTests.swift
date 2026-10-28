import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
private let server = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "h")
private func ctx(network: NetworkType = .wifi) -> CallContext {
    CallContext(server: server, settings: UserSettings(ipHiding: .nonContacts), network: network, isContact: true)
}
private let calleeFP = "sha-256 11:22:33"
private let callerFP = "sha-256 AB:CD:EF"
private func sdp(_ f: String) -> String { "v=0\r\na=fingerprint:\(f)\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" }
private func cand(_ n: Int) -> IceCandidate { IceCandidate(sdp: "candidate:\(n)", sdpMid: "0", sdpMLineIndex: 0) }
private func isAcceptOffer(_ e: CallEffect) -> Bool { if case .acceptOffer = e { true } else { false } }
private func isAcceptAnswer(_ e: CallEffect) -> Bool { if case .acceptAnswer = e { true } else { false } }
private func isMakeOffer(_ e: CallEffect, restart: Bool) -> Bool { if case .makeOffer(_, let r) = e { r == restart } else { false } }
private func isHistory(_ e: CallEffect) -> Bool { if case .recordHistory = e { true } else { false } }
private func signal(_ fx: [CallEffect], _ kind: CallSignalPayload.Kind) -> CallSignalPayload? {
    for e in fx { if case .sendSignal(let p) = e, p.kind == kind { return p } }
    return nil
}

/// Caller, ICE connected. Peer (callee) device "d1"; pinned fingerprint is the callee's.
private func callerActive(video: Bool = false) -> (CallSessionMachine, ManualClock) {
    let clock = ManualClock()
    var m = CallSessionMachine(context: ctx(), clock: clock)
    _ = m.handle(.start(contact: bob, video: video, callID: "c1"))
    _ = m.handle(.server(.answered(callID: "c1", deviceID: "d1", payload: Data())))
    _ = m.handle(.remote(CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(calleeFP), dtlsFingerprint: calleeFP), fromDeviceID: "d1"))
    _ = m.handle(.localAnswer(sdp(callerFP)))
    _ = m.handle(.media(.iceState(.connected)))
    return (m, clock)
}
/// Callee, ICE connected. Peer (caller) device "dA"; pinned fingerprint is the caller's.
private func calleeActive() -> (CallSessionMachine, ManualClock) {
    let clock = ManualClock()
    var m = CallSessionMachine(context: ctx(), clock: clock)
    _ = m.handle(.incoming(callID: "c1", contact: bob, fromDeviceID: "dA", ring: CallSignalPayload(kind: .ring, callID: "c1", video: false)))
    _ = m.handle(.localOffer(sdp(calleeFP)))
    _ = m.handle(.answer(video: false))
    _ = m.handle(.remote(CallSignalPayload(kind: .answer, callID: "c1", sdp: sdp(callerFP), dtlsFingerprint: callerFP), fromDeviceID: "dA"))
    _ = m.handle(.media(.iceState(.connected)))
    return (m, clock)
}
private func peerOffer(_ f: String, sdpFP: String? = nil) -> CallSignalPayload {
    CallSignalPayload(kind: .renegotiate, callID: "c1", sdp: sdp(sdpFP ?? f), dtlsFingerprint: f)
}

@Suite struct FingerprintCheckTests {
    @Test func rules() {
        #expect(FingerprintCheck.isValid(sdp: sdp("sha-256 AA"), claimed: "sha-256 AA"))
        #expect(!FingerprintCheck.isValid(sdp: sdp("sha-256 AA"), claimed: "sha-256 BB"))
        #expect(!FingerprintCheck.isValid(sdp: sdp("sha-256 AA"), claimed: nil))
        #expect(!FingerprintCheck.isValid(sdp: nil, claimed: "sha-256 AA"))
        #expect(!FingerprintCheck.isValid(sdp: "v=0\r\n", claimed: "sha-256 AA"))
        #expect(FingerprintCheck.isValid(sdp: sdp("sha-256 AA"), claimed: "sha-256 AA", pinned: "sha-256 AA"))
        #expect(!FingerprintCheck.isValid(sdp: sdp("sha-256 CC"), claimed: "sha-256 CC", pinned: "sha-256 AA"))
        #expect(!FingerprintCheck.isValid(sdp: sdp("sha-256 AA"), claimed: "sha-256 AA", pinned: nil, requirePin: true))
    }
}

@Suite struct MidCallTests {
    @Test func remoteFromWrongSenderIsRejected() {
        var m = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = m.handle(.start(contact: bob, video: false, callID: "c1"))
        _ = m.handle(.server(.answered(callID: "c1", deviceID: "d1", payload: Data())))
        let fx = m.handle(.remoteRejected(.wrongSender, fromDeviceID: "d1"))
        #expect(m.state?.phase == .ended(.failed))
        #expect(fx.contains(.sendHangup) && fx.contains(.closeMedia))
        #expect(!fx.contains { isAcceptOffer($0) || isAcceptAnswer($0) })
    }

    @Test func fingerprintMismatchEndsCall() {
        var m = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = m.handle(.start(contact: bob, video: false, callID: "c1"))
        _ = m.handle(.server(.answered(callID: "c1", deviceID: "d1", payload: Data())))
        let bad = CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp("sha-256 99"), dtlsFingerprint: calleeFP)
        let fx = m.handle(.remote(bad, fromDeviceID: "d1"))
        #expect(m.state?.phase == .ended(.failed) && !fx.contains { isAcceptOffer($0) })
    }

    @Test func statsDriveAdaptor() {
        var (m, _) = callerActive(video: true)
        let fx = m.handle(.stats(MediaStats(availableOutgoingBitrate: 55_000)))
        #expect(fx.contains(.setVideo(nil, ceiling: .high)) && fx.contains(.setAudioMaxBitrate(16_000)))
        #expect(m.state?.banner == .audioOnly && m.state?.effectiveMode == .dataSaver)
        #expect(fx.contains { if case .publish(let s) = $0 { s?.banner == .audioOnly && s?.effectiveMode == .dataSaver } else { false } })
    }

    @Test func modeChangeRenegotiates() {
        var (m, _) = callerActive()
        // high -> balanced: same ptime, FEC and DTX, so only the bitrate steps.
        let step = m.handle(.setPreference(.fixed(.balanced)))
        #expect(step.contains(.setAudioMaxBitrate(24_000)))
        #expect(!step.contains { isMakeOffer($0, restart: false) })
        // balanced -> dataSaver: ptime 20 -> 40, so a renegotiation is needed.
        let fx = m.handle(.stats(MediaStats(availableOutgoingBitrate: 40_000)))
        #expect(m.state?.effectiveMode == .dataSaver)
        #expect(fx.contains(.makeOffer(receive: .for(.balanced), iceRestart: false)))
        let out = m.handle(.localOffer(sdp(callerFP)))
        let re = signal(out, .renegotiate)
        #expect(re?.sdp == sdp(callerFP) && re?.dtlsFingerprint == callerFP)
        // The callee's answer is accepted with the new send profile.
        let ans = CallSignalPayload(kind: .renegotiateAnswer, callID: "c1", sdp: sdp(calleeFP), dtlsFingerprint: calleeFP)
        #expect(m.handle(.remote(ans, fromDeviceID: "d1")).contains(.acceptAnswer(sdp: sdp(calleeFP), send: .for(.dataSaver))))
        // No second renegotiation for the same settings.
        #expect(!m.handle(.stats(MediaStats(availableOutgoingBitrate: 40_000))).contains { isMakeOffer($0, restart: false) })
    }

    @Test func calleeAnswersModeChangeRenegotiate() {
        var (callee, _) = calleeActive()
        let fx = callee.handle(.remote(peerOffer(callerFP), fromDeviceID: "dA"))
        #expect(fx.contains { isAcceptOffer($0) })
        let out = callee.handle(.localAnswer(sdp(calleeFP)))
        #expect(signal(out, .renegotiateAnswer)?.dtlsFingerprint == calleeFP)
    }

    @Test func calleeStartsModeChangeRenegotiation() {
        var (m, _) = calleeActive()
        let fx = m.handle(.setPreference(.fixed(.dataSaver)))
        #expect(fx.contains(.makeOffer(receive: .for(.dataSaver), iceRestart: false)))
        #expect(signal(m.handle(.localOffer(sdp(calleeFP))), .renegotiate)?.sdp == sdp(calleeFP))
    }

    @Test func inCallPreferenceSignalsCeiling() {
        var (m, _) = callerActive()
        let fx = m.handle(.setPreference(.fixed(.ultraLow)))
        #expect(fx.contains(.sendSignal(CallSignalPayload(kind: .modeChange, callID: "c1", ceiling: .ultraLow))))
        #expect(m.state?.inCallPreference == .fixed(.ultraLow))
    }

    @Test func peerCeilingCaps() {
        var (m, _) = callerActive()
        let mc = CallSignalPayload(kind: .modeChange, callID: "c1", ceiling: .dataSaver)
        #expect(m.handle(.remote(mc, fromDeviceID: "d2")).isEmpty)
        let fx = m.handle(.remote(mc, fromDeviceID: "d1"))
        #expect(m.state!.effectiveMode <= .dataSaver && fx.contains(.setAudioMaxBitrate(16_000)))
        _ = m.handle(.stats(MediaStats(availableOutgoingBitrate: 5_000_000)))
        #expect(m.state!.effectiveMode <= .dataSaver)
    }

    // MARK: Renegotiation rules

    @Test func glareCallerWinsOnCallee() {
        var (m, _) = calleeActive()
        _ = m.handle(.setPreference(.fixed(.dataSaver)))          // callee's own renegotiate offer
        _ = m.handle(.localOffer(sdp(calleeFP)))                  // ... is now out
        let fx = m.handle(.remote(peerOffer(callerFP), fromDeviceID: "dA"))
        #expect(fx.first == .rollback)
        #expect(isAcceptOffer(fx[1]))
        // Its own offer is abandoned: the answer to the caller's offer follows normally.
        #expect(signal(m.handle(.localAnswer(sdp(calleeFP))), .renegotiateAnswer) != nil)
    }

    @Test func callerAcceptsCalleeModeChangeOffer() {
        var (m, _) = callerActive()
        let fx = m.handle(.remote(peerOffer(calleeFP), fromDeviceID: "d1"))
        #expect(fx.contains { isAcceptOffer($0) } && !fx.contains(.rollback))
        #expect(signal(m.handle(.localAnswer(sdp(callerFP))), .renegotiateAnswer)?.dtlsFingerprint == callerFP)
    }

    @Test func callerChecksCalleeOffer() {
        var (a, _) = callerActive()
        #expect(a.handle(.remote(peerOffer(calleeFP), fromDeviceID: "d2")).isEmpty)               // wrong device
        #expect(a.state?.isEnded == false)
        var (b, _) = callerActive()
        let bad = b.handle(.remote(peerOffer(calleeFP, sdpFP: "sha-256 99"), fromDeviceID: "d1")) // != SDP
        #expect(b.state?.phase == .ended(.failed) && bad.contains(.sendHangup))
        var (c, _) = callerActive()
        let unpinned = c.handle(.remote(peerOffer("sha-256 77"), fromDeviceID: "d1"))             // != pinned
        #expect(c.state?.phase == .ended(.failed) && !unpinned.contains { isAcceptOffer($0) })
    }

    @Test func callerOneRenegotiationAtATime() {
        var (m, _) = callerActive()
        #expect(m.handle(.remote(peerOffer(calleeFP), fromDeviceID: "d1")).contains { isAcceptOffer($0) })
        #expect(m.handle(.remote(peerOffer(calleeFP), fromDeviceID: "d1")).isEmpty)               // owes an answer
        _ = m.handle(.localAnswer(sdp(callerFP)))
        #expect(m.handle(.remote(peerOffer(calleeFP), fromDeviceID: "d1")).contains { isAcceptOffer($0) })
    }

    @Test func callerOfferWinsGlareAgainstCalleeOffer() {
        var (m, clock) = callerActive()
        // A callee restart request makes the caller send its own offer.
        let req = CallSignalPayload(kind: .renegotiate, callID: "c1")
        #expect(m.handle(.remote(req, fromDeviceID: "d1")).contains { isMakeOffer($0, restart: true) })
        _ = m.handle(.localOffer(sdp(callerFP)))
        clock.advance(1)
        // The callee's own offer arrives while the caller's is outstanding: ignored, the caller's offer wins.
        #expect(m.handle(.remote(peerOffer(calleeFP), fromDeviceID: "d1")).isEmpty)
        // After the window, a stale offer of ours is rolled back in favour of the peer's.
        clock.advance(10)
        let fx = m.handle(.remote(peerOffer(calleeFP), fromDeviceID: "d1"))
        #expect(fx.first == .rollback && isAcceptOffer(fx[1]))
    }

    @Test func requestWithoutSDPIsOnlyARestartRequest() {
        var (callee, _) = calleeActive()
        #expect(callee.handle(.remote(CallSignalPayload(kind: .renegotiate, callID: "c1"), fromDeviceID: "dA")).isEmpty)
        #expect(callee.state?.isEnded == false)
    }

    @Test func callerDoesNotRestartWhileOwingAnAnswer() {
        // The callee's mode-change offer is accepted; the caller now owes an answer.
        var (n, c2) = callerActive()
        #expect(n.handle(.remote(peerOffer(calleeFP), fromDeviceID: "d1")).contains { isAcceptOffer($0) })
        c2.advance(1)
        let req = CallSignalPayload(kind: .renegotiate, callID: "c1")
        #expect(n.handle(.remote(req, fromDeviceID: "d1")).isEmpty)
        // Its own policy restart is held back too.
        _ = n.handle(.media(.iceState(.disconnected)))
        c2.advance(2.5)
        #expect(!n.handle(.tick).contains { isMakeOffer($0, restart: true) })
        // Once the answer went out, restarts work again.
        _ = n.handle(.localAnswer(sdp(callerFP)))
        c2.advance(5)
        #expect(n.handle(.tick).contains { isMakeOffer($0, restart: true) })
    }

    @Test func callerIgnoresRenegotiateBeforeFirstAnswerIsSent() {
        var m = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = m.handle(.start(contact: bob, video: false, callID: "c1"))
        _ = m.handle(.server(.answered(callID: "c1", deviceID: "d1", payload: Data())))
        _ = m.handle(.remote(CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(calleeFP), dtlsFingerprint: calleeFP), fromDeviceID: "d1"))
        // The initial answer has not gone out yet: a second offer must not be accepted.
        #expect(m.handle(.remote(peerOffer(calleeFP), fromDeviceID: "d1")).isEmpty)
        // So the next localAnswer is still the initial answer.
        #expect(signal(m.handle(.localAnswer(sdp(callerFP))), .answer) != nil)
    }

    @Test func bothSidesLowerCeilingAtOnceAndConverge() {
        var (caller, _) = callerActive()
        var (callee, _) = calleeActive()
        let c1 = caller.handle(.setPreference(.fixed(.dataSaver)))
        let c2 = callee.handle(.setPreference(.fixed(.dataSaver)))
        #expect(c1.contains { isMakeOffer($0, restart: false) } && c2.contains { isMakeOffer($0, restart: false) })
        // The ceiling signals cross; both are accepted from the right device.
        let callerOut = caller.handle(.localOffer(sdp(callerFP)))
        let calleeOut = callee.handle(.localOffer(sdp(calleeFP)))
        guard let callerOffer = signal(callerOut, .renegotiate), let calleeOffer = signal(calleeOut, .renegotiate) else {
            Issue.record("both sides should have an offer out"); return
        }
        // Delivery crosses: the caller's offer reaches the callee, the callee's reaches the caller.
        let atCallee = callee.handle(.remote(callerOffer, fromDeviceID: "dA"))
        let atCaller = caller.handle(.remote(calleeOffer, fromDeviceID: "d1"))
        #expect(atCallee.first == .rollback && isAcceptOffer(atCallee[1]))   // caller's offer wins
        #expect(atCaller.isEmpty)                                            // callee's offer is ignored
        guard let answer = signal(callee.handle(.localAnswer(sdp(calleeFP))), .renegotiateAnswer) else {
            Issue.record("no answer"); return
        }
        let accepted = caller.handle(.remote(answer, fromDeviceID: "d1"))
        #expect(accepted.contains(.acceptAnswer(sdp: sdp(calleeFP), send: .for(.dataSaver))))
        // Converged: neither side wants another offer for the same settings.
        for i in 0..<3 {
            #expect(!caller.handle(.stats(MediaStats(availableOutgoingBitrate: 5_000_000))).contains { isMakeOffer($0, restart: false) }, "caller \(i)")
            #expect(!callee.handle(.stats(MediaStats(availableOutgoingBitrate: 5_000_000))).contains { isMakeOffer($0, restart: false) }, "callee \(i)")
        }
        #expect(caller.state?.isEnded == false && callee.state?.isEnded == false)
    }

    // MARK: Reconnect, end

    @Test func reconnectFlow() {
        var (m, clock) = callerActive()
        clock.advance(5)
        _ = m.handle(.media(.iceState(.disconnected)))
        #expect(m.state?.phase == .reconnecting && m.state?.banner == .reconnecting)
        clock.advance(2.4)
        #expect(!m.handle(.tick).contains { isMakeOffer($0, restart: true) })
        clock.advance(0.1)
        #expect(m.handle(.tick).contains { isMakeOffer($0, restart: true) })
        clock.advance(27.5)
        let fx = m.handle(.tick)
        #expect(m.state?.phase == .ended(.connectionLost))
        #expect(fx.contains(.sendHangup) && fx.contains(.recordHistory(.audio(duration: 35), outgoing: true)))
    }

    @Test func pathChangeRestartsIce() {
        var (m, _) = callerActive()
        #expect(m.handle(.pathChanged).contains { isMakeOffer($0, restart: true) })
        #expect(m.state?.banner == nil)
    }

    @Test func hangupByUser() {
        var (m, clock) = callerActive(video: true)
        clock.advance(12)
        let fx = m.handle(.hangup)
        #expect(fx.contains(.sendHangup) && fx.contains(.recordHistory(.video(duration: 12), outgoing: true)))
        #expect(m.state?.phase == .ended(.hungUp))
    }

    @Test func endedCallIgnoresLateEvents() {
        var (m, _) = callerActive()
        _ = m.handle(.hangup)
        let frozen = m.state
        let inputs: [CallInput] = [
            .answer(video: true), .decline, .hangup, .setPreference(.fixed(.high)), .setVideo(true), .setMuted(true), .setSpeaker(true),
            .server(.answered(callID: "c1", deviceID: "d1", payload: Data())), .server(.ended(callID: "c1", reason: "missed")),
            .remote(peerOffer(calleeFP), fromDeviceID: "d1"), .remoteRejected(.undecryptable, fromDeviceID: "d1"),
            .media(.iceState(.disconnected)), .media(.localCandidate(cand(1))), .localOffer(sdp(callerFP)), .localAnswer(sdp(callerFP)),
            .stats(MediaStats(availableOutgoingBitrate: 1)), .pathChanged, .tick,
            .contextChanged(ctx(network: .mobile)),
        ]
        for i in inputs { #expect(m.handle(i).isEmpty) }
        #expect(m.state == frozen)
    }

    // MARK: Random sequences

    @Test func exactlyOneHistoryEntry() {
        struct RNG: RandomNumberGenerator {
            var s: UInt64
            mutating func next() -> UInt64 {
                s &+= 0x9E3779B97F4A7C15
                var z = s
                z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
                z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
                return z ^ (z >> 31)
            }
        }
        let devices = ["d1", "d2", "dA"]
        let fps = [calleeFP, callerFP, "sha-256 99"]
        let reasons = ["declined", "missed", "hangup", "answered_elsewhere", "failed", "weird"]
        let kinds: [CallSignalPayload.Kind] = [.ring, .offer, .answer, .candidate, .renegotiate, .renegotiateAnswer, .modeChange]
        for seed in 1...6 {
            var rng = RNG(s: UInt64(seed))
            let clock = ManualClock()
            var m = CallSessionMachine(context: ctx(), clock: clock)
            var historyCount = 0
            var handledEnd = true
            for _ in 0..<500 {
                let wasLive = m.state.map { !$0.isEnded } ?? false
                let input: CallInput
                switch Int.random(in: 0..<24, using: &rng) {
                case 0: input = .start(contact: bob, video: Bool.random(using: &rng), callID: "c1")
                case 1: input = .incoming(callID: "c1", contact: bob, fromDeviceID: devices.randomElement(using: &rng)!,
                                          ring: CallSignalPayload(kind: .ring, callID: "c1", video: Bool.random(using: &rng),
                                                                  ceiling: DataMode.allCases.randomElement(using: &rng)))
                case 2: input = .answer(video: Bool.random(using: &rng))
                case 3: input = .decline
                case 4: input = .hangup
                case 5: input = .setPreference(Bool.random(using: &rng) ? .auto : .fixed(DataMode.allCases.randomElement(using: &rng)!))
                case 6: input = .setVideo(Bool.random(using: &rng))
                case 7: input = .server(.answered(callID: "c1", deviceID: devices.randomElement(using: &rng)!, payload: Data()))
                case 8: input = .server(.ended(callID: "c1", reason: reasons.randomElement(using: &rng)!))
                case 9, 10, 11:
                    let f = fps.randomElement(using: &rng)!
                    let p = CallSignalPayload(kind: kinds.randomElement(using: &rng)!, callID: "c1",
                                              sdp: Bool.random(using: &rng) ? sdp(Bool.random(using: &rng) ? f : fps[0]) : nil,
                                              dtlsFingerprint: Bool.random(using: &rng) ? f : nil,
                                              candidate: Bool.random(using: &rng) ? cand(Int.random(in: 0..<100, using: &rng)) : nil,
                                              ceiling: DataMode.allCases.randomElement(using: &rng))
                    input = .remote(p, fromDeviceID: devices.randomElement(using: &rng)!)
                case 12: input = .remoteRejected(.undecryptable, fromDeviceID: devices.randomElement(using: &rng)!)
                case 13, 14: input = .media(.iceState([IceState.connected, .disconnected, .failed, .checking, .closed].randomElement(using: &rng)!))
                case 15: input = .media(.localCandidate(cand(Int.random(in: 0..<100, using: &rng))))
                case 16: input = .localOffer(sdp(fps.randomElement(using: &rng)!))
                case 17: input = .localAnswer(sdp(fps.randomElement(using: &rng)!))
                case 18, 19: input = .stats(MediaStats(availableOutgoingBitrate: Bool.random(using: &rng) ? nil : Double.random(in: 0...600_000, using: &rng),
                                                      inboundLossFraction: Double.random(in: 0...0.3, using: &rng)))
                case 20: input = .pathChanged
                case 21: input = .contextChanged(ctx(network: [NetworkType.wifi, .mobile].randomElement(using: &rng)!))
                default:
                    clock.advance(Double.random(in: 0...12, using: &rng))
                    input = .tick
                }
                let fx = m.handle(input)
                let isNowLive = m.state.map { !$0.isEnded } ?? false
                if !wasLive && isNowLive { historyCount = 0; handledEnd = false }   // a new call
                historyCount += fx.filter(isHistory).count
                #expect(historyCount <= 1)
                if case .ended(let reason)? = m.state?.phase, !handledEnd {
                    handledEnd = true
                    #expect(historyCount == (reason == .answeredElsewhere ? 0 : 1), "ended \(reason)")
                }
            }
        }
    }
}
