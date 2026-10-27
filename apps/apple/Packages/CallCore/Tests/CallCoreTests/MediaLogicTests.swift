import XCTest
@testable import CallCore

final class MediaLogicTests: XCTestCase {
    func testIceStateMapping() {
        XCTAssertEqual(MediaLogic.iceState(fromRTCRaw: 2), .connected)
        XCTAssertEqual(MediaLogic.iceState(fromRTCRaw: 4), .failed)
        XCTAssertEqual(MediaLogic.iceState(fromRTCRaw: 5), .disconnected)
        XCTAssertNil(MediaLogic.iceState(fromRTCRaw: 7))
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
}
