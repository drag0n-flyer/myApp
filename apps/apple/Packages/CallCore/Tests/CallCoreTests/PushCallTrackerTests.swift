import Testing
import Foundation
import AppCore
@testable import CallCore

@Suite struct PushCallTrackerTests {
    let kit = UUID()

    @Test func plainRingSettlesOnceBothKnown() {
        var t = PushCallTracker(); t.begin(kit: kit)
        #expect(t.resolved(kit: kit, callID: "c1", engineKnows: false) == nil)   // report still pending
        #expect(t.reportFinished(kit: kit, refused: false) == .ring)
        #expect(t.isRinging(kit: kit))
        #expect(t.callID(forKit: kit) == "c1")
    }
    @Test func reportFirstThenResolution() {
        var t = PushCallTracker(); t.begin(kit: kit)
        #expect(t.reportFinished(kit: kit, refused: false) == nil)
        #expect(t.resolved(kit: kit, callID: "c1", engineKnows: false) == .ring)
    }
    @Test func answerBeforeResolutionIsAppliedAfter() {
        var t = PushCallTracker(); t.begin(kit: kit)
        #expect(t.reportFinished(kit: kit, refused: false) == nil)
        #expect(t.userAction(.answer, kit: kit) == .recorded)
        #expect(t.resolved(kit: kit, callID: "c1", engineKnows: false) == .answerNow)
        #expect(!t.isRinging(kit: kit))
    }
    @Test func endBeforeResolutionDeclinesInsteadOfRinging() {
        var t = PushCallTracker(); t.begin(kit: kit)
        #expect(t.userAction(.end, kit: kit) == .recorded)
        #expect(t.reportFinished(kit: kit, refused: false) == nil)
        #expect(t.resolved(kit: kit, callID: "c1", engineKnows: false) == .decline)
    }
    @Test func actionsAfterResolutionAreForwardedWithServerCallID() {
        var t = PushCallTracker(); t.begin(kit: kit)
        _ = t.reportFinished(kit: kit, refused: false); _ = t.resolved(kit: kit, callID: "c1", engineKnows: false)
        #expect(t.userAction(.answer, kit: kit) == .forward(callID: "c1"))
        #expect(t.userAction(.end, kit: UUID()) == .passthrough)   // not a push call
    }
    @Test func refusedReportNeverReachesEngine() {
        var t = PushCallTracker(); t.begin(kit: kit)
        #expect(t.reportFinished(kit: kit, refused: true) == nil)
        #expect(t.resolved(kit: kit, callID: "c1", engineKnows: false) == .finish)
        var u = PushCallTracker(); u.begin(kit: kit)
        #expect(u.resolved(kit: kit, callID: "c1", engineKnows: false) == nil)
        #expect(u.reportFinished(kit: kit, refused: true) == .finish)
        #expect(u.callID(forKit: kit) == nil)
    }
    @Test func engineAlreadyKnowsCallEndsPushCallSilently() {   // WebSocket ring first
        var t = PushCallTracker(); t.begin(kit: kit)
        _ = t.reportFinished(kit: kit, refused: false)
        #expect(t.resolved(kit: kit, callID: "c1", engineKnows: true) == .endDuplicate)
        #expect(t.callID(forKit: kit) == nil)
    }
    @Test func webSocketRingAfterPushReportedIsSuppressed() {   // push first
        var t = PushCallTracker(); t.begin(kit: kit)
        _ = t.reportFinished(kit: kit, refused: false); _ = t.resolved(kit: kit, callID: "c1", engineKnows: false)
        #expect(t.webSocketRing(callID: "c1") == false)
        #expect(t.webSocketRing(callID: "c2") == true)
    }
    @Test func webSocketRingWhilePushStillResolvingIsReportedThenPushIsDuplicate() {
        var t = PushCallTracker(); t.begin(kit: kit)
        #expect(t.webSocketRing(callID: "c1") == true)
        _ = t.reportFinished(kit: kit, refused: false)
        #expect(t.resolved(kit: kit, callID: "c1", engineKnows: true) == .endDuplicate)
    }
    @Test func finishedCallFreesTheCallID() {
        var t = PushCallTracker(); t.begin(kit: kit)
        _ = t.reportFinished(kit: kit, refused: false); _ = t.resolved(kit: kit, callID: "c1", engineKnows: false)
        t.finish(kit: kit)
        #expect(t.webSocketRing(callID: "c1") == true)
    }
}

