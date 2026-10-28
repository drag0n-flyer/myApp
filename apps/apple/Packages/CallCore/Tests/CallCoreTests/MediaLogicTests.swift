import XCTest
import AppCore
@testable import CallCore

final class MediaLogicTests: XCTestCase {
    func testIceStateMapping() {
        XCTAssertEqual(MediaLogic.iceState(fromRTCRaw: 2), .connected)
        XCTAssertEqual(MediaLogic.iceState(fromRTCRaw: 4), .failed)
        XCTAssertEqual(MediaLogic.iceState(fromRTCRaw: 5), .disconnected)
        XCTAssertNil(MediaLogic.iceState(fromRTCRaw: 7))
    }

    func testIceStateMappingIsComplete() {
        let want: [IceState] = [.new, .checking, .connected, .completed, .failed, .disconnected, .closed]
        XCTAssertEqual((0...6).compactMap { MediaLogic.iceState(fromRTCRaw: $0) }, want)
    }

    /// The plan's video ladder: 100k / 200k / 300k, never above what the ceiling allows.
    func testVideoCapsMatchLadderAndCeiling() {
        XCTAssertEqual(MediaLogic.videoParameters(.p180).maxBitrate, 100_000)
        XCTAssertEqual(MediaLogic.videoParameters(.p270).maxBitrate, 200_000)
        XCTAssertEqual(MediaLogic.videoParameters(.p360).maxBitrate, 300_000)
        let cap: [DataMode: Int] = [.ultraLow: 100_000, .dataSaver: 100_000, .balanced: 200_000, .high: 300_000]
        for mode in DataMode.allCases {
            let tier = VideoLadder.allowed(ceiling: mode)!
            XCTAssertLessThanOrEqual(MediaLogic.videoParameters(tier).maxBitrate, cap[mode]!, "\(mode)")
        }
        for tier in VideoTier.allCases {
            let spec = VideoLadder.spec(tier), p = MediaLogic.videoParameters(tier)
            XCTAssertEqual(p.height, spec.height); XCTAssertEqual(p.fps, spec.fps)
            XCTAssertLessThanOrEqual(p.maxBitrate, spec.maxBps)
        }
    }

    func testPoolAndTurn() {
        XCTAssertEqual(MediaLogic.candidatePoolSize(prewarm: true), 1)
        XCTAssertEqual(MediaLogic.candidatePoolSize(prewarm: false), 0)
        XCTAssertFalse(MediaLogic.hasTurn([IceServer(urls: ["stun:a:3478"])]))
        XCTAssertTrue(MediaLogic.hasTurn([IceServer(urls: ["TURNS:a:443"])]))
    }

    func testSenderCap() {
        let p = AudioProfile.for(.ultraLow)
        XCTAssertEqual(MediaLogic.senderBitrateCap(requested: 50_000, profile: p), p.maxAverageBitrate)
        XCTAssertEqual(MediaLogic.senderBitrateCap(requested: 5_000, profile: p), 5_000)
    }

    func testStatsAggregation() {
        var agg = MediaStatsAggregator()
        let base: [RawStat] = [
            RawStat(id: "T", type: "transport", strings: ["selectedCandidatePairId": "P"]),
            RawStat(id: "P", type: "candidate-pair", numbers: ["availableOutgoingBitrate": 30000, "currentRoundTripTime": 0.05],
                    strings: ["localCandidateId": "L"]),
            RawStat(id: "L", type: "local-candidate", strings: ["candidateType": "relay"]),
            RawStat(id: "C", type: "codec", strings: ["mimeType": "audio/opus"]),
            RawStat(id: "I", type: "inbound-rtp", numbers: ["packetsReceived": 90, "packetsLost": 10, "jitter": 0.01, "bytesReceived": 900],
                    strings: ["kind": "audio", "codecId": "C"]),
            RawStat(id: "O", type: "outbound-rtp", numbers: ["bytesSent": 500, "packetsSent": 50], strings: ["kind": "audio"]),
        ]
        let s = agg.aggregate(base)
        XCTAssertEqual(s.availableOutgoingBitrate, 30000)
        XCTAssertEqual(s.roundTripTime, 0.05)
        XCTAssertTrue(s.usingRelay)
        XCTAssertEqual(s.audioCodec, "audio/opus")
        XCTAssertEqual(s.bytesSent, 500)
        XCTAssertEqual(s.bytesReceived, 900)
        XCTAssertEqual(s.inboundLossFraction, 0.1, accuracy: 1e-9)
        // Second sample: no new loss.
        var next = base
        next[4].numbers["packetsReceived"] = 190
        XCTAssertEqual(agg.aggregate(next).inboundLossFraction, 0, accuracy: 1e-9)
    }

    func testRemoteVideoDetector() {
        var d = RemoteVideoDetector(stallLimit: 2)
        XCTAssertNil(d.sample(frames: nil))
        XCTAssertNil(d.sample(frames: 0))
        XCTAssertEqual(d.sample(frames: 10), true)
        XCTAssertNil(d.sample(frames: 25))
        XCTAssertNil(d.sample(frames: 25))          // one stall
        XCTAssertEqual(d.sample(frames: 25), false) // second stall
        XCTAssertNil(d.sample(frames: 25))          // already off
        XCTAssertEqual(d.sample(frames: 40), true)  // comes back
    }
}
