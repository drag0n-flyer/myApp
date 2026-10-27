import XCTest
import AppCore
import CallCore
@testable import CallMedia

/// Needs the WebRTC XCFramework (Mac CI). UNVERIFIED: not run yet.
final class MediaStatsTests: XCTestCase {
    private func connectedPair() async throws -> (WebRTCMediaSession, WebRTCMediaSession, [Task<Void, Never>]) {
        let a = WebRTCMediaSession(), b = WebRTCMediaSession()
        try await a.configure(iceServers: [], policy: .all, video: false)
        try await b.configure(iceServers: [], policy: .all, video: false)
        let pumps = [
            Task { for await e in a.events { if case .localCandidate(let c) = e { try? await b.add(c) } } },
            Task { for await e in b.events { if case .localCandidate(let c) = e { try? await a.add(c) } } },
        ]
        let p = AudioProfile.for(.balanced)
        let offer = try await a.makeOffer(receive: p, iceRestart: false)
        let answer = try await b.acceptOffer(offer, send: p, receive: p)
        try await a.acceptAnswer(answer, send: p)
        return (a, b, pumps)
    }

    func testStatsAfterConnect() async throws {
        let (a, b, pumps) = try await connectedPair()
        try await Task.sleep(nanoseconds: 3_000_000_000)
        let s = await a.stats()
        XCTAssertGreaterThan(s.bytesSent, 0)
        XCTAssertGreaterThan(s.packetsReceived, 0)
        XCTAssertFalse(s.usingRelay)
        XCTAssertEqual(s.audioCodec, "audio/opus")
        pumps.forEach { $0.cancel() }
        await a.close(); await b.close()
    }

    func testAudioMaxBitrateApplied() async throws {
        let (a, b, pumps) = try await connectedPair()
        await a.setAudioMaxBitrate(8000)
        let sender = try XCTUnwrap(a.audioSenderForTesting)
        XCTAssertEqual(sender.parameters.encodings.first?.maxBitrateBps?.intValue, 8000)
        pumps.forEach { $0.cancel() }
        await a.close(); await b.close()
    }
}
