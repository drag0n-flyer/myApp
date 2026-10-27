import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
private let server = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "h")
private func ctx() -> CallContext {
    CallContext(server: server, settings: UserSettings(ipHiding: .nonContacts), network: .wifi, isContact: true)
}
private let calleeFP = "sha-256 AB:CD:EF"
private func sdp(_ f: String) -> String { "v=0\r\na=fingerprint:\(f)\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" }

@MainActor
private final class Rig {
    let signaling = FakeCallSignaling()
    let sealer = PlainCallSignalSealer()
    let media = FakeMediaSession()
    let clock = ManualClock()
    var published: [CallState?] = []
    var history: [(CallSummary, String, Bool)] = []
    var runner: CallRunner!

    init(callID: String = "c1", tick: Duration = .milliseconds(5)) {
        media.offerSDP = sdp("sha-256 11:22")
        media.answerSDP = sdp("sha-256 33:44")
        runner = CallRunner(context: ctx(), callID: callID, contactID: "bob", signaling: signaling, sealer: sealer,
                            clock: clock, makeMedia: { [media] in media }, tickInterval: tick, statsEveryTicks: 2,
                            publish: { [unowned self] in published.append($0) },
                            record: { [unowned self] s, c, o, _ in history.append((s, c, o)) })
    }

    /// A payload as the other side would have sent it.
    func sealed(_ p: CallSignalPayload, from id: String = "bob") -> Data {
        let d = try! JSONEncoder().encode(p)
        sealer.setSender(id, of: d)
        return d
    }
    func frames() -> [String] { signaling.sent.map(\.frame) }
}

@MainActor @Suite struct CallRunnerTests {
    @Test func outgoingCallRunsEffectsInOrder() async {
        let r = Rig()
        r.signaling.ice = [IceServer(urls: ["stun:h"])]
        r.runner.handle(.start(contact: bob, video: false, callID: "c1"))
        await r.runner.settled()
        #expect(r.media.calls.prefix(2) == ["configure", "setNoiseSuppression(standard)"])
        #expect(r.frames() == ["call.invite"])
        #expect(r.published.last??.phase == .ringing)

        // the callee answers with its offer
        let offer = CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(calleeFP), dtlsFingerprint: calleeFP, ceiling: .high)
        await r.runner.ingest(.answered(callID: "c1", deviceID: "d1", payload: r.sealed(offer)))
        await r.runner.settled()
        #expect(r.media.calls.contains("acceptOffer"))
        #expect(r.frames() == ["call.invite", "call.signal"])   // our answer
        #expect(r.runner.state?.phase == .connecting)

