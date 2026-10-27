@preconcurrency import XCTest
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
        for (s, peer) in [(a, "B"), (b, "A")] {
            let relay = relay
            pumps.append(Task {
                for await e in s.events { if case .localCandidate(let c) = e { await relay.deliver(to: peer, c) } }
            })
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
        // Wait for traffic instead of sleeping a fixed time (transport totals count STUN/DTLS too, so this needs no audio).
        var s = await a.stats()
        let end = Date().addingTimeInterval(15)
        while s.packetsReceived == 0, Date() < end {
            try await Task.sleep(nanoseconds: 250_000_000)
            s = await a.stats()
        }
        XCTAssertGreaterThan(s.packetsReceived, 0)
        XCTAssertFalse(s.usingRelay)
        // Audio-specific values need real audio, which only flows when the runner captures (the tone hook ran).
        try await Task.sleep(nanoseconds: 2_000_000_000)
        s = await a.stats()
        let toneRan = LoopbackTests.tone.frames != f0
        await a.close(); await b.close()
        if !toneRan {
            if ProcessInfo.processInfo.environment["CI"] != nil { XCTFail("no audio capture on this CI runner: audio stats not checked") }
            throw XCTSkip("no audio capture on this runner, so no audio was sent")
        }
        XCTAssertGreaterThan(s.bytesSent, 0)
        XCTAssertEqual(s.audioCodec, "audio/opus")
    }

    func testAudioMaxBitrateApplied() async throws {
        let (a, b) = try await connectedPair()
        await a.setAudioMaxBitrate(8000)
        XCTAssertEqual(a.audioMaxBitrateForTesting, 8000)
        await a.close(); await b.close()
    }
}
