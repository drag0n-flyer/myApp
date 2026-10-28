import Foundation

public struct CaptureFormat: Equatable, Sendable {
    public var width: Int, height: Int, fps: Int
    public init(width: Int, height: Int, fps: Int) { self.width = width; self.height = height; self.fps = fps }
}

/// Knobs mapped to RTCRtpSender encoding limits and the video capturer format.
public struct SenderLimits: Equatable, Sendable {
    public var audioMaxBitrateBps: Int?
    public var videoMaxBitrateBps: Int?
    public var capture: CaptureFormat?

    public init(_ k: LabKnobs) {
        audioMaxBitrateBps = k.senderMaxBitrate
        videoMaxBitrateBps = k.video?.maxBitrate
        capture = k.video.map { CaptureFormat(width: $0.width, height: $0.height, fps: $0.fps) }
    }
}
