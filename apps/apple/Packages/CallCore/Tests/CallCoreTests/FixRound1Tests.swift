import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
private let server = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "h")
private func ctx() -> CallContext {
    CallContext(server: server, settings: UserSettings(ipHiding: .nonContacts), network: .wifi, isContact: true)
}

/// A connection that starts disconnected, like a suspended iPhone woken by a VoIP push.
private final class FakeConnection: CallConnection, @unchecked Sendable {
    private let lock = NSLock()
    private var _connected = false, _connects = 0
    let connectsOnRequest: Bool
    init(connectsOnRequest: Bool = true) { self.connectsOnRequest = connectsOnRequest }
    var connects: Int { lock.withLock { _connects } }
    var isConnected: Bool { get async { lock.withLock { _connected } } }
    func connect() async { lock.withLock { _connects += 1; if connectsOnRequest { _connected = true } } }
}

@MainActor @Suite struct FixRound1Tests {
    // 1. a push-woken device connects before it talks
    @Test func gateConnectsBeforeSending() async throws {
        let base = FakeCallSignaling()
        let conn = FakeConnection()
        let gated = GatedCallSignaling(base: base, connection: conn, timeout: .milliseconds(200))
        try await gated.answer(callID: "c1", payload: Data([1]))
        #expect(conn.connects == 1)
        #expect(base.sent.map(\.frame) == ["call.answer"])
        _ = try await gated.status(callID: "c1")
        try await gated.signal(callID: "c1", payload: Data())
        #expect(conn.connects == 1)   // already connected: no second start
    }

    @Test func gateFailsWhenTheConnectionNeverComes() async {
        let base = FakeCallSignaling()
        let gated = GatedCallSignaling(base: base, connection: FakeConnection(connectsOnRequest: false), timeout: .milliseconds(60))
        await #expect(throws: CallError.notConnected) { try await gated.status(callID: "c1") }
        await #expect(throws: CallError.notConnected) { try await gated.answer(callID: "c1", payload: Data()) }
        #expect(base.sent.isEmpty)
    }

    @Test func pushWokenCalleeAnswersThroughTheGate() async {
        let base = FakeCallSignaling()
        let conn = FakeConnection()
        let media = FakeMediaSession()
        media.offerSDP = "v=0\r\na=fingerprint:sha-256 11:22\r\n"
        let runner = CallRunner(context: ctx(), callID: "c1", contactID: "bob",
                                signaling: GatedCallSignaling(base: base, connection: conn, timeout: .milliseconds(200)),
                                sealer: PlainCallSignalSealer(), clock: ManualClock(), makeMedia: { media },
                                publish: { _ in }, record: { _, _, _, _ in })
        runner.handle(.incoming(callID: "c1", contact: bob, fromDeviceID: "d1",
                                ring: CallSignalPayload(kind: .ring, callID: "c1", video: false)))
        runner.handle(.answer(video: false))
        await runner.settled()
        #expect(conn.connects >= 1)
        #expect(base.sent.map(\.frame) == ["call.answer"])
    }

    // 2. a local failure never declines a ringing call
    private func failingRunner(_ base: FakeCallSignaling, ice: Bool = true) -> (CallRunner, () -> [CallState?], () -> Int) {
        final class Box: @unchecked Sendable { var states: [CallState?] = []; var history = 0 }
        let box = Box()
        let signaling: CallSignaling = ice ? IceFailing(base: base) : base
        let r = CallRunner(context: ctx(), callID: "c1", contactID: "bob", signaling: signaling,
                           sealer: PlainCallSignalSealer(), clock: ManualClock(), makeMedia: { FakeMediaSession() },
                           publish: { box.states.append($0) }, record: { _, _, _, _ in box.history += 1 })
        return (r, { box.states }, { box.history })
    }

    @Test func ringingCalleeFailureIsLocalAndSilent() async {
        let base = FakeCallSignaling()
        let (r, states, history) = failingRunner(base)
        r.handle(.incoming(callID: "c1", contact: bob, fromDeviceID: "dA",
                           ring: CallSignalPayload(kind: .ring, callID: "c1", video: false)))
        await r.settled()
        #expect(states().last??.phase == .ended(.failed))
        #expect(base.sent.isEmpty)            // no decline, no hangup: other devices keep ringing
        #expect(history() == 1)
    }

    @Test func outgoingFailureHangsUpAsFailed() async {
        let base = FakeCallSignaling()
        let (r, states, _) = failingRunner(base)
        r.handle(.start(contact: bob, video: false, callID: "c1"))
        await r.settled()
        #expect(states().last??.phase == .ended(.failed))
        #expect(base.sent.map(\.frame) == ["call.hangup"])
    }

    @Test func answeredCalleeFailureHangsUp() async {
        var m = CallSessionMachine(context: ctx(), clock: ManualClock())
        _ = m.handle(.incoming(callID: "c1", contact: bob, fromDeviceID: "dA",
                               ring: CallSignalPayload(kind: .ring, callID: "c1", video: false)))
        _ = m.handle(.answer(video: false))
        let fx = m.handle(.localFailure)
        #expect(fx.contains(.sendHangup))
        #expect(m.state?.phase == .ended(.failed))
    }

    // 3. per-call CallKit flag
    @Test func systemEndLatchDoesNotLeakIntoTheNextCall() {
        var latch = SystemEndLatch()
        latch.begin()
        latch.systemEnded(liveCall: true)
        #expect(latch.shouldReportEnd() == false)   // the system already ended it
        latch.begin()
        #expect(latch.shouldReportEnd() == true)    // next call is reported normally
        // an end tap with nothing live (race, or provider reset while idle) must not poison the next call
        latch.systemEnded(liveCall: false)
        latch.begin()
        #expect(latch.shouldReportEnd() == true)
        latch.systemEnded(liveCall: true)
        latch.begin()                                // a new call starts before the stale end was consumed
        #expect(latch.shouldReportEnd() == true)
    }
}

private final class IceFailing: CallSignaling, @unchecked Sendable {
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
