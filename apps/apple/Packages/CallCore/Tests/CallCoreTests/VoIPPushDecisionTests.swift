import Testing
import Foundation
import AppCore
@testable import CallCore

@Suite struct VoIPPushDecisionTests {
    static let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
    static let ring = PushPayloadState.ring(CallSignalPayload(kind: .ring, callID: "c1", video: false), contact: bob)
    static let videoRing = PushPayloadState.ring(CallSignalPayload(kind: .ring, callID: "c1", video: true), contact: bob)

    struct Row: Sendable {
        let mustReport: Bool?
        let payload: PushPayloadState
        let stranger: Bool
        let status: CallStatus?
        let expected: VoIPPushAction
    }

    static let rows: [Row] = [
        Row(mustReport: nil, payload: ring, stranger: false, status: .ringing, expected: .reportRing(name: "Bob", video: false)),
        Row(mustReport: nil, payload: .undecryptable, stranger: false, status: nil, expected: .reportThenEnd(name: "Unknown", reason: .failed)),
        Row(mustReport: nil, payload: .empty, stranger: false, status: nil, expected: .reportThenEnd(name: "Unknown", reason: .failed)),
        Row(mustReport: nil, payload: ring, stranger: true, status: .ringing, expected: .reportThenEnd(name: "Bob", reason: .unanswered)),
        Row(mustReport: nil, payload: ring, stranger: false, status: .ended(reason: "hangup"), expected: .reportThenEnd(name: "Bob", reason: .remoteEnded)),
        Row(mustReport: true, payload: ring, stranger: true, status: .ringing, expected: .reportThenEnd(name: "Bob", reason: .unanswered)),
        Row(mustReport: false, payload: ring, stranger: true, status: .ringing, expected: .ignore),
        Row(mustReport: false, payload: ring, stranger: false, status: .ringing, expected: .reportRing(name: "Bob", video: false)),
        // extras
        Row(mustReport: nil, payload: videoRing, stranger: false, status: nil, expected: .reportRing(name: "Bob", video: true)),
        Row(mustReport: nil, payload: ring, stranger: false, status: .answered, expected: .reportThenEnd(name: "Bob", reason: .remoteEnded)),
        Row(mustReport: false, payload: .undecryptable, stranger: false, status: nil, expected: .ignore),
        Row(mustReport: false, payload: ring, stranger: false, status: .ended(reason: "missed"), expected: .ignore),
    ]

    @Test(arguments: rows) func table(_ r: Row) {
        let got = VoIPPushDecision.decide(mustReport: r.mustReport, payload: r.payload, isStranger: r.stranger, status: r.status)
        #expect(got == r.expected)
    }

    @Test func everyVoIPPushIsReported() {
        let payloads: [PushPayloadState] = [Self.ring, Self.videoRing, .undecryptable, .empty]
        let statuses: [CallStatus?] = [nil, .ringing, .answered, .ended(reason: "x"), .unknown]
        for mustReport in [nil, true] as [Bool?] {
            for p in payloads { for s in [false, true] { for st in statuses {
                #expect(VoIPPushDecision.decide(mustReport: mustReport, payload: p, isStranger: s, status: st) != .ignore)
            } } }
        }
    }
}

@Suite struct VoIPPushBodyTests {
    @Test func unpadsPayload() {
        let padded = Data([0, 3, 9, 8, 7, 0, 0, 0])
        #expect(VoIPPushBody.payload(d: padded.base64EncodedString()) == Data([9, 8, 7]))
    }
    @Test func emptyPushIsEmptyPayload() {
        #expect(VoIPPushBody.payload(d: Data([0, 0, 0, 0]).base64EncodedString()) == Data())
    }
    @Test func malformedIsNil() {
        #expect(VoIPPushBody.payload(d: nil) == nil)
        #expect(VoIPPushBody.payload(d: "!!!") == nil)
        #expect(VoIPPushBody.payload(d: Data([0]).base64EncodedString()) == nil)
        #expect(VoIPPushBody.payload(d: Data([0, 9, 1]).base64EncodedString()) == nil)
    }
}
