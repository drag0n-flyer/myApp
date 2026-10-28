import Foundation

/// Where raw stats come from: `RTCPeerConnection` in CallMedia, a stub in tests.
public protocol StatsSource: Sendable {
    func rawStats() async -> [RawStat]
}

/// Pulls a raw report and aggregates it. Keeps the loss baseline between samples.
public actor StatsSampler {
    private let source: StatsSource
    private var aggregator = MediaStatsAggregator()
    public init(source: StatsSource) { self.source = source }

    public func sample() async -> MediaStats {
        let raw = await source.rawStats()
        return aggregator.aggregate(raw)
    }
}
