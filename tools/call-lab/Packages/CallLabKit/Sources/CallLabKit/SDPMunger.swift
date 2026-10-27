import Foundation

public enum SDPMunger {
    /// Rewrites the audio m-section of `sdp` to carry the Opus, ptime and TWCC knobs.
    /// Other sections are left byte-identical. Output uses CRLF line endings.
    public static func apply(_ k: LabKnobs, to sdp: String) -> String {
        var lines = sdp.components(separatedBy: "\r\n")
        let hadTrailing = lines.last == ""
        if hadTrailing { lines.removeLast() }

        guard let start = lines.firstIndex(where: { $0.hasPrefix("m=audio") }) else {
            return sdp
        }
        let end = lines[(start + 1)...].firstIndex(where: { $0.hasPrefix("m=") }) ?? lines.count
        var audio = Array(lines[start..<end])

        // Opus payload type.
        let pt = audio.lazy.compactMap { l -> String? in
            guard l.hasPrefix("a=rtpmap:"), l.hasSuffix(" opus/48000/2") || l.contains(" opus/48000/2") else { return nil }
            return String(l.dropFirst("a=rtpmap:".count).prefix { $0 != " " })
        }.first

        if let pt {
            let fmtp = "a=fmtp:\(pt) minptime=10;useinbandfec=\(k.fec ? 1 : 0);usedtx=\(k.dtx ? 1 : 0)"
                + ";maxaveragebitrate=\(k.opusBitrate);maxplaybackrate=\(k.maxPlaybackRate)"
                + ";sprop-maxcapturerate=\(k.maxPlaybackRate)"
            if let i = audio.firstIndex(where: { $0.hasPrefix("a=fmtp:\(pt) ") }) {
                audio[i] = fmtp
            } else if let r = audio.firstIndex(where: { $0.hasPrefix("a=rtpmap:\(pt) ") }) {
                audio.insert(fmtp, at: r + 1)
            }
        }

        if !k.twcc {
            audio.removeAll { l in
                (l.hasPrefix("a=rtcp-fb:") && l.hasSuffix(" transport-cc"))
                    || (l.hasPrefix("a=extmap:") && l.contains("transport-wide-cc-extensions"))
            }
        }

        audio.removeAll { $0.hasPrefix("a=ptime:") || $0.hasPrefix("a=maxptime:") }
        audio.append("a=ptime:\(k.ptime)")
        audio.append("a=maxptime:\(max(120, k.ptime))")

        lines.replaceSubrange(start..<end, with: audio)
        return lines.joined(separator: "\r\n") + "\r\n"
    }
}