        r.media.emit(.iceState(.connected))
        try? await Task.sleep(for: .milliseconds(50))
        #expect(r.runner.state?.phase == .active)
    }

    @Test func payloadFromWrongSenderIsRejected() async {
        let r = Rig()
        r.runner.handle(.start(contact: bob, video: false, callID: "c1"))
        let offer = CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(calleeFP), dtlsFingerprint: calleeFP)
        await r.runner.ingest(.answered(callID: "c1", deviceID: "d1", payload: r.sealed(offer, from: "mallory")))
        await r.runner.settled()
        #expect(r.runner.state?.phase.isEndedForTest == true)
        #expect(r.frames().contains("call.hangup"))
        #expect(!r.media.calls.contains("acceptOffer"))
        #expect(r.history.count == 1)
    }

    @Test func eventsForAnotherCallAreIgnored() async {
        let r = Rig()
        r.runner.handle(.start(contact: bob, video: false, callID: "c1"))
        await r.runner.ingest(.ended(callID: "other", reason: "hangup"))
        #expect(r.runner.isFinished == false)
        await r.runner.ingest(.ended(callID: "c1", reason: "declined"))
        #expect(r.runner.isFinished)
        #expect(r.history.count == 1)
    }

    @Test func incomingCallPrewarmsThenAnswers() async {
        let r = Rig()
        let ring = CallSignalPayload(kind: .ring, callID: "c1", video: false, ceiling: .high)
        r.runner.handle(.incoming(callID: "c1", contact: bob, fromDeviceID: "dA", ring: ring))
        await r.runner.settled()
        #expect(r.media.calls.contains("makeOffer"))
        #expect(r.frames().isEmpty)                      // nothing leaves while ringing
        r.runner.handle(.answer(video: false))
        await r.runner.settled()
        #expect(r.frames() == ["call.answer"])
        #expect(r.runner.state?.phase == .connecting)
    }

    @Test func declineSendsDeclineAndRecordsMissedOnce() async {
        let r = Rig()
        let ring = CallSignalPayload(kind: .ring, callID: "c1", video: false)
        r.runner.handle(.incoming(callID: "c1", contact: bob, fromDeviceID: "dA", ring: ring))
        r.runner.handle(.decline)
        await r.runner.settled()
        #expect(r.frames() == ["call.decline"])
        #expect(r.media.calls.last == "close")
        #expect(r.history.count == 1)
        #expect(r.history[0].2 == false)
    }

    @Test func iceServerFailureEndsTheCallWithoutInviting() async {
        let r = Rig()
        let failing = FailingIceSignaling(base: r.signaling)
        let runner = CallRunner(context: ctx(), callID: "c1", contactID: "bob", signaling: failing, sealer: r.sealer,
                                clock: r.clock, makeMedia: { [media = r.media] in media },
                                publish: { [unowned r] in r.published.append($0) }, record: { _, _, _, _ in })
        runner.handle(.start(contact: bob, video: false, callID: "c1"))
        await runner.settled()
        #expect(runner.isFinished)
        #expect(!r.frames().contains("call.invite"))
    }

    @Test func ticksAndStatsFlowIntoTheMachine() async {
        let r = Rig()
        r.runner.handle(.start(contact: bob, video: false, callID: "c1"))
        r.media.statsResult = MediaStats(availableOutgoingBitrate: 100_000, roundTripTime: 0.05, bytesSent: 1000, bytesReceived: 2000,
                                         packetsSent: 10, packetsReceived: 10, usingRelay: true, audioCodec: "opus")
        let offer = CallSignalPayload(kind: .offer, callID: "c1", sdp: sdp(calleeFP), dtlsFingerprint: calleeFP)
        await r.runner.ingest(.answered(callID: "c1", deviceID: "d1", payload: r.sealed(offer)))
        r.media.emit(.iceState(.connected))
        try? await Task.sleep(for: .milliseconds(120))
        #expect(r.media.calls.contains("stats"))
        let used: Int64? = r.runner.state?.bytesUsed
        #expect(used == 3560)
        r.runner.handle(.hangup)
        await r.runner.settled()
        #expect(r.frames().contains("call.hangup"))
        #expect(r.history.count == 1)
    }
}

private extension CallPhase { var isEndedForTest: Bool { if case .ended = self { true } else { false } } }

private final class FailingIceSignaling: CallSignaling, @unchecked Sendable {
    let base: FakeCallSignaling
    init(base: FakeCallSignaling) { self.base = base }
    var events: AsyncStream<CallServerEvent> { base.events }
    func invite(callID: String, to accountID: String, video: Bool, payload: Data) async throws { try await base.invite(callID: callID, to: accountID, video: video, payload: payload) }
    func answer(callID: String, payload: Data) async throws { try await base.answer(callID: callID, payload: payload) }
    func signal(callID: String, payload: Data) async throws { try await base.signal(callID: callID, payload: payload) }
    func decline(callID: String) async throws { try await base.decline(callID: callID) }
    func hangup(callID: String) async throws { try await base.hangup(callID: callID) }
    func status(callID: String) async throws -> CallStatus { .unknown }
    func iceServers() async throws -> [IceServer] { throw CallError.disabled }
}
