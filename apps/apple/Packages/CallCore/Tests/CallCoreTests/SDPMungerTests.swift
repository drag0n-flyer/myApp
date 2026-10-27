import Testing
import Foundation
import AppCore
@testable import CallCore

private func fixture(_ name: String) throws -> String {
    let url = try #require(Bundle.module.url(forResource: name, withExtension: "sdp", subdirectory: "Fixtures"))
    return try String(contentsOf: url, encoding: .utf8)
}
private func lines(_ s: String) -> [String] { s.components(separatedBy: "\r\n") }
private func section(_ sdp: String, _ kind: String) -> [String] {
    var out: [String] = [], on = false
    for l in lines(sdp) {
        if l.hasPrefix("m=") { on = l.hasPrefix("m=\(kind) ") }
        if on { out.append(l) }
    }
    return out
}

@Suite struct SDPMungerTests {
    @Test func tableMatchesDesign() {
        let rows: [(DataMode, Int, Int, Bool, Bool, Int, Int)] = [
            (.ultraLow, 10000, 60, false, true, 16000, 21000),
            (.dataSaver, 16000, 40, true, true, 16000, 30000),
            (.balanced, 24000, 20, true, true, 48000, 51000),
            (.high, 40000, 20, true, true, 48000, 75000),
        ]
        #expect(AudioProfile.table.count == 4)
        for (m, br, pt, fec, dtx, rate, wire) in rows {
            let p = AudioProfile.for(m)
            #expect(p.mode == m && p.maxAverageBitrate == br && p.ptime == pt && p.inbandFEC == fec
                    && p.dtx == dtx && p.maxPlaybackRate == rate && p.wireBudget == wire)
        }
    }

    @Test func mungesOpusFmtp() throws {
        let out = SDPMunger.apply(.for(.dataSaver), to: try fixture("chrome-offer"))
        let fmtp = try #require(lines(out).first { $0.hasPrefix("a=fmtp:111 ") })
        #expect(fmtp.contains("maxaveragebitrate=16000;useinbandfec=1;usedtx=1;maxplaybackrate=16000;stereo=0"))
        #expect(fmtp.contains("minptime=10"))
        #expect(fmtp.components(separatedBy: "useinbandfec").count == 2)
    }

    @Test func insertsPtime() throws {
        let src = try fixture("chrome-offer")
        let out = SDPMunger.apply(.for(.dataSaver), to: src)
        let audio = section(out, "audio")
        #expect(audio.filter { $0 == "a=ptime:40" }.count == 1)
        #expect(audio.filter { $0 == "a=maxptime:40" }.count == 1)
        #expect(audio.filter { $0.hasPrefix("a=ptime:") || $0.hasPrefix("a=maxptime:") }.count == 2)
        #expect(section(out, "video") == section(src, "video"))
    }

    @Test func nonDefaultPayloadType() throws {
        let out = SDPMunger.apply(.for(.ultraLow), to: try fixture("firefox-offer"))
        let l = lines(out)
        let fmtp = try #require(l.first { $0.hasPrefix("a=fmtp:109 ") })
        #expect(fmtp.contains("maxaveragebitrate=10000") && fmtp.contains("useinbandfec=0"))
        #expect(fmtp.contains("stereo=0") && !fmtp.contains("stereo=1"))
        #expect(fmtp.contains("maxplaybackrate=16000") && !fmtp.contains("maxplaybackrate=48000"))
        for key in ["maxaveragebitrate", "useinbandfec", "usedtx", "maxplaybackrate", "stereo"] {
            #expect(fmtp.components(separatedBy: key + "=").count == 2, "\(key) duplicated")
        }
        #expect(l.filter { $0.hasPrefix("a=fmtp:109 ") }.count == 1)
        #expect(section(out, "audio").filter { $0 == "a=ptime:60" }.count == 1)
    }

    @Test func idempotent() throws {
        for name in ["chrome-offer", "ios-webrtc-offer", "firefox-offer"] {
            let s = try fixture(name)
            let once = SDPMunger.apply(.for(.balanced), to: s)
            #expect(SDPMunger.apply(.for(.balanced), to: once) == once)
        }
    }

    @Test func crlfPreserved() throws {
        let out = SDPMunger.apply(.for(.high), to: try fixture("chrome-offer"))
        #expect(out.hasSuffix("\r\n"))
        #expect(out.replacingOccurrences(of: "\r\n", with: "").contains("\n") == false)
    }

    @Test func extractsFingerprint() throws {
        let fp = SDPMunger.fingerprint(in: try fixture("chrome-offer"))
        #expect(fp?.hasPrefix("sha-256 AB:CD:EF:01") == true)
        #expect(SDPMunger.fingerprint(in: "v=0\r\n") == nil)
    }

    @Test func ptimeAfterLastPayloadAttribute() throws {
        for name in ["chrome-offer", "ios-webrtc-offer", "firefox-offer"] {
            let audio = section(SDPMunger.apply(.for(.dataSaver), to: try fixture(name)), "audio")
            let pi = try #require(audio.firstIndex { $0.hasPrefix("a=ptime:") })
            #expect(audio[pi + 1].hasPrefix("a=maxptime:"))
            let lastPayload = try #require(audio.lastIndex {
                $0.hasPrefix("a=rtpmap:") || $0.hasPrefix("a=fmtp:") || $0.hasPrefix("a=rtcp-fb:")
            })
            #expect(pi == lastPayload + 1)
        }
    }

    @Test func lfOnlyInputBecomesCRLF() throws {
        let lf = try fixture("chrome-offer").replacingOccurrences(of: "\r\n", with: "\n")
        let out = SDPMunger.apply(.for(.balanced), to: lf)
        #expect(out.replacingOccurrences(of: "\r\n", with: "").contains("\n") == false)
        #expect(out.contains("\r\n"))
    }
}
