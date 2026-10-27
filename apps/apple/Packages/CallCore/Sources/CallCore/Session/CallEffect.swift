import Foundation
import AppCore

public enum CallEffect: Sendable, Equatable {
    case sendInvite(CallSignalPayload), sendAnswer(CallSignalPayload), sendSignal(CallSignalPayload)
    case sendDecline, sendHangup
    case configureMedia(policy: IceTransportPolicy, video: Bool)   // engine fetches iceServers first
    case makeOffer(receive: AudioProfile, iceRestart: Bool)        // engine replies with `.localOffer(sdp)`
    case acceptOffer(sdp: String, send: AudioProfile, receive: AudioProfile)  // engine replies with `.localAnswer(sdp)`
    case acceptAnswer(sdp: String, send: AudioProfile)
    case rollback, addCandidate(IceCandidate)
    case setAudioMaxBitrate(Int), setVideo(VideoTier?), setMuted(Bool), setNoiseSuppression(NoiseSuppression)
    case closeMedia
    case recordHistory(CallSummary, outgoing: Bool)
    case publish(CallState?)
}