@Suite struct VoIPPushResolverStatusTests {
    @Test func openIsLocalOnly() async throws {
        let s = PlainCallSignalSealer()
        let d = try await s.seal(CallSignalPayload(kind: .ring, callID: "c1"), to: "bob")
        s.setSender("bob/d1", of: d)
        let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
        let r = VoIPPushResolver(sealer: s, contact: { $0 == "bob" ? bob : nil },
                                 status: { _ in try? await Task.sleep(for: .seconds(60)); return .ringing })
        let t0 = ContinuousClock.now
        let o = await r.open(sealed: d)
        #expect(ContinuousClock.now - t0 < .seconds(1))   // did not wait for status
        guard case let .ring(_, c) = o.payload else { Issue.record("not ring"); return }
        #expect(c == bob)
    }
    @Test func statusTimesOutToNil() async {
        let r = VoIPPushResolver(sealer: PlainCallSignalSealer(), contact: { _ in nil },
                                 status: { _ in try? await Task.sleep(for: .seconds(60)); return .ringing })
        let t0 = ContinuousClock.now
        let st = await r.fetchStatus(callID: "c1", timeout: .milliseconds(50))
        #expect(st == nil)
        #expect(ContinuousClock.now - t0 < .seconds(5))
    }
    @Test func statusReturnedWhenFast() async {
        let r = VoIPPushResolver(sealer: PlainCallSignalSealer(), contact: { _ in nil }, status: { _ in .answered })
        #expect(await r.fetchStatus(callID: "c1", timeout: .seconds(5)) == .answered)
    }
    @Test func missedStatusEndsAsUnanswered() {
        let ring = PushPayloadState.ring(CallSignalPayload(kind: .ring, callID: "c1"),
                                         contact: Contact(id: "bob", displayName: "Bob", username: "bob"))
        for mustReport in [nil, true] as [Bool?] {
            #expect(VoIPPushDecision.decide(mustReport: mustReport, payload: ring, isStranger: false,
                                            status: .ended(reason: "missed")) == .reportThenEnd(name: "Bob", reason: .unanswered))
        }
        #expect(VoIPPushDecision.decide(mustReport: nil, payload: ring, isStranger: false,
                                        status: .ended(reason: "hangup")) == .reportThenEnd(name: "Bob", reason: .remoteEnded))
    }
}

@Suite struct PushCallLateEndTests {
    let kit = UUID()
    func ringing() -> PushCallTracker {
        var t = PushCallTracker(); t.begin(kit: kit)
        _ = t.reportFinished(kit: kit, refused: false)
        _ = t.resolved(kit: kit, callID: "c1", engineKnows: false)
        return t
    }

    @Test func lateStatusEndsRingingCallOnce() {
        var t = ringing()
        #expect(t.endRinging(kit: kit, kind: .remoteEnded) == PushLateEnd(callID: "c1", kind: .remoteEnded))
        #expect(t.endRinging(kit: kit, kind: .unanswered) == nil)
        #expect(!t.isRinging(kit: kit))
    }
    @Test func declineAfterOpenThenLateStatusIsOneEnd() {
        var t = ringing()
        #expect(t.userAction(.end, kit: kit) == .forward(callID: "c1"))
        #expect(!t.isRinging(kit: kit))
        #expect(t.endRinging(kit: kit, kind: .remoteEnded) == nil)
    }
    @Test func answeredCallIsNotEndedByLateStatusOrCap() {
        var t = ringing()
        _ = t.userAction(.answer, kit: kit)
        #expect(t.endRinging(kit: kit, kind: .answeredElsewhere) == nil)
        #expect(t.capReached(kit: kit) == .none)
    }
    @Test func capAfterEngineRingingTellsEngineOnce() {
        var t = ringing()
        #expect(t.capReached(kit: kit) == .endRinging(PushLateEnd(callID: "c1", kind: .unanswered)))
        #expect(t.capReached(kit: kit) == .none)
        #expect(t.endRinging(kit: kit, kind: .remoteEnded) == nil)
    }
    @Test func capBeforeSettlingEndsCallKitOnlyOnce() {
        var t = PushCallTracker(); t.begin(kit: kit)
        #expect(t.capReached(kit: kit) == .endUnsettled)
        #expect(t.capReached(kit: kit) == .none)
        #expect(t.resolved(kit: kit, callID: "c1", engineKnows: false) == nil)
    }
    @Test func statusMapsToCallKitEndKind() {
        #expect(PushCallTracker.lateEndKind(status: .answered) == .answeredElsewhere)
        #expect(PushCallTracker.lateEndKind(status: .ended(reason: "missed")) == .unanswered)
        #expect(PushCallTracker.lateEndKind(status: .ended(reason: "hangup")) == .remoteEnded)
        #expect(PushCallTracker.lateEndKind(status: .ended(reason: "answered_elsewhere")) == .answeredElsewhere)
        #expect(PushCallTracker.lateEndKind(status: .ringing) == nil)
        #expect(PushCallTracker.lateEndKind(status: .unknown) == nil)
    }
    @Test func briefedEndReasonsMapToKinds() {
        #expect(CallKitEndKind(EndReason.failed) == .failed)
        #expect(CallKitEndKind(EndReason.remoteEnded) == .remoteEnded)
        #expect(CallKitEndKind(EndReason.unanswered) == .unanswered)
    }
    @Test func duplicatePushForSameCallDoesNotRingTwice() {
        let k2 = UUID()
        var t = ringing()
        t.begin(kit: k2); _ = t.reportFinished(kit: k2, refused: false)
        #expect(t.resolved(kit: k2, callID: "c1", engineKnows: false) == .endDuplicate)
    }
}
