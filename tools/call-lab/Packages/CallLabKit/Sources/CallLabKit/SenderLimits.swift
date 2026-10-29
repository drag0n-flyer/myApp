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
    /// Bandwidth estimator ceiling (`setBweMinBitrateBps(nil, nil, max)`), as the apps set it (CallCore
    /// `MediaLogic.bweMaxBitrate`): the wire budget of the lowest app mode whose Opus target covers the Opus rate,
    /// plus the video cap. Keeps WebRTC's loss-driven padding and probes within the mode's budget.
    public var bweMaxBitrateBps: Int

    /// The apps' `AudioProfile` table: Opus target to wire budget, lowest mode first.
    static let wireBudgets: [(opus: Int, wire: Int)] = [(10_000, 21_000), (16_000, 30_000), (24_000, 51_000), (40_000, 75_000)]

    public init(_ k: LabKnobs) {
        audioMaxBitrateBps = k.senderMaxBitrate
        videoMaxBitrateBps = k.video?.maxBitrate
        let opus = min(k.opusBitrate, k.senderMaxBitrate ?? k.opusBitrate)
        let audio = Self.wireBudgets.first { $0.opus >= opus } ?? Self.wireBudgets[Self.wireBudgets.count - 1]
        bweMaxBitrateBps = audio.wire + (k.video?.maxBitrate ?? 0)
        capture = k.video.map { CaptureFormat(width: $0.width, height: $0.height, fps: $0.fps) }
    }
}
