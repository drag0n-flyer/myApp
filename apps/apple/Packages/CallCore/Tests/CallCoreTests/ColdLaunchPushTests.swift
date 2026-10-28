import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")

/// The app's sealer before sign-in finishes: fails closed until the live store is plugged in.
private final class LateSealer: SenderRecoveringSealer, @unchecked Sendable {
    private let lock = NSLock()
    private var current: any SenderRecoveringSealer = UnavailableCallSignalSealer()
    func use(_ s: any SenderRecoveringSealer) { lock.withLock { current = s } }
    private var sealer: any SenderRecoveringSealer { lock.withLock { current } }
    func seal(_ payload: CallSignalPayload, to contactID: String) async throws -> Data {
        try await sealer.seal(payload, to: contactID)
    }
    func open(_ data: Data, from contactID: String) async throws -> (payload: CallSignalPayload, senderDeviceID: String) {
        try await sealer.open(data, from: contactID)
    }
    func openRecoveringSender(_ data: Data) async throws
        -> (payload: CallSignalPayload, senderAccountID: String, senderDeviceID: String) {
        try await sealer.openRecoveringSender(data)
    }
}

@Suite struct ColdLaunchPushTests {
    private func sealedRing(_ s: PlainCallSignalSealer) async throws -> Data {
        let d = try await s.seal(CallSignalPayload(kind: .ring, callID: "c1"), to: "bob")
        s.setSender("bob/d7", of: d)
        return d
    }

    private func resolver(_ s: any SenderRecoveringSealer, gate: ReadinessGate, timeout: Duration) -> VoIPPushResolver {
        VoIPPushResolver(sealer: s, contact: { $0 == "bob" ? bob : nil }, status: { _ in .ringing },
                         ready: gate, readyTimeout: timeout)
    }

    /// A push that wakes a killed app arrives before sign-in has finished: it is opened only once the session is
    /// ready, with the live sealer, so the call still rings.
    @Test func pushWaitsForTheSessionThenRings() async throws {
        let plain = PlainCallSignalSealer()
        let late = LateSealer()
        let gate = ReadinessGate()
        let d = try await sealedRing(plain)
        let opening = Task { await resolver(late, gate: gate, timeout: .seconds(5)).open(sealed: d) }
        try await Task.sleep(for: .milliseconds(50))
        late.use(plain)   // sign-in finished: the live store is in place
        await gate.open()
        let o = await opening.value
        guard case let .ring(p, contact) = o.payload else { Issue.record("not ring: \(o.payload)"); return }
        #expect(p.callID == "c1" && contact == bob)
        #expect(o.senderDeviceID == "d7")
    }

    /// The session never becomes ready: after the bounded wait the push ends as failed (it was already reported).
    @Test func pushEndsAsFailedWhenTheSessionNeverComes() async throws {
        let plain = PlainCallSignalSealer()
        let d = try await sealedRing(plain)
        let t0 = ContinuousClock.now
        let o = await resolver(plain, gate: ReadinessGate(), timeout: .milliseconds(100)).open(sealed: d)
        #expect(ContinuousClock.now - t0 < .seconds(2))
        guard case .undecryptable = o.payload else { Issue.record("expected undecryptable, got \(o.payload)"); return }
        let action = VoIPPushDecision.decide(mustReport: nil, payload: o.payload, isStranger: o.isStranger, status: nil)
        #expect(VoIPPushDecision.followUp(reportedGeneric: true, action: action) == .end(reason: .failed))
    }

    @Test func gateOpenAlreadyDoesNotWait() async throws {
        let gate = ReadinessGate()
        await gate.open()
        #expect(await gate.wait(timeout: .milliseconds(1)))
    }

    @Test func closedGateWaitsAgain() async throws {
        let gate = ReadinessGate()
        await gate.open()
        await gate.close()
        #expect(await gate.wait(timeout: .milliseconds(20)) == false)
    }

    @Test func pushCarriesTheCredentialDevice() async throws {
        let plain = PlainCallSignalSealer()
        let gate = ReadinessGate()
        await gate.open()
        let o = await resolver(plain, gate: gate, timeout: .seconds(1)).open(sealed: try await sealedRing(plain))
        #expect(o.senderDeviceID == "d7")
    }

    @Test func inAppHangupGoesThroughTheSystemWhenItKnowsTheCall() {
        #expect(InAppHangup.route(systemKnowsCall: true) == .requestSystemEnd)
        #expect(InAppHangup.route(systemKnowsCall: false) == .hangUpHere)
    }
}
