import Foundation

/// Line-based SDP edits for Opus settings. Only the audio m-section is touched.
public enum SDPMunger {
    private static let managedKeys: Set<String> =
        ["maxaveragebitrate", "useinbandfec", "usedtx", "maxplaybackrate", "stereo"]

    public static func apply(_ p: AudioProfile, to sdp: String) -> String {
        var lines = sdp.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        let hadTrailingEOL = lines.last == ""
        if hadTrailingEOL { lines.removeLast() }

        guard let audioStart = lines.firstIndex(where: { $0.hasPrefix("m=audio ") }) else {
            return join(lines, trailing: hadTrailingEOL)
        }
        var audioEnd = lines[(audioStart + 1)...].firstIndex { $0.hasPrefix("m=") } ?? lines.count

        // 1. Opus payload type.
        var opusPT: String?
        for i in audioStart..<audioEnd {
            let l = lines[i]
            guard l.hasPrefix("a=rtpmap:") else { continue }
            let body = l.dropFirst("a=rtpmap:".count)
            let parts = body.split(separator: " ", maxSplits: 1)
            if parts.count == 2, parts[1].lowercased().hasPrefix("opus/48000") { opusPT = String(parts[0]); break }
        }
        guard let pt = opusPT else { return join(lines, trailing: hadTrailingEOL) }

        // 2. fmtp.
        let managed = [
            "maxaveragebitrate=\(p.maxAverageBitrate)",
            "useinbandfec=\(p.inbandFEC ? 1 : 0)",
            "usedtx=\(p.dtx ? 1 : 0)",
            "maxplaybackrate=\(p.maxPlaybackRate)",
            "stereo=0",
        ]
        let fmtpPrefix = "a=fmtp:\(pt) "
        if let i = (audioStart..<audioEnd).first(where: { lines[$0].hasPrefix(fmtpPrefix) }) {
            let kept = lines[i].dropFirst(fmtpPrefix.count).split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { param in
                    let key = param.split(separator: "=", maxSplits: 1).first.map(String.init) ?? param
                    return !param.isEmpty && !managedKeys.contains(key)
                }
            lines[i] = fmtpPrefix + (kept + managed).joined(separator: ";")
        } else if let r = (audioStart..<audioEnd).first(where: { lines[$0].hasPrefix("a=rtpmap:\(pt) ") }) {
            lines.insert(fmtpPrefix + managed.joined(separator: ";"), at: r + 1)
            audioEnd += 1
        }

        // 3. ptime / maxptime, right after the last payload attribute line (rtpmap, fmtp, rtcp-fb).
        var i = audioEnd - 1
        while i > audioStart {
            if lines[i].hasPrefix("a=ptime:") || lines[i].hasPrefix("a=maxptime:") {
                lines.remove(at: i); audioEnd -= 1
            }
            i -= 1
        }
        let lastPayload = (audioStart..<audioEnd).last {
            lines[$0].hasPrefix("a=rtpmap:") || lines[$0].hasPrefix("a=fmtp:") || lines[$0].hasPrefix("a=rtcp-fb:")
        } ?? (audioEnd - 1)
        lines.insert(contentsOf: ["a=ptime:\(p.ptime)", "a=maxptime:\(p.ptime)"], at: lastPayload + 1)

        return join(lines, trailing: hadTrailingEOL)
    }

    /// Returns "sha-256 AB:…" from the first `a=fingerprint:` line.
    public static func fingerprint(in sdp: String) -> String? {
        for raw in sdp.replacingOccurrences(of: "\r\n", with: "\n").split(separator: "\n") where raw.hasPrefix("a=fingerprint:") {
            return String(raw.dropFirst("a=fingerprint:".count)).trimmingCharacters(in: .whitespaces)
        }
        return nil
    }

    private static func join(_ lines: [String], trailing: Bool) -> String {
        lines.joined(separator: "\r\n") + (trailing ? "\r\n" : "")
    }
}
