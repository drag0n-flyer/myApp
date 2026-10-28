import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
private let server = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "h")
private func ctx() -> CallContext {
    var s = UserSettings(ipHiding: .nonContacts)
    s.call.callDataMode = .auto
    return CallContext(server: server, settings: s, network: .wifi, isContact: true)
}
private let fp = "sha-256 AB:CD:EF"
private func sdp(_ f: String) -> String { "v=0\r\na=fingerprint:\(f)\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" }
private func offer() -> CallSignalPayload { CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(fp), dtlsFingerprint: fp) }
private func ring() -> CallSignalPayload { CallSignalPayload(kind: .ring, callID: "c1", video: false) }
private let cand = IceCandidate(sdp: "candidate:1", sdpMid: "0", sdpMLineIndex: 0)
private func candPayload() -> CallSignalPayload { CallSignalPayload(kind: .candidate, callID: "c1", candidate: cand) }

private func caller() -> (CallSessionMachine, ManualClock) {
    let clock = ManualClock()
    var m = CallSessionMachine(context: ctx(), clock: clock)
    _ = m.handle(.start(contact: bob, video: false, callID: "c1"))
    return (m, clock)
}
private func callee(answer: Bool = true) -> (CallSessionMachine, ManualClock) {
    let clock = ManualClock()
    var m = CallSessionMachine(context: ctx(), clock: clock)
    _ = m.handle(.incoming(callID: "c1", contact: bob, fromDeviceID: "dA", ring: ring()))
    _ = m.handle(.localOffer(sdp("sha-256 11:22:33")))
    if answer { _ = m.handle(.answer(video: false)) }
    return (m, clock)
}
private func tick(_ m: inout CallSessionMachine, _ clock: ManualClock, after seconds: TimeInterval) -> [CallEffect] {
    clock.advance(seconds)
    return m.handle(.tick)
}
private func answered(_ m: inout CallSessionMachine, device: String = "d1") {
    _ = m.handle(.server(.answered(callID: "c1", deviceID: device, payload: Data())))
}
private func hasAcceptOffer(_ fx: [CallEffect]) -> Bool { fx.contains { if case .acceptOffer = $0 { true } else { false } } }
private func hasHistory(_ fx: [CallEffect]) -> Bool { fx.contains { if case .recordHistory = $0 { true } else { false } } }

/// Ring and setup deadlines, and the answering device's credential check. Time moves only with the clock and `.tick`.
@Suite struct DeadlineTests {
    @Test func deadlineValuesMatchAndroid() {
        #expect(CallSessionMachine.serverRingLimit == 45)
        #expect(CallSessionMachine.ringGrace == 5)
        #expect(CallSessionMachine.ringDeadline == 50)
        #expect(CallSessionMachine.setupDeadline == 30)
    }

    @Test func callerUnansweredEndsMissedAndCancels() {
        var (m, clock) = caller()
        _ = tick(&m, clock, after: 49.9)
        #expect(m.state?.phase == .ringing)
        let fx = tick(&m, clock, after: 0.1)
        #expect(m.state?.phase == .ended(.missed))
        #expect(fx.contains(.sendHangup))
        #expect(fx.contains(.closeMedia))
        #expect(hasHistory(fx))
    }

    @Test func callerAnsweredButNoOfferFailsAfterSetupLimit() {
        var (m, clock) = caller()
        _ = tick(&m, clock, after: 20)
        answered(&m)
        _ = tick(&m, clock, after: 29.9)
        #expect(m.state?.phase == .ringing)
        let fx = tick(&m, clock, after: 0.1)
        #expect(m.state?.phase == .ended(.failed))
        #expect(fx.contains(.sendHangup))
    }

