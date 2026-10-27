import AppCore

public enum VideoLadder {
    public struct Spec: Sendable, Equatable {
        public let width: Int, height: Int, fps: Int, minBps: Int, maxBps: Int
    }

    public static func spec(_ tier: VideoTier) -> Spec {
        switch tier {
        case .p360: Spec(width: 640, height: 360, fps: 20, minBps: 250_000, maxBps: 300_000)
        case .p270: Spec(width: 480, height: 270, fps: 15, minBps: 140_000, maxBps: 200_000)
        case .p180: Spec(width: 320, height: 180, fps: 15, minBps: 40_000, maxBps: 100_000)
        }
    }

    /// The highest tier the ceiling allows. `p180` is used at `ultraLow` and `dataSaver`, only if the user turned video on.
    public static func allowed(ceiling: DataMode) -> VideoTier? {
        switch ceiling {
        case .high: .p360
        case .balanced: .p270
        case .dataSaver, .ultraLow: .p180
        }
    }

    public static func startsOff(ceiling: DataMode, network: NetworkType, settings: CallSettings) -> Bool {
        ceiling <= .dataSaver || (network == .mobile && settings.videoOffOnMobile)
    }

    /// Highest tier (at most `maxTier`) whose minimum fits `budget`, with the sender capped at `min(budget, max)`.
    public static func pick(budget: Double, maxTier: VideoTier?) -> (tier: VideoTier, maxBitrate: Int)? {
        guard let maxTier else { return nil }
        for tier in VideoTier.allCases.reversed() where tier <= maxTier {
            let s = spec(tier)
            if Double(s.minBps) <= budget { return (tier, Int(min(budget, Double(s.maxBps)))) }
        }
        return nil
    }
}
