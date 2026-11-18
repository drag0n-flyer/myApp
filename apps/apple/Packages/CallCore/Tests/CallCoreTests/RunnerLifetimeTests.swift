import Testing
import Foundation
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")
private let server = ServerPolicy(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "h")

/// A media session whose `stats()` waits until the test lets it go, so the ticker is parked inside it.
private final class HeldStatsMedia: MediaSession, @unchecked Sendable {
    let base = FakeMediaSession()
    private let lock = NSLock()
    private var waiting: CheckedContinuation<Void, Never>?
    private var released = false
    private var _statsStarted = false
    var statsStarted: Bool { lock.withLock { _statsStarted } }

    var events: AsyncStream<MediaEvent> { base.events }
    func release() {
        let w: CheckedContinuation<Void, Never>? = lock.withLock { released = true; defer { waiting = nil }; return waiting }
        w?.resume()
    }
    func stats() async -> MediaStats {
        await withCheckedContinuation { (k: CheckedContinuation<Void, Never>) in
            let resumeNow: Bool = lock.withLock {
                _statsStarted = true
                if released { return true }
                waiting = k
                return false
            }
            if resumeNow { k.resume() }
        }
        return MediaStats()
    }
    func configure(iceServers: [IceServer], policy: IceTransportPolicy, video: Bool) async throws { try await base.configure(iceServers: iceServers, policy: policy, video: video) }
    func makeOffer(receive: AudioProfile, iceRestart: Bool) async throws -> String { try await base.makeOffer(receive: receive, iceRestart: iceRestart) }
    func acceptOffer(_ sdp: String, send: AudioProfile, receive: AudioProfile) async throws -> String { try await base.acceptOffer(sdp, send: send, receive: receive) }
    func acceptAnswer(_ sdp: String, send: AudioProfile) async throws { try await base.acceptAnswer(sdp, send: send) }
    func rollback() async throws { try await base.rollback() }
    func add(_ candidate: IceCandidate) async throws { try await base.add(candidate) }
    func setAudioMaxBitrate(_ bps: Int) async { await base.setAudioMaxBitrate(bps) }
    func setVideo(_ tier: VideoTier?, ceiling: DataMode) async { await base.setVideo(tier, ceiling: ceiling) }
    func setMuted(_ on: Bool) async { await base.setMuted(on) }
    func setNoiseSuppression(_ mode: NoiseSuppression) async { await base.setNoiseSuppression(mode) }
    func close() async { await base.close() }
}

@MainActor
private final class Probe {
    var released = false
    var late = 0
}

@MainActor @Suite struct RunnerLifetimeTests {
    /// The owner lets go of a runner that is still ticking. The ticker must not keep the runner alive across its
    /// stats call and then call back into an owner that is gone (a crash in a test fixture, a stale update in an app).
    @Test func tickerDoesNotCallBackAfterTheOwnerReleasedTheRunner() async {
        let probe = Probe()
        let media = HeldStatsMedia()
        let signaling = FakeCallSignaling()
        var runner: CallRunner? = CallRunner(
            context: CallContext(server: server, settings: UserSettings(ipHiding: .nonContacts), network: .wifi, isContact: true),
            callID: "c1", contactID: "bob", signaling: signaling, sealer: PlainCallSignalSealer(), clock: ManualClock(),
            makeMedia: { media }, tickInterval: .milliseconds(5), statsEveryTicks: 1,
            publish: { _ in if probe.released { probe.late += 1 } },
            record: { _, _, _, _ in if probe.released { probe.late += 1 } },
            onStats: { _ in if probe.released { probe.late += 1 } })
        runner!.handle(.start(contact: bob, video: false, callID: "c1"))
        await runner!.settled()
        for _ in 0..<200 where !media.statsStarted { try? await Task.sleep(for: .milliseconds(10)) }
        #expect(media.statsStarted)   // the ticker is parked inside stats()

        probe.released = true
        runner = nil
        media.release()
        try? await Task.sleep(for: .milliseconds(100))
        #expect(probe.late == 0)
    }
}
