import Foundation
import AppCore

public struct AudioProfile: Sendable, Equatable {
    public var mode: DataMode
    public var maxAverageBitrate: Int, ptime: Int, maxPlaybackRate: Int, wireBudget: Int
    public var inbandFEC: Bool, dtx: Bool
    public init(mode: DataMode, maxAverageBitrate: Int, ptime: Int, maxPlaybackRate: Int, wireBudget: Int,
                inbandFEC: Bool, dtx: Bool) {
        self.mode = mode; self.maxAverageBitrate = maxAverageBitrate; self.ptime = ptime
        self.maxPlaybackRate = maxPlaybackRate; self.wireBudget = wireBudget
        self.inbandFEC = inbandFEC; self.dtx = dtx
    }
}

public enum MediaEvent: Sendable, Equatable {
    case localCandidate(IceCandidate)
    case iceState(IceState)
    case remoteVideo(Bool)
}

public protocol MediaSession: AnyObject, Sendable {
    var events: AsyncStream<MediaEvent> { get }
    func configure(iceServers: [IceServer], policy: IceTransportPolicy, video: Bool) async throws
    func makeOffer(receive: AudioProfile, iceRestart: Bool) async throws -> String
    func acceptOffer(_ sdp: String, send: AudioProfile, receive: AudioProfile) async throws -> String
    func acceptAnswer(_ sdp: String, send: AudioProfile) async throws
    func rollback() async throws
    func add(_ candidate: IceCandidate) async throws
    func setAudioMaxBitrate(_ bps: Int) async
    /// `ceiling` is the call's data-mode ceiling (own setting and peer's, whichever is lower); the tier must be clamped to it.
    func setVideo(_ tier: VideoTier?, ceiling: DataMode) async
    func setMuted(_ on: Bool) async
    func setNoiseSuppression(_ mode: NoiseSuppression) async
    func stats() async -> MediaStats
    func close() async
}
