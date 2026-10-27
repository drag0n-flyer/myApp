import Foundation

/// WebRTC-free logic used by CallMedia's `WebRTCMediaSession`, kept here so it runs in Linux tests.
public enum MediaLogic {
    /// Maps `RTCIceConnectionState.rawValue` (new0 checking1 connected2 completed3 failed4 disconnected5 closed6).
    public static func iceState(fromRTCRaw raw: Int) -> IceState? {
        switch raw {
        case 0: return .new
        case 1: return .checking
        case 2: return .connected
        case 3: return .completed
        case 4: return .failed
        case 5: return .disconnected
        case 6: return .closed
        default: return nil
        }
    }

    /// `iceCandidatePoolSize` is 1 only when prewarming.
    public static func candidatePoolSize(prewarm: Bool) -> Int32 { prewarm ? 1 : 0 }

    /// A relay-only call needs at least one TURN server to gather anything.
    public static func hasTurn(_ servers: [IceServer]) -> Bool {
        servers.contains { $0.urls.contains { $0.lowercased().hasPrefix("turn:") || $0.lowercased().hasPrefix("turns:") } }
    }

    /// Opus bitrate cap for the RTP sender: at most the profile's `maxAverageBitrate`.
    public static func senderBitrateCap(requested bps: Int, profile: AudioProfile) -> Int {
        max(0, min(bps, profile.maxAverageBitrate))
    }

    /// Video tier -> (height, max bitrate bps, max fps), from `VideoLadder` (the plan's 100k/200k/300k caps).
    public static func videoParameters(_ tier: VideoTier) -> (height: Int, maxBitrate: Int, fps: Int) {
        let s = VideoLadder.spec(tier)
        return (s.height, s.maxBps, s.fps)
    }
}

/// One entry of an `RTCStatisticsReport`, flattened to numbers and strings.
public struct RawStat: Sendable, Equatable {
    public var id: String, type: String
    public var numbers: [String: Double]
    public var strings: [String: String]
    public init(id: String, type: String, numbers: [String: Double] = [:], strings: [String: String] = [:]) {
        self.id = id; self.type = type; self.numbers = numbers; self.strings = strings
    }
}

/// Turns raw stats entries into `MediaStats`. Keeps the previous inbound counters to give loss since the last sample.
public struct MediaStatsAggregator: Sendable {
    private var lastReceived: Double = 0, lastLost: Double = 0
    public init() {}

    public mutating func aggregate(_ stats: [RawStat]) -> MediaStats {
        var out = MediaStats()
        let byId = Dictionary(stats.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })

        // Selected pair: transport.selectedCandidatePairId, else a nominated/succeeded pair.
        var pair: RawStat?
        if let tid = stats.first(where: { $0.type == "transport" })?.strings["selectedCandidatePairId"] { pair = byId[tid] }
        if pair == nil {
            pair = stats.first { $0.type == "candidate-pair" && ($0.numbers["nominated"] ?? 0) == 1 && $0.strings["state"] == "succeeded" }
        }
        if let p = pair {
            out.availableOutgoingBitrate = p.numbers["availableOutgoingBitrate"]
            out.roundTripTime = p.numbers["currentRoundTripTime"]
            if let lid = p.strings["localCandidateId"], let l = byId[lid] { out.usingRelay = l.strings["candidateType"] == "relay" }
        }

        var received = 0.0, lost = 0.0
        for s in stats {
            switch (s.type, s.strings["kind"] ?? s.strings["mediaType"]) {
            case ("inbound-rtp", "audio"):
                received += s.numbers["packetsReceived"] ?? 0
                lost += s.numbers["packetsLost"] ?? 0
                out.jitter = s.numbers["jitter"]
                out.packetsReceived += Int64(s.numbers["packetsReceived"] ?? 0)
                out.bytesReceived += Int64(s.numbers["bytesReceived"] ?? 0)
                if let c = s.strings["codecId"], let codec = byId[c]?.strings["mimeType"] { out.audioCodec = codec }
            case ("outbound-rtp", "audio"):
                out.bytesSent += Int64(s.numbers["bytesSent"] ?? 0)
                out.packetsSent += Int64(s.numbers["packetsSent"] ?? 0)
                if out.audioCodec == nil, let c = s.strings["codecId"], let codec = byId[c]?.strings["mimeType"] { out.audioCodec = codec }
            default: break
            }
        }
        let dRecv = received - lastReceived, dLost = max(0, lost - lastLost)
        if dRecv >= 0, dRecv + dLost > 0 { out.inboundLossFraction = dLost / (dRecv + dLost) }
        lastReceived = received; lastLost = lost
        return out
    }
}

/// Decides when the peer's video starts or stops, from the inbound video frame counter sampled about once a second.
/// A sender that turns video off sends no frames, so the counter stops growing.
public struct RemoteVideoDetector: Sendable {
    public private(set) var active = false
    private var last: Double = 0, stalls = 0
    private let stallLimit: Int
    public init(stallLimit: Int = 2) { self.stallLimit = stallLimit }

    /// Returns the new state when it changed, nil otherwise.
    public mutating func sample(frames: Double?) -> Bool? {
        if let f = frames, f > last {
            last = f; stalls = 0
            if !active { active = true; return true }
            return nil
        }
        if let f = frames, f < last { last = f }   // counter reset after a restart
        stalls += 1
        if active, stalls >= stallLimit { active = false; return false }
        return nil
    }
}
