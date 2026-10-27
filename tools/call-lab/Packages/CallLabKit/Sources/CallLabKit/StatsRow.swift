import Foundation

public struct StatsRow: Sendable {
    public var tMs: Int
    public var runId: String
    public var preset: String?
    public var opusBps: Int?
    public var ptime: Int?
    public var dtx: Bool?
    public var fec: Bool?
    public var playbackHz: Int?
    public var twcc: Bool?
    public var videoRung: String?
    public var path: String?
    public var localCand: String?
    public var remoteCand: String?
    public var rttMs: Double?
    public var aBytesSent: Int?
    public var aHdrBytesSent: Int?
    public var aPktsSent: Int?
    public var aBytesRecv: Int?
    public var aHdrBytesRecv: Int?
    public var aPktsRecv: Int?
    public var aPktsLost: Int?
    public var aJitterMs: Double?
    public var concealedSamples: Int?
    public var totalSamplesRecv: Int?
    public var concealmentEvents: Int?
    public var vBytesSent: Int?
    public var vBytesRecv: Int?
    public var vFrameW: Int?
    public var vFpsSent: Double?
    public var pairBytesSent: Int?
    public var pairBytesRecv: Int?
    public var availOutBps: Int?
    public var thermal: Int?      // 0...3
    public var battery: Double?   // 0.00...1.00, nil on Mac

    public init(tMs: Int, runId: String) {
        self.tMs = tMs
        self.runId = runId
    }

    public static let csvHeader =
        "t_ms,run_id,preset,opus_bps,ptime,dtx,fec,playback_hz,twcc,video_rung,path,local_cand,remote_cand,rtt_ms,"
        + "a_bytes_sent,a_hdr_bytes_sent,a_pkts_sent,a_bytes_recv,a_hdr_bytes_recv,a_pkts_recv,a_pkts_lost,a_jitter_ms,"
        + "concealed_samples,total_samples_recv,concealment_events,v_bytes_sent,v_bytes_recv,v_frame_w,v_fps_sent,"
        + "pair_bytes_sent,pair_bytes_recv,avail_out_bps,thermal,battery"

    public var csvLine: String {
        func i(_ v: Int?) -> String { v.map(String.init) ?? "" }
        func b(_ v: Bool?) -> String { v.map { $0 ? "1" : "0" } ?? "" }
        func d(_ v: Double?, _ places: Int) -> String { v.map { String(format: "%.\(places)f", $0) } ?? "" }
        func s(_ v: String?) -> String {
            guard let v else { return "" }
            if v.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) {
                return "\"" + v.replacingOccurrences(of: "\"", with: "\"\"") + "\""
            }
            return v
        }
        let fields: [String] = [
            String(tMs), s(runId), s(preset), i(opusBps), i(ptime), b(dtx), b(fec), i(playbackHz), b(twcc),
            s(videoRung), s(path), s(localCand), s(remoteCand), d(rttMs, 1),
            i(aBytesSent), i(aHdrBytesSent), i(aPktsSent), i(aBytesRecv), i(aHdrBytesRecv), i(aPktsRecv),
            i(aPktsLost), d(aJitterMs, 1),
            i(concealedSamples), i(totalSamplesRecv), i(concealmentEvents), i(vBytesSent), i(vBytesRecv),
            i(vFrameW), d(vFpsSent, 1),
            i(pairBytesSent), i(pairBytesRecv), i(availOutBps), i(thermal), d(battery, 2),
        ]
        return fields.joined(separator: ",")
    }
}
