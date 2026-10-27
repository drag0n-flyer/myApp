import Foundation

public enum LabStatValue: Equatable, Sendable {
    case number(Double), string(String), bool(Bool)
}

/// One entry of a WebRTC stats report, flattened from `RTCStatistics`.
public struct LabStat: Equatable, Sendable {
    public var type: String
    public var id: String
    public var values: [String: LabStatValue]
    public init(type: String, id: String, values: [String: LabStatValue]) {
        self.type = type; self.id = id; self.values = values
    }
    func num(_ k: String) -> Double? { if case let .number(n)? = values[k] { n } else { nil } }
    func int(_ k: String) -> Int? { num(k).map { Int($0) } }
    func str(_ k: String) -> String? { if case let .string(s)? = values[k] { s } else { nil } }
    func flag(_ k: String) -> Bool? { if case let .bool(b)? = values[k] { b } else { nil } }
    var kind: String? { str("kind") ?? str("mediaType") }
}

public enum StatsMapper {
    public static func row(from stats: [LabStat], tMs: Int, runID: String, preset: String, knobs k: LabKnobs) -> StatsRow {
        var r = StatsRow(tMs: tMs, runId: runID)
        r.preset = preset; r.opusBps = k.opusBitrate; r.ptime = k.ptime; r.dtx = k.dtx; r.fec = k.fec
        r.playbackHz = k.maxPlaybackRate; r.twcc = k.twcc; r.videoRung = k.video?.rawValue; r.path = k.path.rawValue

        let byID = Dictionary(stats.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let selectedID = stats.first { $0.type == "transport" }?.str("selectedCandidatePairId")
        let pair = selectedID.flatMap { byID[$0] }
            ?? stats.first { $0.type == "candidate-pair" && $0.flag("nominated") == true && $0.str("state") == "succeeded" }
        if let p = pair {
            r.rttMs = p.num("currentRoundTripTime").map { $0 * 1000 }
            r.pairBytesSent = p.int("bytesSent"); r.pairBytesRecv = p.int("bytesReceived")
            r.availOutBps = p.int("availableOutgoingBitrate")
            r.localCand = p.str("localCandidateId").flatMap { byID[$0] }?.str("candidateType")
            r.remoteCand = p.str("remoteCandidateId").flatMap { byID[$0] }?.str("candidateType")
        }
        for s in stats {
            switch (s.type, s.kind) {
            case ("outbound-rtp", "audio"):
                r.aBytesSent = s.int("bytesSent"); r.aHdrBytesSent = s.int("headerBytesSent"); r.aPktsSent = s.int("packetsSent")
            case ("outbound-rtp", "video"):
                r.vBytesSent = s.int("bytesSent"); r.vFrameW = s.int("frameWidth"); r.vFpsSent = s.num("framesPerSecond")
            case ("inbound-rtp", "audio"):
                r.aBytesRecv = s.int("bytesReceived"); r.aHdrBytesRecv = s.int("headerBytesReceived")
                r.aPktsRecv = s.int("packetsReceived"); r.aPktsLost = s.int("packetsLost")
                r.aJitterMs = s.num("jitter").map { $0 * 1000 }
                r.concealedSamples = s.int("concealedSamples"); r.totalSamplesRecv = s.int("totalSamplesReceived")
                r.concealmentEvents = s.int("concealmentEvents")
            case ("inbound-rtp", "video"):
                r.vBytesRecv = s.int("bytesReceived")
            default: break
            }
        }
        return r
    }

    /// True once an inbound audio stream has received a packet (the `first_audio_packet` event).
    public static func hasInboundAudio(_ stats: [LabStat]) -> Bool {
        stats.contains { $0.type == "inbound-rtp" && $0.kind == "audio" && ($0.int("packetsReceived") ?? 0) > 0 }
    }
}
