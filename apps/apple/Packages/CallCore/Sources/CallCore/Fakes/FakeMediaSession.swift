import Foundation
import AppCore

public final class FakeMediaSession: MediaSession, @unchecked Sendable {
    public let events: AsyncStream<MediaEvent>
    private let continuation: AsyncStream<MediaEvent>.Continuation
    private let lock = NSLock()
    private var _calls: [String] = []
    public var offerSDP = "fake-offer"
    public var answerSDP = "fake-answer"
    public var statsResult = MediaStats()

    public init() { (events, continuation) = AsyncStream.makeStream(of: MediaEvent.self) }
    public var calls: [String] { lock.lock(); defer { lock.unlock() }; return _calls }
    public func emit(_ event: MediaEvent) { continuation.yield(event) }
    private func rec(_ s: String) { lock.lock(); _calls.append(s); lock.unlock() }

    public func configure(iceServers: [IceServer], policy: IceTransportPolicy, video: Bool) async throws { rec("configure") }
    public func makeOffer(receive: AudioProfile, iceRestart: Bool) async throws -> String { rec("makeOffer"); return offerSDP }
    public func acceptOffer(_ sdp: String, send: AudioProfile, receive: AudioProfile) async throws -> String { rec("acceptOffer"); return answerSDP }
    public func acceptAnswer(_ sdp: String, send: AudioProfile) async throws { rec("acceptAnswer") }
    public func rollback() async throws { rec("rollback") }
    public func add(_ candidate: IceCandidate) async throws { rec("add") }
    public func setAudioMaxBitrate(_ bps: Int) async { rec("setAudioMaxBitrate(\(bps))") }
    public func setVideo(_ tier: VideoTier?) async { rec("setVideo(\(tier.map { "\($0)" } ?? "nil"))") }
    public func setMuted(_ on: Bool) async { rec("setMuted(\(on))") }
    public func setNoiseSuppression(_ mode: NoiseSuppression) async { rec("setNoiseSuppression(\(mode.rawValue))") }
    public func stats() async -> MediaStats { rec("stats"); return statsResult }
    public func close() async { rec("close"); continuation.finish() }
}
