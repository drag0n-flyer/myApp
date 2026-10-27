import XCTest
import AppCore
import CallCore
@testable import CallMedia

/// Needs the WebRTC XCFramework (Mac CI). UNVERIFIED: not run yet.
/// Uses M1's `CandidateRelay` (no dropped early candidates) and the shared tone capture processor.
final class MediaStatsTests: XCTestCase {
    private let relay = CandidateRelay()
    private var pumps: [Task<Void, Never>] = []
    override class func setUp() { MediaFactory.captureProcessor = LoopbackTests.tone }  // before the first shared()
    override func tearDown() { pumps.forEach { $0.cancel() }; pumps = [] }

    private func connectedPair() async throws -> (WebRTCMediaSession, WebRTCMediaSession) {
        let a = WebRTCMediaSession(), b = WebRTCMediaSession()
        try await a.configure(iceServers: [], policy: .all, video: false)
        try await b.configure(iceServers: [], policy: .all, video: false)
        relay.register("A", a); relay.register("B", b)
        for (name, s, peer) in [("A", a, "B"), ("B", b, "A")] {
            pumps.append(Task { [relay] in
                for await e in s.events { if case .localCandidate(let c) = e { await relay.deliver(to: peer, c) } }
            })
            _ = name
        }
        let p = AudioProfile.for(.balanced)
        let offer = try await a.makeOffer(receive: p, iceRestart: false)
        let answer = try await b.acceptOffer(offer, send: p, receive: p)
        await relay.remoteDescriptionSet(on: "B")
        try await a.acceptAnswer(answer, send: p)
        await relay.remoteDescriptionSet(on: "A")
        return (a, b)
    }

    func testStatsAfterConnect() async throws {
        let (a, b) = try await connectedPair()
        let f0 = LoopbackTests.tone.frames
        try await Task.sleep(nanoseconds: 3_000_000_000)
        let s = await a.stats()
        let toneRan = LoopbackTests.tone.frames != f0
        await a.close(); await b.close()
        try XCTSkipIf(!toneRan, "no audio capture on this runner, so no audio was sent")
        XCTAssertGreaterThan(s.bytesSent, 0)
        XCTAssertGreaterThan(s.packetsReceived, 0)
        XCTAssertFalse(s.usingRelay)
        XCTAssertEqual(s.audioCodec, "audio/opus")
    }

    func testAudioMaxBitrateApplied() async throws {
        let (a, b) = try await connectedPair()
        await a.setAudioMaxBitrate(8000)
        let sender = try XCTUnwrap(a.audioSenderForTesting)
        XCTAssertEqual(sender.parameters.encodings.first?.maxBitrateBps?.intValue, 8000)
        await a.close(); await b.close()
    }
}
