import Foundation
import CallCore
import WebRTC

/// `StatsSource` over an `RTCPeerConnection`. Flattens `RTCStatisticsReport` into `RawStat`;
/// all picking and aggregation lives in CallCore (`MediaStatsAggregator`, `StatsSampler`).
/// UNVERIFIED against WebRTC M156 (not compiled here).
struct StatsReader: StatsSource, @unchecked Sendable {
    let pc: RTCPeerConnection

    func rawStats() async -> [RawStat] {
        await withCheckedContinuation { (k: CheckedContinuation<[RawStat], Never>) in
            pc.statistics { report in
                k.resume(returning: report.statistics.values.map(Self.flatten))
            }
        }
    }

    static func flatten(_ s: RTCStatistics) -> RawStat {
        var nums: [String: Double] = [:], strs: [String: String] = [:]
        for (k, v) in s.values {
            if let n = v as? NSNumber { nums[k] = n.doubleValue }   // bools (nominated) arrive as NSNumber too
            else if let t = v as? String { strs[k] = t }
        }
        return RawStat(id: s.id, type: s.type, numbers: nums, strings: strs)
    }
}
