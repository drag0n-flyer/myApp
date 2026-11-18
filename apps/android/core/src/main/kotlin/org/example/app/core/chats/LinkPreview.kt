package org.example.app.core.chats

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** A link preview as the chat shows it: made by the sender's app, carried in the message, never fetched by a receiver. */
class LinkPreviewCard(val url: String, val title: String, val desc: String? = null, val image: ByteArray? = null) {
    override fun equals(other: Any?) = other is LinkPreviewCard && url == other.url && title == other.title && desc == other.desc &&
        (image contentEquals other.image)
    override fun hashCode() = url.hashCode() + title.hashCode()
}

/** Makes a preview for a link, on the sender's device and only when the sender turned previews on. Null: no preview. */
fun interface LinkPreviewMaker {
    suspend fun make(url: String): LinkPreviewCard?
}

/**
 * Which links get a preview and which addresses may be contacted to make one. Pure: no network and no name lookup
 * happens here. The fetcher resolves a name itself and asks [allowedHost] about the answer before it connects.
 */
object LinkPreviewRule {
    /** A link longer than this gets no preview. */
    const val MAX_URL = 2_048

    /** The first `https://` link of [text] that is not inside code, exactly as it is written in the text; null when there is none. */
    fun firstLink(text: String): String? {
        val link = MessageFormat.layout(text).links.firstOrNull { it.url.startsWith("https://", ignoreCase = true) } ?: return null
        // The receiver accepts a preview only when its url is in the text, so a rewritten link would be dropped.
        return link.url.takeIf { it.length <= MAX_URL && it in text }
    }

    private const val TITLE_MAX = 200
    private const val DESC_MAX = 300
    private const val IMAGE_MAX = 8_192

    /**
     * [card] when a receiver will accept it for a message with [text], else null: the url is an https link that is in
     * the text and passes [checkUrl]; the title has 1 to 200 code points (not blank); the description at most 300; the
     * image, when there is one, is a JPEG of at most 8,192 bytes.
     */
    fun cardFor(text: String, card: LinkPreviewCard): LinkPreviewCard? {
        if (checkUrl(card.url) == null || card.url !in text) return null
        if (card.title.isBlank() || card.title.codePointCount(0, card.title.length) > TITLE_MAX) return null
        if (card.desc != null && card.desc.codePointCount(0, card.desc.length) > DESC_MAX) return null
        val img = card.image
        if (img != null && !(img.size in 2..IMAGE_MAX && img[0] == 0xFF.toByte() && img[1] == 0xD8.toByte())) return null
        return card
    }

    /**
     * True when a request to [host] may be made: [host] is an ordinary public DNS name ([hostNameAllowed]) and every
     * address it [resolved] to is a public one. One bad address refuses the name. Each address is 4 or 16 raw bytes.
     */
    fun allowedHost(host: String, resolved: List<ByteArray>): Boolean =
        hostNameAllowed(host) && resolved.isNotEmpty() && resolved.all(::addressAllowed)

