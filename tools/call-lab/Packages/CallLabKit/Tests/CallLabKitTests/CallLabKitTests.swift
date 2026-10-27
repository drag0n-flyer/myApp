import Testing
import Foundation
@testable import CallLabKit

func loadOffer() throws -> String {
    let url = try #require(Bundle.module.url(forResource: "offer", withExtension: "sdp", subdirectory: "Fixtures"))
    return try String(contentsOf: url, encoding: .utf8)
}

func sections(_ sdp: String) -> [String: [String]] {
    var out: [String: [String]] = [:]
    var cur = "session"
    for l in sdp.components(separatedBy: "\r\n") {
        if l.hasPrefix("m=") { cur = String(l.dropFirst(2).prefix { $0 != " " }) }
        out[cur, default: []].append(l)
    }
    return out
}

@Suite struct CallLabKitTests {
    @Test func presetsMatchGoalTable() {
        let u = LabPreset.ultraLow.knobs
        #expect(u.opusBitrate == 9000 && u.ptime == 60 && u.dtx && !u.fec && u.maxPlaybackRate == 16000 && u.video == nil)
        let d = LabPreset.dataSaver.knobs
        #expect(d.opusBitrate == 15000 && d.ptime == 60 && d.dtx && d.fec && d.maxPlaybackRate == 16000 && d.video == nil)
        let b = LabPreset.balanced.knobs
        #expect(b.opusBitrate == 24000 && b.ptime == 20 && b.dtx && b.fec && b.maxPlaybackRate == 48000 && b.video == .p270_15)
        let h = LabPreset.high.knobs
        #expect(h.opusBitrate == 40000 && h.ptime == 20 && h.dtx && h.fec && h.maxPlaybackRate == 48000 && h.video == .p360_20)
        for p in LabPreset.allCases { #expect(p.knobs.path == .direct && p.knobs.iceRestartAfter == nil) }
        #expect(VideoRung.p180_15.maxBitrate == 100_000 && VideoRung.p270_15.maxBitrate == 175_000 && VideoRung.p360_20.maxBitrate == 300_000)
        #expect(VideoRung.p360_20.width == 640 && VideoRung.p360_20.height == 360 && VideoRung.p360_20.fps == 20)
        #expect(VideoRung.p180_15.width == 320 && VideoRung.p270_15.height == 270)
    }

    @Test func mungeOpusFmtp() throws {
        let out = SDPMunger.apply(LabPreset.ultraLow.knobs, to: try loadOffer())
        let a = try #require(sections(out)["audio"])
        #expect(a.contains("a=fmtp:111 minptime=10;useinbandfec=0;usedtx=1;maxaveragebitrate=9000;maxplaybackrate=16000;sprop-maxcapturerate=16000"))
        #expect(a.contains("a=ptime:60"))
        #expect(a.contains("a=maxptime:120"))
        #expect(!a.contains("a=fmtp:111 minptime=10;useinbandfec=1"))
    }

    @Test func mungeInsertsFmtpWhenMissing() {
        let sdp = "v=0\r\nm=audio 9 RTP/AVP 111\r\na=rtpmap:111 opus/48000/2\r\na=sendrecv\r\n"
        let out = SDPMunger.apply(LabPreset.balanced.knobs, to: sdp)
        let lines = out.components(separatedBy: "\r\n")
        let i = lines.firstIndex(of: "a=rtpmap:111 opus/48000/2")!
        #expect(lines[i + 1].hasPrefix("a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=24000"))
        #expect(lines.contains("a=ptime:20"))
    }

    @Test func mungeTwccOff() throws {
        var k = LabPreset.balanced.knobs
        k.twcc = false
        let out = SDPMunger.apply(k, to: try loadOffer())
        let s = sections(out)
        let a = try #require(s["audio"])
        #expect(!a.contains("a=rtcp-fb:111 transport-cc"))
        #expect(!a.contains { $0.hasPrefix("a=extmap") && $0.contains("transport-wide-cc-extensions") })
        let v = try #require(s["video"])
        #expect(v.contains("a=rtcp-fb:96 transport-cc"))
        #expect(v.contains { $0.hasPrefix("a=extmap") && $0.contains("transport-wide-cc-extensions") })
    }

    @Test func twccOnKeepsAudioTwcc() throws {
        let out = SDPMunger.apply(LabPreset.balanced.knobs, to: try loadOffer())
        let a = try #require(sections(out)["audio"])
        #expect(a.contains("a=rtcp-fb:111 transport-cc"))
    }

    @Test func videoSectionUntouched() throws {
        let sdp = try loadOffer()
        var k = LabPreset.ultraLow.knobs
        k.twcc = false
        let out = SDPMunger.apply(k, to: sdp)
        #expect(sections(out)["video"] == sections(sdp)["video"])
    }

    @Test func mungeIsIdempotent() throws {
        let sdp = try loadOffer()
        for p in LabPreset.allCases {
            var k = p.knobs
            #expect(SDPMunger.apply(k, to: SDPMunger.apply(k, to: sdp)) == SDPMunger.apply(k, to: sdp))
            k.twcc = false
            #expect(SDPMunger.apply(k, to: SDPMunger.apply(k, to: sdp)) == SDPMunger.apply(k, to: sdp))
        }
    }

    @Test func lineEndingsPreserved() throws {
        let sdp = try loadOffer()
        #expect(sdp.contains("\r\n"))
        let out = SDPMunger.apply(LabPreset.ultraLow.knobs, to: sdp)
        #expect(out.hasSuffix("\r\n"))
        let stripped = out.replacingOccurrences(of: "\r\n", with: "")
        #expect(!stripped.contains("\n") && !stripped.contains("\r"))
    }

    @Test func payloadRoundTrip() throws {
        let p = LabPayload(kind: .offer, sdp: "v=0\r\n", candidate: nil, sdpMid: nil, sdpMLineIndex: nil, knobs: LabPreset.balanced.knobs)
        #expect(try LabPayload.decode(p.encoded()) == p)
        let c = LabPayload(kind: .candidate, sdp: nil, candidate: "candidate:1 1 udp 1 1.2.3.4 5 typ host", sdpMid: "0", sdpMLineIndex: 0, knobs: nil)
        #expect(try LabPayload.decode(c.encoded()) == c)
        #expect(throws: (any Error).self) { try LabPayload.decode("!!!not base64") }
    }

    static let header = "t_ms,run_id,preset,opus_bps,ptime,dtx,fec,playback_hz,twcc,video_rung,path,local_cand,remote_cand,rtt_ms,a_bytes_sent,a_hdr_bytes_sent,a_pkts_sent,a_bytes_recv,a_hdr_bytes_recv,a_pkts_recv,a_pkts_lost,a_jitter_ms,concealed_samples,total_samples_recv,concealment_events,v_bytes_sent,v_bytes_recv,v_frame_w,v_fps_sent,pair_bytes_sent,pair_bytes_recv,avail_out_bps,thermal,battery"

    @Test func csvHeaderIsFixed() {
        #expect(StatsRow.csvHeader == Self.header)
        #expect(!StatsRow.csvHeader.contains("\n"))
    }

    @Test func missingValuesAreEmpty() {
        let row = StatsRow(tMs: 1500, runId: "r1")
        let line = row.csvLine
        #expect(line.filter { $0 == "," }.count == Self.header.filter { $0 == "," }.count)
        #expect(line.hasPrefix("1500,r1,,"))
        #expect(line.hasSuffix(",,"))
    }

    @Test func fullRowFormatting() {
        var row = StatsRow(tMs: 0, runId: "r")
        row.preset = "ultraLow"; row.battery = 0.5; row.thermal = 2; row.twcc = true; row.dtx = false
        let f = row.csvLine.components(separatedBy: ",")
        let h = Self.header.components(separatedBy: ",")
        #expect(f[h.firstIndex(of: "battery")!] == "0.50")
        #expect(f[h.firstIndex(of: "thermal")!] == "2")
        #expect(f[h.firstIndex(of: "twcc")!] == "1")
        #expect(f[h.firstIndex(of: "dtx")!] == "0")
        #expect(f[h.firstIndex(of: "preset")!] == "ultraLow")
    }
}