    @Test func callerConnectingPastSetupLimitFails() {
        var (m, clock) = caller()
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp("sha-256 11:22:33")))
        #expect(m.state?.phase == .connecting)
        _ = tick(&m, clock, after: 29.9)
        #expect(m.state?.phase == .connecting)
        let fx = tick(&m, clock, after: 0.1)
        #expect(m.state?.phase == .ended(.failed))
        #expect(fx.contains(.sendHangup))
    }

    @Test func activeCallHasNoSetupDeadline() {
        var (m, clock) = caller()
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1"))
        _ = m.handle(.localAnswer(sdp("sha-256 11:22:33")))
        _ = m.handle(.media(.iceState(.connected)))
        #expect(m.state?.phase == .active)
        _ = tick(&m, clock, after: 300)
        #expect(m.state?.phase == .active)
    }

    @Test func calleeConnectingPastSetupLimitFails() {
        var (m, clock) = callee()
        #expect(m.state?.phase == .connecting)
        _ = tick(&m, clock, after: 29.9)
        #expect(m.state?.phase == .connecting)
        let fx = tick(&m, clock, after: 0.1)
        #expect(m.state?.phase == .ended(.failed))
        #expect(fx.contains(.sendHangup))
    }

    @Test func unansweredRingingCalleeEndsMissedWithoutTellingServer() {
        var (m, clock) = callee(answer: false)
        _ = tick(&m, clock, after: 49.9)
        #expect(m.state?.phase == .incoming)
        let fx = tick(&m, clock, after: 0.1)
        #expect(m.state?.phase == .ended(.missed))
        #expect(!fx.contains(.sendHangup))
        #expect(!fx.contains(.sendDecline))
        #expect(hasHistory(fx))
    }

    @Test func answeringJustBeforeRingDeadlineIsNotMissed() {
        var (m, clock) = callee(answer: false)
        _ = tick(&m, clock, after: 49)
        _ = m.handle(.answer(video: false))
        _ = tick(&m, clock, after: 5)
        #expect(m.state?.phase == .connecting)
    }

    @Test func endedCallIgnoresLaterTicks() {
        var (m, clock) = caller()
        _ = m.handle(.hangup)
        #expect(tick(&m, clock, after: 100).isEmpty)
    }

    @Test func offerWithMismatchedCredentialDeviceIsDropped() {
        var (m, _) = caller()
        answered(&m)
        // The MLS credential names d2, but the server framed the answer as coming from d1.
        let bad = m.handle(.remote(offer(), fromDeviceID: "d1", senderDeviceID: "d2"))
        #expect(!hasAcceptOffer(bad))
        #expect(m.state?.phase == .ringing)
        #expect(hasAcceptOffer(m.handle(.remote(offer(), fromDeviceID: "d1", senderDeviceID: "d1"))))
    }

    @Test func signalWithMismatchedCredentialDeviceIsDropped() {
        var (m, _) = caller()
        answered(&m)
        _ = m.handle(.remote(offer(), fromDeviceID: "d1", senderDeviceID: "d1"))
        #expect(m.handle(.remote(candPayload(), fromDeviceID: "d1", senderDeviceID: "d2")).isEmpty)
        // A matching credential device is accepted; so is one the sealer cannot name.
        #expect(m.handle(.remote(candPayload(), fromDeviceID: "d1", senderDeviceID: "d1")).contains(.addCandidate(cand)))
        #expect(m.handle(.remote(candPayload(), fromDeviceID: "d1")).contains(.addCandidate(cand)))
    }

    @Test func earlyCandidateWithMismatchedCredentialDeviceIsNotBuffered() {
        var (m, _) = caller()
        _ = m.handle(.remote(candPayload(), fromDeviceID: "d1", senderDeviceID: "d2"))
        answered(&m)
        let fx = m.handle(.remote(offer(), fromDeviceID: "d1", senderDeviceID: "d1"))
        #expect(hasAcceptOffer(fx))
        #expect(!fx.contains(.addCandidate(cand)))
    }

    @Test func calleeDropsAnswerWithMismatchedCredentialDevice() {
        var (m, _) = callee()
        let answer = CallSignalPayload(kind: .answer, callID: "c1", sdp: sdp(fp), dtlsFingerprint: fp)
        let bad = m.handle(.remote(answer, fromDeviceID: "dA", senderDeviceID: "dX"))
        #expect(!bad.contains { if case .acceptAnswer = $0 { true } else { false } })
        #expect(m.state?.phase == .connecting)
        let good = m.handle(.remote(answer, fromDeviceID: "dA", senderDeviceID: "dA"))
        #expect(good.contains { if case .acceptAnswer = $0 { true } else { false } })
    }

    @Test func callerAnsweredLateIsNotMissedAtRingDeadline() {
        var (m, clock) = caller()
        _ = tick(&m, clock, after: 45)
        answered(&m)
        let fx = tick(&m, clock, after: 5)
        #expect(m.state?.phase == .ringing)
        #expect(!fx.contains(.sendHangup))
    }

    @Test func secondCallDoesNotInheritFirstCallsSetupClock() {
        var (m, clock) = callee()
        _ = tick(&m, clock, after: 10)
        _ = m.handle(.hangup)
        _ = m.handle(.start(contact: bob, video: false, callID: "c2"))
        _ = tick(&m, clock, after: 25)
        #expect(m.state?.phase == .ringing)
    }

    @Test func mismatchedAnsweringDeviceIsRefusedThroughTheSealer() async throws {
        var (m, _) = caller()
        let sealer = PlainCallSignalSealer()
        answered(&m)
        let forged = try await sealer.seal(offer(), to: "me")
        sealer.setSender("bob/d2", of: forged)
        let bad = await sealer.openInput(forged, from: "bob", fromDeviceID: "d1")
        #expect(!hasAcceptOffer(m.handle(bad)))
        #expect(m.state?.phase == .ringing)

        var genuine = offer()
        genuine.ceiling = .high   // different bytes from the forged frame
        let data = try await sealer.seal(genuine, to: "me")
        sealer.setSender("bob/d1", of: data)
        #expect(hasAcceptOffer(m.handle(await sealer.openInput(data, from: "bob", fromDeviceID: "d1"))))
    }

    @Test func openInputMapsSealErrorsToRemoteRejected() async throws {
        let sealer = PlainCallSignalSealer()
        let data = try await sealer.seal(offer(), to: "me")
        sealer.setSender("carol/d9", of: data)
        let input = await sealer.openInput(data, from: "bob", fromDeviceID: "d1")
        guard case .remoteRejected(.wrongSender, let device) = input else { Issue.record("got \(input)"); return }
        #expect(device == "d1")
    }
}