    /**
     * Names only: not an IP address in any notation (dotted, numeric, hex, bracketed), no single-label name, nothing
     * ending in `.local`, `.internal`, `.localhost` or `.localdomain`, only letters, digits and hyphens in valid labels.
     */
    fun hostNameAllowed(raw: String): Boolean {
        var host = raw.lowercase(Locale.ROOT)
        if (host.endsWith(".")) host = host.dropLast(1)
        if (host.isEmpty() || host.length > 253) return false
        if (host.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.') }) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return false
        val last = labels.last()
        // Every notation of an IPv4 address ends in a number: 127.1, 2130706433, 0x7f.1, 1.2.3.4.
        if (last.all { it in '0'..'9' } || last.startsWith("0x")) return false
        return last !in LOCAL_SUFFIXES
    }

    private val LOCAL_SUFFIXES = setOf("local", "internal", "localhost", "localdomain")

    /** A public, routable address. Refused: unspecified, loopback, private, link-local, unique-local, CGNAT, multicast, reserved, documentation. */
    fun addressAllowed(b: ByteArray): Boolean = when (b.size) {
        4 -> v4Allowed(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b[2].toInt() and 0xFF)
        16 -> v6Allowed(b)
        else -> false
    }

    private fun v4Allowed(a: Int, b: Int, c: Int): Boolean = when {
        a == 0 || a == 10 || a == 127 -> false
        a == 100 && b in 64..127 -> false // carrier-grade NAT
        a == 169 && b == 254 -> false // link-local, cloud metadata
        a == 172 && b in 16..31 -> false
        a == 192 && b == 168 -> false
        a == 192 && b == 0 && (c == 0 || c == 2) -> false
        a == 198 && (b == 18 || b == 19) -> false
        a == 198 && b == 51 && c == 100 -> false
        a == 203 && b == 0 && c == 113 -> false
        a >= 224 -> false // multicast, reserved, broadcast
        else -> true
    }

    private fun v6Allowed(b: ByteArray): Boolean {
        fun u(i: Int) = b[i].toInt() and 0xFF
        fun zero(from: Int, to: Int) = (from until to).all { b[it].toInt() == 0 }
        fun embedded(at: Int) = v4Allowed(u(at), u(at + 1), u(at + 2))
        if (zero(0, 10) && u(10) == 0xFF && u(11) == 0xFF) return embedded(12) // ::ffff:a.b.c.d
        if (zero(0, 12)) return false // ::, ::1 and the IPv4-compatible form
        if (u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xFF && u(3) == 0x9B) return zero(4, 12) && embedded(12) // NAT64
        if ((u(0) and 0xE0) != 0x20) return false // only global unicast 2000::/3: refuses fe80::/10, fc00::/7, fec0::/10, ff00::/8
        if (u(0) == 0x20 && u(1) == 0x02) return embedded(2) // 6to4
        if (u(0) == 0x20 && u(1) == 0x01 && u(2) == 0x00 && u(3) == 0x00) return false // Teredo
        if (u(0) == 0x20 && u(1) == 0x01 && u(2) == 0x0D && u(3) == 0xB8) return false // documentation
        return true
    }

    /**
     * The host of [url] in lower case without a trailing dot when a preview may be fetched from it: `https` only, no
     * user or password, port 443 only, a host that passes [hostNameAllowed], no white space or control characters,
     * at most [MAX_URL] characters. Null otherwise.
     */
    fun checkUrl(url: String): String? {
        if (url.isEmpty() || url.length > MAX_URL) return null
        if (url.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = try { URI(url) } catch (_: URISyntaxException) { return null }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host ?: return null
        if (!hostNameAllowed(host)) return null
        return host.lowercase(Locale.ROOT).removeSuffix(".")
    }

    /**
     * Where a redirect from [current] to [location] (absolute or relative) may go: the same host, over https, and a
     * URL that passes [checkUrl]. Anything else, including a different host or a downgrade to http, is null.
     */
    fun redirectTarget(current: String, location: String): String? {
        if (location.isBlank()) return null
        val from = checkUrl(current) ?: return null
        val target = try { URI(current).resolve(location).toString() } catch (_: Exception) { return null }
        val host = checkUrl(target) ?: return null
        return target.takeIf { host == from }
    }
}

/** Reads the preview fields out of the first bytes of a web page. Never throws; linear in the size of the input. */
object LinkPreviewParse {
    const val TITLE_MAX = 200
    const val DESC_MAX = 300

    /** [imageUrl] is as written in the page (possibly relative); the fetcher resolves and checks it. */
    class Parsed(val title: String, val desc: String?, val imageUrl: String?)

    /** `og:title`, `og:description`, `og:image`, with the `<title>` element when there is no usable `og:title`. Null without a title. */
    fun fromHTML(bytes: ByteArray, limit: Int): Parsed? {
        val html = String(bytes, 0, minOf(bytes.size, maxOf(limit, 0)), Charsets.UTF_8)
        val found = scan(html)
        val title = clean(found.ogTitle, TITLE_MAX) ?: clean(found.titleElement, TITLE_MAX) ?: return null
        val image = found.ogImage?.let { decodeEntities(it).trim() }?.takeIf { it.isNotEmpty() && it.length <= LinkPreviewRule.MAX_URL }
        return Parsed(title, clean(found.ogDescription, DESC_MAX), image)
    }

    private class Found {
        var ogTitle: String? = null
        var ogDescription: String? = null
        var ogImage: String? = null
        var titleElement: String? = null
    }

    private fun scan(s: String): Found {
        val out = Found()
        val n = s.length
        var i = 0
        while (i < n) {
            val lt = s.indexOf('<', i)
            if (lt < 0) break
            i = lt + 1
            if (s.startsWith("!--", i)) {
                val end = s.indexOf("-->", i + 3)
                if (end < 0) break
                i = end + 3
                continue
            }
            // Tag name.
            var j = i
            while (j < n && (s[j].isLetterOrDigit() || s[j] == '!' || s[j] == '/')) j++
            val name = s.substring(i, j).lowercase(Locale.ROOT)
            if (name == "script" || name == "style") {
                val end = indexOfIgnoreCase(s, "</$name", j)
                if (end < 0) break
                i = end + 2 // lands on the closing tag's name, which the next round skips past
                continue
            }
            // Attributes up to '>' (quotes may hide a '>').
            val attrs = ArrayList<Pair<String, String>>()
            var k = j
            var closed = false
            while (k < n) {
                val c = s[k]
                if (c == '>') { closed = true; k++; break }
                if (c.isWhitespace() || c == '/') { k++; continue }
                val nameStart = k
                while (k < n && !s[k].isWhitespace() && s[k] != '=' && s[k] != '>' && s[k] != '/') k++
                val attr = s.substring(nameStart, k).lowercase(Locale.ROOT)
                while (k < n && s[k].isWhitespace()) k++
                var value = ""
                if (k < n && s[k] == '=') {
                    k++
                    while (k < n && s[k].isWhitespace()) k++
                    if (k < n && (s[k] == '"' || s[k] == '\'')) {
                        val quote = s[k]
                        val end = s.indexOf(quote, k + 1)
                        if (end < 0) { k = n; value = ""; break } // an unterminated quote swallows the rest
                        value = s.substring(k + 1, end)
                        k = end + 1
                    } else {
                        val start = k
                        while (k < n && !s[k].isWhitespace() && s[k] != '>') k++
                        value = s.substring(start, k)
                    }
                }
                if (attr.isNotEmpty()) attrs += attr to value
                else if (k == nameStart) k++ // a lone '=' must not stall the loop
            }
            i = if (closed) k else n
            if (name == "meta") {
                val key = (attrs.firstOrNull { it.first == "property" }?.second ?: attrs.firstOrNull { it.first == "name" }?.second)
                    ?.trim()?.lowercase(Locale.ROOT)
                val content = attrs.firstOrNull { it.first == "content" }?.second
                if (key != null && content != null) when (key) {
                    "og:title" -> if (out.ogTitle == null) out.ogTitle = content
                    "og:description" -> if (out.ogDescription == null) out.ogDescription = content
                    "og:image" -> if (out.ogImage == null) out.ogImage = content
                }
            } else if (name == "title" && out.titleElement == null && closed) {
                val end = s.indexOf('<', i)
                if (end < 0) break // cut off before the end of the title: a half title is not used
                out.titleElement = s.substring(i, end)
            }
        }
        return out
    }

    private fun indexOfIgnoreCase(s: String, needle: String, from: Int): Int {
        // `needle` is ASCII; compare in place so a huge page is not copied or lower-cased again for every script tag.
        val last = s.length - needle.length
        var at = s.indexOf('<', from)
        while (at in 0..last) {
            if (s.regionMatches(at, needle, 0, needle.length, ignoreCase = true)) return at
            at = s.indexOf('<', at + 1)
        }
        return -1
    }

    /** Entities decoded, then [sanitize]d. */
    private fun clean(raw: String?, max: Int): String? = if (raw == null) null else sanitize(decodeEntities(raw), max)

    /**
     * [text] as it may be shown: white space collapsed to single spaces, control, format (direction marks, zero width),
     * private-use and unassigned characters removed, trimmed, cut to [max] code points. Null when nothing visible is left.
     * No entities are decoded. The receiver of a preview runs this on the title and description it gets.
     */
    fun sanitize(text: String, max: Int): String? {
        val sb = StringBuilder()
        var pendingSpace = false
        var count = 0
        var i = 0
        while (i < text.length && count < max) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            val space = Character.isWhitespace(cp) || Character.isSpaceChar(cp) ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
            if (space) { pendingSpace = sb.isNotEmpty(); continue }
            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
                type == Character.UNASSIGNED.toInt() || type == Character.PRIVATE_USE.toInt()
            ) continue
            if (pendingSpace) { sb.append(' '); pendingSpace = false; count++; if (count >= max) break }
            sb.appendCodePoint(cp)
            count++
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    private fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            // A bounded look ahead: a page of nothing but '&' must not cost a scan to the end for each one.
            var semi = -1
            for (p in i + 1 until minOf(s.length, i + 11)) if (s[p] == ';') { semi = p; break }
            if (semi < 0) { sb.append(c); i++; continue }
            val body = s.substring(i + 1, semi)
            val replacement: String? = when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2), 16)
                body.startsWith("#") -> codePoint(body.substring(1), 10)
                else -> NAMED[body]
            }
            if (replacement == null && !body.startsWith("#")) { sb.append(c); i++; continue } // unknown name: left as written
            sb.append(replacement.orEmpty()) // a numeric entity that is not a character is dropped
            i = semi + 1
        }
        return sb.toString()
    }

    private fun codePoint(digits: String, radix: Int): String? {
        if (digits.isEmpty() || digits.length > 8) return ""
        val cp = digits.toIntOrNull(radix) ?: return ""
        if (cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return ""
        return String(Character.toChars(cp))
    }
}
