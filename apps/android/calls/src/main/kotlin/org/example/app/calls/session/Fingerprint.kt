package org.example.app.calls.session

/**
 * The DTLS fingerprint rule for every SDP accepted from the peer (docs/protocol/calls.md, "What the receiver
 * must check", step 4). The fingerprint arrived inside an MLS message from the peer, so a server or relay
 * that swaps it in the SDP is caught here.
 */
object Fingerprint {
    private const val PREFIX = "a=fingerprint:"
    private val hashName = Regex("[a-z0-9-]+")
    private val hexPairs = Regex("[0-9A-F]{2}(:[0-9A-F]{2})*")

    /**
     * The canonical form `"<hash> <HEX:HEX…>"`: hash name lower case, hex upper case, one space between.
     * Null when it is not a hash name followed by colon-separated hex pairs.
     */
    fun normalise(raw: String?): String? {
        val parts = raw?.trim()?.split(Regex("\\s+")) ?: return null
        if (parts.size != 2) return null
        val hash = parts[0].lowercase()
        val hex = parts[1].uppercase()
        if (!hashName.matches(hash) || !hexPairs.matches(hex)) return null
        return "$hash $hex"
    }

    /** The raw values of every `a=fingerprint:` line, session and media level. */
    fun lines(sdp: String): List<String> =
        sdp.replace("\r\n", "\n").split("\n").filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX).trim() }

    /**
     * What we put in `dtlsFingerprint` for our own SDP: the first line copied as is (the iPhone compares it
     * byte for byte with its SDP line). Null when the SDP has none, a malformed one, or lines that disagree.
     */
    fun local(sdp: String): String? {
        val all = lines(sdp)
        val first = all.firstOrNull() ?: return null
        val canonical = normalise(first) ?: return null
        return if (all.all { normalise(it) == canonical }) first else null
    }

    /**
     * True when `claimed` is well formed, the SDP has at least one `a=fingerprint` line and every one equals it,
     * and, when `pinned` is given, it equals the fingerprint pinned from the first offer or answer.
     * `requirePin` makes a missing pin fail. All comparisons use [normalise].
     */
    fun isValid(sdp: String?, claimed: String?, pinned: String? = null, requirePin: Boolean = false): Boolean {
        if (sdp == null) return false
        val want = normalise(claimed) ?: return false
        val all = lines(sdp)
        if (all.isEmpty() || all.any { normalise(it) != want }) return false
        if (pinned != null) return normalise(pinned) == want
        return !requirePin
    }
}
