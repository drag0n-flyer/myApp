import Testing
import Foundation
import AppCore
@testable import CallCore

@Suite struct ContractsTests {
    @Test func payloadRoundTrip() throws {
        let p = CallSignalPayload(
            kind: .offer, callID: "c1", video: true, sdp: "v=0", dtlsFingerprint: "sha-256 AB:CD",
            candidate: IceCandidate(sdp: "candidate:1", sdpMid: "0", sdpMLineIndex: 0),
            ceiling: .balanced, videoOn: false)
        let back = try JSONDecoder().decode(CallSignalPayload.self, from: JSONEncoder().encode(p))
        #expect(back == p)
    }

    @Test func plainSealerRejectsWrongSender() async throws {
        let sealer = PlainCallSignalSealer()
        let p = CallSignalPayload(kind: .ring, callID: "c1", video: false)
        let data = try await sealer.seal(p, to: "bob")
        sealer.setSender("bob", of: data)
        await #expect(throws: CallSealError.wrongSender) {
            _ = try await sealer.open(data, from: "carol")
        }
        #expect(try await sealer.open(data, from: "bob") == p)
    }

    @Test func fakeSignalingRecordsFrames() async throws {
        let s = FakeCallSignaling()
        try await s.invite(callID: "c1", to: "bob", video: false, payload: Data([1]))
        try await s.hangup(callID: "c1")
        #expect(s.sent.map(\.frame) == ["call.invite", "call.hangup"])
    }

    @Test func fakeMediaSessionRecordsAndEmits() async throws {
        let m = FakeMediaSession()
        try await m.configure(iceServers: [], policy: .relay, video: false)
        var it = m.events.makeAsyncIterator()
        m.emit(.iceState(.connected))
        await m.close()
        #expect(m.calls == ["configure", "close"])
        #expect(await it.next() == .iceState(.connected))
    }

    @Test func manualClockAdvances() {
        let c = ManualClock(Date(timeIntervalSince1970: 0))
        c.advance(5)
        #expect(c.now == Date(timeIntervalSince1970: 5))
    }
}
