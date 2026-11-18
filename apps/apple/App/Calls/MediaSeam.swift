import AVFoundation
import Foundation
import Observation
import AppCore
import CallCore
import CallMedia

// Everything the call engine needs from media goes through CallCore's `MediaSession`;
// the live one is CallMedia's WebRTC session, wrapped to ask for camera access first.

enum MediaCapabilities {
    /// True when the linked WebRTC build has the capture post-processing hook; otherwise `enhanced` behaves like `standard`.
    static var enhancedNoiseSuppression: Bool { MediaFactory.captureHookAvailable }
}

enum LiveMedia {
    @MainActor static func make() -> MediaSession {
        let inner = WebRTCMediaSession.live()
        let session = LiveMediaSession(inner)
        CallVideo.shared.began(inner)
        return session
    }
}

/// What the call screen needs to know about the live call's video. Set only by the live media session;
/// the sample build never touches it, so the call screen shows its placeholder tiles there.
@MainActor @Observable
final class CallVideo {
    static let shared = CallVideo()

    /// The live call's session, for attaching video views. Nil when no live call exists.
    private(set) var session: WebRTCMediaSession?
    /// True once the other side's video is arriving, false when they turn it off.
    private(set) var remoteVideoOn = false
    /// The camera was refused (or is off in system settings), so the call continues as audio.
    private(set) var cameraDenied = false
    /// iPhone with a front and a back camera, during a live call.
    var canFlip: Bool { session != nil && WebRTCMediaSession.canSwitchCamera }

    func began(_ s: WebRTCMediaSession) { session = s; remoteVideoOn = false; cameraDenied = false }
    func ended(_ s: WebRTCMediaSession) {
        guard session === s else { return }
        session = nil; remoteVideoOn = false; cameraDenied = false
    }
    func remoteVideoChanged(_ on: Bool, of s: WebRTCMediaSession) { if session === s { remoteVideoOn = on } }
    func cameraAccess(denied: Bool, of s: WebRTCMediaSession) { if session === s { cameraDenied = denied } }

    func flip() {
        guard let session else { return }
        Task { await session.switchCamera() }
    }
}

enum CameraAccess {
    /// Asks the first time; later it only reports what the user chose.
    static func ensure() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }
}

/// CallMedia's session plus two app duties: the camera permission (refused means the call stays audio, never an
/// error) and telling `CallVideo` when the other side's video starts or stops.
// @unchecked Sendable: `inner` and `events` are immutable; the forwarding task is set once in `init`.
final class LiveMediaSession: MediaSession, @unchecked Sendable {
    let events: AsyncStream<MediaEvent>
    private let inner: WebRTCMediaSession
    private let continuation: AsyncStream<MediaEvent>.Continuation

    init(_ inner: WebRTCMediaSession) {
        self.inner = inner
        let (events, continuation) = AsyncStream.makeStream(of: MediaEvent.self)
        self.events = events
        self.continuation = continuation
        Task { [inner] in
            for await e in inner.events {
                if case .remoteVideo(let on) = e { await CallVideo.shared.remoteVideoChanged(on, of: inner) }
                continuation.yield(e)
            }
            continuation.finish()
        }
    }

    func configure(iceServers: [IceServer], policy: IceTransportPolicy, video: Bool) async throws {
        try await inner.configure(iceServers: iceServers, policy: policy, video: video)
    }
    func makeOffer(receive: AudioProfile, iceRestart: Bool) async throws -> String {
        try await inner.makeOffer(receive: receive, iceRestart: iceRestart)
    }
    func acceptOffer(_ sdp: String, send: AudioProfile, receive: AudioProfile) async throws -> String {
        try await inner.acceptOffer(sdp, send: send, receive: receive)
    }
    func acceptAnswer(_ sdp: String, send: AudioProfile) async throws { try await inner.acceptAnswer(sdp, send: send) }
    func rollback() async throws { try await inner.rollback() }
    func add(_ candidate: IceCandidate) async throws { try await inner.add(candidate) }
    func setAudioMaxBitrate(_ bps: Int) async { await inner.setAudioMaxBitrate(bps) }
    func setMuted(_ on: Bool) async { await inner.setMuted(on) }
    func setNoiseSuppression(_ mode: NoiseSuppression) async { await inner.setNoiseSuppression(mode) }
    func stats() async -> MediaStats { await inner.stats() }

    func setVideo(_ tier: VideoTier?, ceiling: DataMode) async {
        guard tier != nil else {
            await CallVideo.shared.cameraAccess(denied: false, of: inner)
            await inner.setVideo(nil, ceiling: ceiling)
            return
        }
        guard await CameraAccess.ensure() else {
            await CallVideo.shared.cameraAccess(denied: true, of: inner)
            await inner.setVideo(nil, ceiling: ceiling)   // the call goes on as audio
            return
        }
        await CallVideo.shared.cameraAccess(denied: false, of: inner)
        await inner.setVideo(tier, ceiling: ceiling)
    }

    func close() async {
        await inner.close()   // finishes `inner.events`, which finishes `events`
        await CallVideo.shared.ended(inner)
    }
}
