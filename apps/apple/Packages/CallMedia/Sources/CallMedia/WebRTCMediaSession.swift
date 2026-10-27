import Foundation
import AppCore
import CallCore
import WebRTC

public enum MediaSessionError: Error, Equatable {
    case notConfigured, alreadyConfigured, noDescription
}

/// `MediaSession` over WebRTC (Unified Plan, one BUNDLE, Opus audio, optional H.264 video).
/// Written against the M156 Objective-C API; see the M1 report for what is verified.
public final class WebRTCMediaSession: MediaSession, @unchecked Sendable {
    public let events: AsyncStream<MediaEvent>
    private let continuation: AsyncStream<MediaEvent>.Continuation
    private let lock = NSLock()
    private let delegate = Delegate()

    private var pc: RTCPeerConnection?
    private var audioSource: RTCAudioSource?
    private var audioTrack: RTCAudioTrack?
    private var audioSender: RTCRtpSender?
    private var videoSource: RTCVideoSource?
    private var videoTrack: RTCVideoTrack?
    private var videoSender: RTCRtpSender?
    private var capturer: RTCCameraVideoCapturer?
    private var sendProfile: AudioProfile?
    private var statsAggregator = MediaStatsAggregator()
    private var closed = false

    /// Task M3 sets this to apply the noise suppression mode to the audio processing module.
    public var noiseSuppressionHandler: (@Sendable (NoiseSuppression) -> Void)?
    private let prewarm: Bool

    public init(prewarm: Bool = false) {
        self.prewarm = prewarm
        (events, continuation) = AsyncStream.makeStream(of: MediaEvent.self)
        delegate.owner = self
    }

    // MARK: MediaSession

    public func configure(iceServers: [IceServer], policy: IceTransportPolicy, video: Bool) async throws {
        if lock.withLock({ pc != nil }) { throw MediaSessionError.alreadyConfigured }
        let factory = MediaFactory.shared()

        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.iceTransportPolicy = policy == .relay ? .relay : .all
        config.iceCandidatePoolSize = MediaLogic.candidatePoolSize(prewarm: prewarm)
        config.iceServers = iceServers.map {
            RTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential)
        }

        guard let pc = factory.peerConnection(with: config, constraints: Self.emptyConstraints, delegate: delegate) else {
            throw MediaSessionError.notConfigured
        }

        let aSource = factory.audioSource(with: Self.emptyConstraints)
        let aTrack = factory.audioTrack(with: aSource, trackId: "audio0")
        let aInit = RTCRtpTransceiverInit()
        aInit.direction = .sendRecv
        aInit.streamIds = ["call"]
        let aTransceiver = pc.addTransceiver(with: aTrack, init: aInit)

        var vSource: RTCVideoSource?, vTrack: RTCVideoTrack?, vSender: RTCRtpSender?, cap: RTCCameraVideoCapturer?
        if video {
            let source = factory.videoSource()
            let track = factory.videoTrack(with: source, trackId: "video0")
            track.isEnabled = false   // off until setVideo(tier)
            let vInit = RTCRtpTransceiverInit()
            vInit.direction = .sendRecv
            vInit.streamIds = ["call"]
            vSender = pc.addTransceiver(with: track, init: vInit)?.sender
            vSource = source; vTrack = track
            cap = RTCCameraVideoCapturer(delegate: source)
        }

