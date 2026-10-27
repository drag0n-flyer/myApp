package org.example.app.calls.policy

import org.example.app.contracts.DataMode

/** Line-based SDP edits for Opus settings. Only the audio m-section is touched. Output uses CRLF. */
object SdpMunger {
    private val managedKeys = setOf("maxaveragebitrate", "useinbandfec", "usedtx", "maxplaybackrate", "stereo")

    /** Local description: what we are willing to receive, at our own ceiling. */
    fun local(sdp: String, ceiling: DataMode): String = apply(AudioProfile.forMode(ceiling), sdp)

    /** Remote description: what the peer sends us, at the mode we send at. */
    fun remote(sdp: String, sendMode: DataMode): String = apply(AudioProfile.forMode(sendMode), sdp)

    fun apply(p: AudioProfile, sdp: String): String {
        val lines = sdp.replace("\r\n", "\n").split("\n").toMutableList()
        val hadTrailingEol = lines.last() == ""
        if (hadTrailingEol) lines.removeAt(lines.size - 1)

        val audioStart = lines.indexOfFirst { it.startsWith("m=audio ") }
        if (audioStart < 0) return join(lines, hadTrailingEol)
        var audioEnd = (audioStart + 1 until lines.size).firstOrNull { lines[it].startsWith("m=") } ?: lines.size

        // 1. Opus payload type.
        var opusPt: String? = null
        for (i in audioStart until audioEnd) {
            val l = lines[i]
            if (!l.startsWith("a=rtpmap:")) continue
            val parts = l.removePrefix("a=rtpmap:").split(" ", limit = 2)
            if (parts.size == 2 && parts[1].lowercase().startsWith("opus/48000")) { opusPt = parts[0]; break }
        }
        val pt = opusPt ?: return join(lines, hadTrailingEol)

        // 2. fmtp.
        val managed = listOf(
            "maxaveragebitrate=${p.maxAverageBitrate}",
            "useinbandfec=${if (p.inbandFEC) 1 else 0}",
            "usedtx=${if (p.dtx) 1 else 0}",
            "maxplaybackrate=${p.maxPlaybackRate}",
            "stereo=0",
        )
        val fmtpPrefix = "a=fmtp:$pt "
        val fmtpIdx = (audioStart until audioEnd).firstOrNull { lines[it].startsWith(fmtpPrefix) }
        if (fmtpIdx != null) {
            val kept = lines[fmtpIdx].removePrefix(fmtpPrefix).split(";").map { it.trim() }.filter { param ->
                val key = param.split("=", limit = 2).first()
                param.isNotEmpty() && key !in managedKeys
            }
            lines[fmtpIdx] = fmtpPrefix + (kept + managed).joinToString(";")
        } else {
            val r = (audioStart until audioEnd).firstOrNull { lines[it].startsWith("a=rtpmap:$pt ") }
            if (r != null) {
                lines.add(r + 1, fmtpPrefix + managed.joinToString(";"))
                audioEnd += 1
            }
        }

        // 3. ptime / maxptime, right after the last payload attribute line (rtpmap, fmtp, rtcp-fb).
        var i = audioEnd - 1
        while (i > audioStart) {
            if (lines[i].startsWith("a=ptime:") || lines[i].startsWith("a=maxptime:")) {
                lines.removeAt(i); audioEnd -= 1
            }
            i -= 1
        }
        val lastPayload = (audioStart until audioEnd).lastOrNull {
            lines[it].startsWith("a=rtpmap:") || lines[it].startsWith("a=fmtp:") || lines[it].startsWith("a=rtcp-fb:")
        } ?: (audioEnd - 1)
        lines.addAll(lastPayload + 1, listOf("a=ptime:${p.ptime}", "a=maxptime:${p.ptime}"))

        return join(lines, hadTrailingEol)
    }

    /** Returns "sha-256 AB:..." from the first `a=fingerprint:` line. */
    fun fingerprint(sdp: String): String? =
        sdp.replace("\r\n", "\n").split("\n").firstOrNull { it.startsWith("a=fingerprint:") }
            ?.removePrefix("a=fingerprint:")?.trim()

    private fun join(lines: List<String>, trailing: Boolean) = lines.joinToString("\r\n") + if (trailing) "\r\n" else ""
}
