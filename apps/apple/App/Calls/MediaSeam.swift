import Foundation
import AppCore
import CallCore

// LANE M SEAM. Everything the call engine needs from media goes through CallCore's `MediaSession`.
// Lane M (WebRTC build, Opus profiles, RNNoise) replaces `LiveMedia.make` with its `MediaSession` and flips
// `MediaCapabilities.enhancedNoiseSuppression` once `RTCAudioCapturePostProcessing` exists. Nothing else changes.

enum MediaCapabilities {
    /// False until plan 3 patches in the capture post-processing hook; `enhanced` then behaves like `standard`.
    static let enhancedNoiseSuppression = false
}

enum LiveMedia {
    @MainActor static func make() -> MediaSession { UnavailableMediaSession() }
}

/// Stands in until lane M lands: configuring it fails, so a call ends as failed instead of connecting with no audio.
final class UnavailableMediaSession: MediaSession, @unchecked Sendable {
    struct Unavailable: Error {}
    let events: AsyncStream<MediaEvent>
    private let continuation: AsyncStream<MediaEvent>.Continuation

    init() { (events, continuation) = AsyncStream.makeStream(of: MediaEvent.self) }

    func configure(iceServers: [IceServer], policy: IceTransportPolicy, video: Bool) async throws { throw Unavailable() }
    func makeOffer(receive: AudioProfile, iceRestart: Bool) async throws -> String { throw Unavailable() }
    func acceptOffer(_ sdp: String, send: AudioProfile, receive: AudioProfile) async throws -> String { throw Unavailable() }
    func acceptAnswer(_ sdp: String, send: AudioProfile) async throws { throw Unavailable() }
    func rollback() async throws {}
    func add(_ candidate: IceCandidate) async throws {}
    func setAudioMaxBitrate(_ bps: Int) async {}
    func setVideo(_ tier: VideoTier?) async {}
    func setMuted(_ on: Bool) async {}
    func setNoiseSuppression(_ mode: NoiseSuppression) async {}
    func stats() async -> MediaStats { MediaStats() }
    func close() async { continuation.finish() }
}