        lock.withLock {
            self.pc = pc
            audioSource = aSource; audioTrack = aTrack; audioSender = aTransceiver?.sender
            videoSource = vSource; videoTrack = vTrack; videoSender = vSender; capturer = cap
        }
    }

    public func makeOffer(receive: AudioProfile, iceRestart: Bool) async throws -> String {
        let pc = try requirePC()
        let constraints = iceRestart
            ? RTCMediaConstraints(mandatoryConstraints: ["IceRestart": "true"], optionalConstraints: nil)
            : Self.emptyConstraints
        let offer = try await Self.offer(pc, constraints)
        let munged = SDPMunger.apply(receive, to: offer.sdp)
        try await Self.setLocal(pc, RTCSessionDescription(type: .offer, sdp: munged))
        return munged
    }

    public func acceptOffer(_ sdp: String, send: AudioProfile, receive: AudioProfile) async throws -> String {
        let pc = try requirePC()
        // The remote offer's Opus parameters describe what we send; ours go in the answer.
        try await Self.setRemote(pc, RTCSessionDescription(type: .offer, sdp: SDPMunger.apply(send, to: sdp)))
        lock.withLock { sendProfile = send }
        let answer = try await Self.answer(pc, Self.emptyConstraints)
        let munged = SDPMunger.apply(receive, to: answer.sdp)
        try await Self.setLocal(pc, RTCSessionDescription(type: .answer, sdp: munged))
        reportRemoteVideo(pc)
        return munged
    }

    public func acceptAnswer(_ sdp: String, send: AudioProfile) async throws {
        let pc = try requirePC()
        try await Self.setRemote(pc, RTCSessionDescription(type: .answer, sdp: SDPMunger.apply(send, to: sdp)))
        lock.withLock { sendProfile = send }
        reportRemoteVideo(pc)
    }

    public func rollback() async throws {
        let pc = try requirePC()
        try await Self.setLocal(pc, RTCSessionDescription(type: .rollback, sdp: ""))
    }

    public func add(_ candidate: IceCandidate) async throws {
        let pc = try requirePC()
        let c = RTCIceCandidate(sdp: candidate.sdp, sdpMLineIndex: candidate.sdpMLineIndex, sdpMid: candidate.sdpMid)
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            pc.add(c) { error in
                if let error { k.resume(throwing: error) } else { k.resume() }
            }
        }
    }

    public func setAudioMaxBitrate(_ bps: Int) async {
        let (sender, profile) = lock.withLock { (audioSender, sendProfile) }
        guard let sender else { return }
        let cap = profile.map { MediaLogic.senderBitrateCap(requested: bps, profile: $0) } ?? bps
        let params = sender.parameters
        for e in params.encodings { e.maxBitrateBps = NSNumber(value: cap) }
        sender.parameters = params
    }

    public func setVideo(_ tier: VideoTier?) async {
        let (track, sender, capturer) = lock.withLock { (videoTrack, videoSender, self.capturer) }
        guard let track, let sender, let capturer else { return }
        guard let tier else {
            track.isEnabled = false
            await withCheckedContinuation { (k: CheckedContinuation<Void, Never>) in
                capturer.stopCapture { k.resume() }
            }
            return
        }
        let p = MediaLogic.videoParameters(tier)
        let params = sender.parameters
        for e in params.encodings { e.maxBitrateBps = NSNumber(value: p.maxBitrate); e.maxFramerate = NSNumber(value: p.fps) }
        sender.parameters = params
        track.isEnabled = true
        guard let device = Self.cameraDevice(),
              let format = Self.closestFormat(device, height: p.height) else { return }
        try? await capturer.startCapture(with: device, format: format, fps: p.fps)
    }

    public func setMuted(_ on: Bool) async {
        lock.withLock { audioTrack }?.isEnabled = !on
    }

    public func setNoiseSuppression(_ mode: NoiseSuppression) async {
        noiseSuppressionHandler?(mode)
    }

    public func stats() async -> MediaStats {
        guard let pc = lock.withLock({ pc }) else { return MediaStats() }
        let raw: [RawStat] = await withCheckedContinuation { k in
            pc.statistics { report in
                k.resume(returning: report.statistics.values.map(Self.flatten))
            }
        }
        return lock.withLock { statsAggregator.aggregate(raw) }
    }

    public func close() async {
        let (pc, cap) = lock.withLock { () -> (RTCPeerConnection?, RTCCameraVideoCapturer?) in
            if closed { return (nil, nil) }
            closed = true
            let r = (self.pc, capturer)
            self.pc = nil; capturer = nil
            audioTrack = nil; audioSender = nil; audioSource = nil
            videoTrack = nil; videoSender = nil; videoSource = nil
            return r
        }
        if let cap { await withCheckedContinuation { (k: CheckedContinuation<Void, Never>) in cap.stopCapture { k.resume() } } }
        pc?.close()
        continuation.finish()
    }

    // MARK: Internals

    private static var emptyConstraints: RTCMediaConstraints {
        RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
    }

    private func requirePC() throws -> RTCPeerConnection {
        guard let pc = lock.withLock({ pc }) else { throw MediaSessionError.notConfigured }
        return pc
    }

    private func reportRemoteVideo(_ pc: RTCPeerConnection) {
        let has = pc.transceivers.contains {
            $0.mediaType == .video && ($0.currentDirection == .sendRecv || $0.currentDirection == .recvOnly)
        }
        if has { continuation.yield(.remoteVideo(true)) }
    }

    private static func flatten(_ s: RTCStatistics) -> RawStat {
        var nums: [String: Double] = [:], strs: [String: String] = [:]
        for (k, v) in s.values {
            if let n = v as? NSNumber { nums[k] = n.doubleValue }
            else if let t = v as? String { strs[k] = t }
        }
        // `nominated` is a bool: NSNumber, handled above.
        return RawStat(id: s.id, type: s.type, numbers: nums, strings: strs)
    }

    private static func cameraDevice() -> AVCaptureDevice? {
        let devices = RTCCameraVideoCapturer.captureDevices()
        return devices.first { $0.position == .front } ?? devices.first
    }

    private static func closestFormat(_ device: AVCaptureDevice, height: Int) -> AVCaptureDevice.Format? {
        RTCCameraVideoCapturer.supportedFormats(for: device).min {
            abs(Int(CMVideoFormatDescriptionGetDimensions($0.formatDescription).height) - height)
                < abs(Int(CMVideoFormatDescriptionGetDimensions($1.formatDescription).height) - height)
        }
    }

    private static func offer(_ pc: RTCPeerConnection, _ c: RTCMediaConstraints) async throws -> RTCSessionDescription {
        try await withCheckedThrowingContinuation { k in
            pc.offer(for: c) { sdp, error in
                if let sdp { k.resume(returning: sdp) } else { k.resume(throwing: error ?? MediaSessionError.noDescription) }
            }
        }
    }
    private static func answer(_ pc: RTCPeerConnection, _ c: RTCMediaConstraints) async throws -> RTCSessionDescription {
        try await withCheckedThrowingContinuation { k in
            pc.answer(for: c) { sdp, error in
                if let sdp { k.resume(returning: sdp) } else { k.resume(throwing: error ?? MediaSessionError.noDescription) }
            }
        }
    }
    private static func setLocal(_ pc: RTCPeerConnection, _ d: RTCSessionDescription) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            pc.setLocalDescription(d) { error in if let error { k.resume(throwing: error) } else { k.resume() } }
        }
    }
    private static func setRemote(_ pc: RTCPeerConnection, _ d: RTCSessionDescription) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            pc.setRemoteDescription(d) { error in if let error { k.resume(throwing: error) } else { k.resume() } }
        }
    }

    // MARK: Delegate

    private final class Delegate: NSObject, RTCPeerConnectionDelegate, @unchecked Sendable {
        weak var owner: WebRTCMediaSession?

        func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
        func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
        func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
        func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
        func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
        func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
        func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

        func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
            if let s = MediaLogic.iceState(fromRTCRaw: newState.rawValue) { owner?.continuation.yield(.iceState(s)) }
        }
        func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
            owner?.continuation.yield(.localCandidate(
                IceCandidate(sdp: candidate.sdp, sdpMid: candidate.sdpMid, sdpMLineIndex: candidate.sdpMLineIndex)))
        }
        func peerConnection(_ pc: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
            if transceiver.mediaType == .video { owner?.continuation.yield(.remoteVideo(true)) }
        }
    }
}
