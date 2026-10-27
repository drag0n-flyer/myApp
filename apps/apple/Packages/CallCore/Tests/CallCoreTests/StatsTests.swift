import XCTest
@testable import CallCore

final class StatsTests: XCTestCase {
    private final class Stub: StatsSource, @unchecked Sendable {
        var samples: [[RawStat]]
        init(_ s: [[RawStat]]) { samples = s }
        func rawStats() async -> [RawStat] { samples.isEmpty ? [] : samples.removeFirst() }
    }

    private func inbound(received: Double, lost: Double) -> [RawStat] {
        [RawStat(id: "I", type: "inbound-rtp", numbers: ["packetsReceived": received, "packetsLost": lost, "jitter": 0.02],
                 strings: ["kind": "audio"])]
    }

    func testLossFractionIsDelta() async {
        let sampler = StatsSampler(source: Stub([inbound(received: 100, lost: 0), inbound(received: 190, lost: 10)]))
        let first = await sampler.sample()
        XCTAssertEqual(first.inboundLossFraction, 0, accuracy: 1e-9)
        let second = await sampler.sample()
        XCTAssertEqual(second.inboundLossFraction, 10.0 / 100.0, accuracy: 1e-9)
        XCTAssertEqual(second.jitter, 0.02)
    }

    func testNominatedPairWithoutTransportSelection() async {
        let report = [
            RawStat(id: "P1", type: "candidate-pair", numbers: ["nominated": 0], strings: ["state": "succeeded"]),
            RawStat(id: "P2", type: "candidate-pair", numbers: ["nominated": 1, "availableOutgoingBitrate": 24000, "currentRoundTripTime": 0.08],
                    strings: ["state": "succeeded", "localCandidateId": "L"]),
            RawStat(id: "L", type: "local-candidate", strings: ["candidateType": "host"]),
            RawStat(id: "T", type: "transport", numbers: ["bytesSent": 10]),
        ]
        let s = await StatsSampler(source: Stub([report])).sample()
        XCTAssertEqual(s.availableOutgoingBitrate, 24000)
        XCTAssertEqual(s.roundTripTime, 0.08)
        XCTAssertFalse(s.usingRelay)
    }

    func testTransportTotalsAreUsed() async {
        let report = [
            RawStat(id: "T", type: "transport", numbers: ["bytesSent": 1000, "bytesReceived": 2000, "packetsSent": 10, "packetsReceived": 20]),
            RawStat(id: "O", type: "outbound-rtp", numbers: ["bytesSent": 400, "packetsSent": 4], strings: ["kind": "audio"]),
        ]
        let s = await StatsSampler(source: Stub([report])).sample()
        XCTAssertEqual(s.bytesSent, 1000)
        XCTAssertEqual(s.bytesReceived, 2000)
        XCTAssertEqual(s.packetsSent, 10)
        XCTAssertEqual(s.packetsReceived, 20)
    }

    func testVideoParametersFollowLadder() {
        for tier in VideoTier.allCases {
            let p = MediaLogic.videoParameters(tier), l = VideoLadder.spec(tier)
            XCTAssertEqual(p.width, l.width); XCTAssertEqual(p.height, l.height)
            XCTAssertEqual(p.fps, l.fps); XCTAssertEqual(p.maxBitrate, l.maxBps)
        }
    }

    func testTierNeverExceedsCeiling() {
        XCTAssertEqual(MediaLogic.clampTier(.p360, ceiling: .balanced), .p270)
        XCTAssertEqual(MediaLogic.clampTier(.p360, ceiling: .ultraLow), .p180)
        XCTAssertEqual(MediaLogic.clampTier(.p270, ceiling: .high), .p270)
    }

    func testEffectiveTierAtSetVideoBoundary() {
        XCTAssertNil(MediaLogic.effectiveTier(nil, ceiling: .high))
        XCTAssertEqual(MediaLogic.effectiveTier(.p360, ceiling: .balanced), .p270)
        XCTAssertEqual(MediaLogic.effectiveTier(.p360, ceiling: .dataSaver), .p180)
        XCTAssertEqual(MediaLogic.effectiveTier(.p360, ceiling: nil), .p360)
    }

    func testVideoTierFormat() {
        let sizes = [VideoSize(width: 192, height: 144), VideoSize(width: 352, height: 288), VideoSize(width: 480, height: 270),
                     VideoSize(width: 640, height: 360), VideoSize(width: 1280, height: 720)]
        XCTAssertEqual(VideoFormatPicker.index(in: sizes, for: .p270), 2)
        XCTAssertEqual(VideoFormatPicker.index(in: sizes, for: .p360), 3)
        // No exact 320x180: the nearest larger (352x288 is larger in both dimensions).
        XCTAssertEqual(VideoFormatPicker.index(in: sizes, for: .p180), 1)
        // Nothing large enough: the largest available.
        XCTAssertEqual(VideoFormatPicker.index(in: [VideoSize(width: 192, height: 144)], for: .p270), 0)
        XCTAssertNil(VideoFormatPicker.index(in: [], for: .p270))
    }
}
