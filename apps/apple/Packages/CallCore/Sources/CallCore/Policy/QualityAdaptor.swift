import AppCore

public struct AdaptorOutput: Equatable, Sendable {
    public var mode: DataMode
    public var video: VideoTier?
    public var videoBitrate: Int?
    public var quality: CallQuality
    public var banner: CallBanner?
}

/// Picks the audio mode and video tier a network can sustain, from a `MediaStats` sample every 2 s.
/// Degrades video tier, then video off, then audio. Recovers one level after several good samples.
public struct QualityAdaptor: Sendable {
    public static let stepDownMargin = 0.85
    public static let stepUpMargin = 1.3
    public static let lossDown = 0.10
    public static let lossUpMax = 0.02
    public static let goodSamplesToStepUp = 5
    public static let audioOnlyBelow = 60_000.0

    private struct Level: Equatable { var mode: DataMode; var tier: VideoTier? }

    private var ceiling: DataMode
    private var videoWanted: Bool
    private var current: Level
    private var badLossRun = 0
    private var goodRun = 0
    private var lastAvail: Double?

    public init(ceiling: DataMode, videoWanted: Bool) {
        self.ceiling = ceiling
        self.videoWanted = videoWanted
        self.current = Level(mode: ceiling, tier: videoWanted ? VideoLadder.allowed(ceiling: ceiling) : nil)
    }

    public mutating func setCeiling(_ c: DataMode) {
        let raised = c > ceiling
        ceiling = c
        if raised, let tier = current.tier {
            // Video levels only exist at the ceiling mode, so a running video call moves up with the ceiling.
            current = Level(mode: c, tier: tier)
        } else {
            clamp()
        }
    }

    public mutating func setVideoWanted(_ on: Bool) {
        let turnedOn = on && !videoWanted
        videoWanted = on
        clamp()
        // Jump straight to the best level the last known bandwidth supports instead of waiting for good samples.
        if turnedOn, let avail = lastAvail {
            let ladder = levels()
            if let idx = ladder.firstIndex(of: current),
               let target = ladder[...idx].first(where: { fits($0, avail) }) {
                current = target
            }
        }
        goodRun = 0
    }

    public mutating func ingest(_ s: MediaStats) -> AdaptorOutput {
        let ladder = levels()
        let idx = ladder.firstIndex(of: current) ?? (ladder.count - 1)

        badLossRun = s.inboundLossFraction > Self.lossDown ? badLossRun + 1 : 0
        let lossTrigger = badLossRun >= 2

        // No bandwidth estimate: hold. Only the loss rule applies, and goodRun is neither counted nor reset.
        guard let measured = s.availableOutgoingBitrate else {
            if lossTrigger {
                goodRun = 0
                badLossRun = 0
                if idx < ladder.count - 1 { current = ladder[idx + 1] }
            }
            return output(avail: lastAvail ?? .infinity)
        }
        let avail = measured
        lastAvail = measured
        let bandwidthBad = avail < Double(need(ladder[idx])) * Self.stepDownMargin
            || (current.tier != nil && avail < Self.audioOnlyBelow)

        if bandwidthBad || lossTrigger {
            goodRun = 0
            if idx < ladder.count - 1 {
                if bandwidthBad {
                    let target = ladder[(idx + 1)...].first { fits($0, avail) } ?? ladder[ladder.count - 1]
                    current = target
                } else {
                    current = ladder[idx + 1]
                }
            }
            if lossTrigger { badLossRun = 0 }
        } else if idx > 0 {
            let next = ladder[idx - 1]
            let good = s.inboundLossFraction < Self.lossUpMax
                && avail >= Double(need(next)) * Self.stepUpMargin
                && (next.tier == nil || avail >= Self.audioOnlyBelow)
            goodRun = good ? goodRun + 1 : 0
            if goodRun >= Self.goodSamplesToStepUp {
                current = next
                goodRun = 0
            }
        } else {
            goodRun = 0
        }
        return output(avail: avail)
    }

    // MARK: - Private

    /// Best first: video tiers at the ceiling mode, then audio only from the ceiling mode down.
    private func levels() -> [Level] {
        var out: [Level] = []
        if videoWanted {
            for tier in VideoTier.allCases.reversed() where tier <= (VideoLadder.allowed(ceiling: ceiling) ?? .p180) {
                out.append(Level(mode: ceiling, tier: tier))
            }
        }
        for mode in DataMode.allCases.reversed() where mode <= ceiling { out.append(Level(mode: mode, tier: nil)) }
        return out
    }

    private func need(_ l: Level) -> Int {
        AudioProfile.for(l.mode).wireBudget + (l.tier.map { VideoLadder.spec($0).minBps } ?? 0)
    }

    private func fits(_ l: Level, _ avail: Double) -> Bool {
        // Stricter than the step-down trigger (need x 0.85 vs avail x 0.85) on purpose: a hold gap that stops flapping.
        if l.tier != nil && avail < Self.audioOnlyBelow { return false }
        return Double(need(l)) <= avail * Self.stepDownMargin
    }

    /// Keeps the current level where possible, but never above the ceiling or an unwanted video tier.
    private mutating func clamp() {
        let ladder = levels()
        let curTier = current.tier.map { $0.rawValue } ?? -1
        current = ladder.first { l in
            l.mode <= current.mode && (l.tier.map { $0.rawValue } ?? -1) <= curTier
        } ?? ladder[ladder.count - 1]
    }

    private func output(avail: Double) -> AdaptorOutput {
        var bitrate: Int?
        var banner: CallBanner?
        if let tier = current.tier {
            let spec = VideoLadder.spec(tier)
            let budget = avail - Double(AudioProfile.for(current.mode).wireBudget)
            bitrate = Int(min(max(budget, Double(spec.minBps)), Double(spec.maxBps)))
            if tier < (VideoLadder.allowed(ceiling: ceiling) ?? tier) { banner = .videoLowered }
        } else if videoWanted {
            banner = .audioOnly
        }
        return AdaptorOutput(mode: current.mode, video: current.tier, videoBitrate: bitrate,
                             quality: current.mode < ceiling ? .weak : .good, banner: banner)
    }
}
