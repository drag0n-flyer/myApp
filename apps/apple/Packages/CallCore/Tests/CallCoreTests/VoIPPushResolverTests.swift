import Testing
import Foundation
import AppCore
@testable import CallCore

@Suite struct VoIPPushResolverTests {
    static let bob = Contact(id: "bob", displayName: "Bob", username: "bob")

    func sealed(_ p: CallSignalPayload, sender: String, _ s: PlainCallSignalSealer) async throws -> Data {
        let d = try await s.seal(p, to: sender)
        s.setSender("\(sender)/d1", of: d)
        return d
    }
    func resolver(_ s: PlainCallSignalSealer, contacts: [String: Contact] = ["bob": bob],
                  status: CallStatus? = .ringing) -> VoIPPushResolver {
        VoIPPushResolver(sealer: s, contact: { contacts[$0] }, status: { _ in status })
    }
    let ring = CallSignalPayload(kind: .ring, callID: "c1", video: true)

    @Test func recoversSenderWithoutFromArgument() async throws {
        let s = PlainCallSignalSealer()
        let r = await resolver(s).resolve(sealed: try await sealed(ring, sender: "bob", s))
        guard case let .ring(p, contact) = r.payload else { Issue.record("not ring"); return }
        #expect(p == ring); #expect(contact == Self.bob)
        #expect(!r.isStranger); #expect(r.status == .ringing)
    }
    @Test func strangerIsFlaggedAndNamedUnknown() async throws {
        let s = PlainCallSignalSealer()
        let r = await resolver(s, contacts: [:]).resolve(sealed: try await sealed(ring, sender: "eve", s))
        guard case let .ring(_, contact) = r.payload else { Issue.record("not ring"); return }
        #expect(r.isStranger); #expect(contact.id == "eve"); #expect(contact.displayName == "Unknown")
    }
    @Test func emptyAndMissingAreEmpty() async {
        let s = PlainCallSignalSealer()
        for d in [nil, Data()] as [Data?] {
            let r = await resolver(s).resolve(sealed: d)
            guard case .empty = r.payload else { Issue.record("not empty"); return }
        }
    }
    @Test func openFailureIsUndecryptable() async {
        let r = await resolver(PlainCallSignalSealer()).resolve(sealed: Data([1, 2, 3]))
        guard case .undecryptable = r.payload else { Issue.record("not undecryptable"); return }
    }
    @Test func nonRingKindIsUndecryptable() async throws {
        let s = PlainCallSignalSealer()
        let offer = CallSignalPayload(kind: .offer, callID: "c1", sdp: "v=0")
        let r = await resolver(s).resolve(sealed: try await sealed(offer, sender: "bob", s))
        guard case .undecryptable = r.payload else { Issue.record("not undecryptable"); return }
    }
    @Test func endedStatusFlowsToDecision() async throws {
        let s = PlainCallSignalSealer()
        let r = await resolver(s, status: .ended(reason: "hangup")).resolve(sealed: try await sealed(ring, sender: "bob", s))
        #expect(VoIPPushDecision.decide(mustReport: nil, payload: r.payload, isStranger: r.isStranger, status: r.status)
                == .reportThenEnd(name: "Bob", reason: .remoteEnded))
    }
}

@Suite struct VoIPPushStagesTests {
    @Test func immediateReportsUnlessNotRequired() {
        #expect(VoIPPushDecision.immediate(mustReport: nil) == .reportGeneric)
        #expect(VoIPPushDecision.immediate(mustReport: true) == .reportGeneric)
        #expect(VoIPPushDecision.immediate(mustReport: false) == .deferToResolution)
    }
    @Test func followUpAfterGenericReport() {
        #expect(VoIPPushDecision.followUp(reportedGeneric: true, action: .reportRing(name: "Bob", video: true))
                == .updateCaller(name: "Bob", video: true))
        #expect(VoIPPushDecision.followUp(reportedGeneric: true, action: .reportThenEnd(name: "Bob", reason: .unanswered))
                == .end(reason: .unanswered))
        #expect(VoIPPushDecision.followUp(reportedGeneric: true, action: .ignore) == .end(reason: .failed))
    }
    @Test func followUpWhenNothingReportedYet() {
        #expect(VoIPPushDecision.followUp(reportedGeneric: false, action: .reportRing(name: "Bob", video: false))
                == .reportRing(name: "Bob", video: false))
        #expect(VoIPPushDecision.followUp(reportedGeneric: false, action: .reportThenEnd(name: "Bob", reason: .failed)) == .none)
        #expect(VoIPPushDecision.followUp(reportedGeneric: false, action: .ignore) == .none)
    }
}
