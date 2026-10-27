import Foundation

public enum VideoRung: String, Codable, CaseIterable, Sendable {
    case p180_15, p270_15, p360_20

    public var width: Int {
        switch self { case .p180_15: 320; case .p270_15: 480; case .p360_20: 640 }
    }
    public var height: Int {
        switch self { case .p180_15: 180; case .p270_15: 270; case .p360_20: 360 }
    }
    public var fps: Int {
        switch self { case .p180_15, .p270_15: 15; case .p360_20: 20 }
    }
    /// Maximum video bitrate in bit/s.
    public var maxBitrate: Int {
        switch self { case .p180_15: 100_000; case .p270_15: 175_000; case .p360_20: 300_000 }
    }
}

public enum PathPolicy: String, Codable, Sendable { case direct, relay }

public struct LabKnobs: Codable, Equatable, Sendable {
    public var opusBitrate: Int
    public var ptime: Int
    public var dtx: Bool
    public var fec: Bool
    public var maxPlaybackRate: Int
    public var twcc: Bool
    public var senderMaxBitrate: Int?
    public var video: VideoRung?
    public var path: PathPolicy
    public var iceRestartAfter: Int?   // seconds; nil = off

    public init(opusBitrate: Int, ptime: Int, dtx: Bool, fec: Bool, maxPlaybackRate: Int, twcc: Bool = true,
                senderMaxBitrate: Int? = nil, video: VideoRung? = nil, path: PathPolicy = .direct,
                iceRestartAfter: Int? = nil) {
        self.opusBitrate = opusBitrate; self.ptime = ptime; self.dtx = dtx; self.fec = fec
        self.maxPlaybackRate = maxPlaybackRate; self.twcc = twcc; self.senderMaxBitrate = senderMaxBitrate
        self.video = video; self.path = path; self.iceRestartAfter = iceRestartAfter
    }
}
