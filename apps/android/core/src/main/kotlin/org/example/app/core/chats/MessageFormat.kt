package org.example.app.core.chats

/**
 * Display-only formatting of message text: `*bold*`, `_italic_`, `~strike~`, `` `mono` `` and a block between two
 * lines of three backticks. The stored and sent text never changes; copy, edit, forward, quote, report and search use
 * it as typed. The same rules run on iPhone and Mac.
 *
 * 1. An opening marker is at the start or after a boundary character and is followed by a non-space; a closing marker
 *    follows a non-space and is at the end or before a boundary character. Boundary: white space, any Unicode
 *    punctuation, or any ASCII punctuation or symbol (so `~` and the backtick count).
 * 2. A marker next to the same marker is never read (`**`, `a ** b`, `**a**` stay as typed).
 * 3. A span does not cross a newline (except the block). Nothing is parsed inside mono or a block.
 * 4. Bold, italic and strike nest (a nested pair gives two spans, the outer covering the inner); an unclosed marker
 *    stays as typed.
 * 5. A link is a white-space-delimited word that starts with http:// or https:// (any case), minus trailing
 *    `* _ ~ ` . , ; : ! ? )`. Markers inside it are never read. Links are tappable outside mono.
 *
 * Span and link ranges are UTF-16 indices into [plain], end exclusive.
 */
object MessageFormat {
    enum class Style { Bold, Italic, Strike, Mono, Block }

    data class Span(val start: Int, val end: Int, val styles: Set<Style>)

    /** A tappable link, in [plain] coordinates. */
    data class Link(val start: Int, val end: Int, val url: String)

    /** Everything the display needs, from one scan of the text. */
    class Layout(val plain: String, val spans: List<Span>, val links: List<Link>)

    /** Text longer than this many graphemes is left as typed (links are still found): bounded work on hostile input. */
    const val MAX_GRAPHEMES = 4_096

    /** Counters for the bounds tests. */
    internal var analyzeCalls = 0
    internal var monoScanSteps = 0

    fun layout(text: String): Layout = analyze(text)

    /** One span per marker pair (outer first), mono and block as `{Mono, Block}` / `{Mono}`. */
    fun parse(text: String): List<Span> = analyze(text).spans

    /** The text with the markers of valid spans removed. */
    fun plain(text: String): String = analyze(text).plain

    /** Links outside mono and blocks, in [plain] coordinates. */
    fun links(text: String): List<Link> = analyze(text).links

    private const val MARKERS = "*_~"
    private const val LINK_TRAIL = "*_~`.,;:!?)"

    private fun styleOf(c: Char) = when (c) {
        '*' -> Style.Bold
        '_' -> Style.Italic
        else -> Style.Strike
    }

    private fun isSpace(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    private fun isBoundary(cp: Int): Boolean {
        if (isSpace(cp)) return true
        if (cp < 128) return !Character.isLetterOrDigit(cp) && cp > 32 && cp != 127
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private class Open(val ch: Char, val at: Int)

    /** Ranges (original coordinates, end exclusive) of http(s) words, trailing characters trimmed. */
    private fun protectedWords(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (isSpace(text.codePointAt(i))) { i++; continue }
            var j = i
            while (j < n && !isSpace(text.codePointAt(j))) j++
            if (text.regionMatches(i, "http://", 0, 7, ignoreCase = true) || text.regionMatches(i, "https://", 0, 8, ignoreCase = true)) {
                var end = j
                while (end > i && text[end - 1] in LINK_TRAIL) end--
                if (end > i) out += i until end
            }
            i = j
        }
        return out
    }

    private fun overLimit(text: String): Boolean {
        // A grapheme is at least one UTF-16 unit, so shorter texts cannot be over the limit.
        if (text.length <= MAX_GRAPHEMES) return false
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(text)
        var count = 0
        while (it.next() != java.text.BreakIterator.DONE) if (++count > MAX_GRAPHEMES) return true
        return false
    }

    /** A combining mark or a zero-width joiner directly after a marker makes it part of a character, not a marker. */
    private fun joined(text: String, i: Int): Boolean {
        val next = i + 1
        if (next >= text.length) return false
        val cp = text.codePointAt(next)
        if (cp == 0x200D) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    }

    private fun analyze(text: String): Layout {
        analyzeCalls++
        val n = text.length
        if (n == 0) return Layout(text, emptyList(), emptyList())
        if (overLimit(text)) {
            return Layout(text, emptyList(), LinkFinder.find(text).map { Link(it.range.first, it.range.last + 1, it.url) })
        }
        val removed = BooleanArray(n)
        // (start, end exclusive, styles) in original coordinates.
        val ranges = ArrayList<Triple<Int, Int, Set<Style>>>()
        val inLink = BooleanArray(n)
        for (r in protectedWords(text)) for (k in r) inLink[k] = true
        val stack = ArrayList<Open>()
        var noCloserUntil = 0

        fun before(i: Int) = text.codePointBefore(i)

        fun canOpen(i: Int): Boolean {
            val next = i + 1
            if (next >= n) return false
            if (text[next] == text[i]) return false
            if (i > 0 && text[i - 1] == text[i]) return false
            if (i > 0 && !isBoundary(before(i))) return false
            return !isSpace(text.codePointAt(next))
        }

        fun canClose(i: Int): Boolean {
            if (i == 0 || isSpace(before(i))) return false
            if (text[i - 1] == text[i]) return false
            val next = i + 1
            if (next >= n) return true
            if (text[next] == text[i]) return false
            return isBoundary(text.codePointAt(next))
        }

        var i = 0
        while (i < n) {
            val c = text[i]
            if (inLink[i]) { i++; continue }
            if (c == '\n' || c == '\r') {
                stack.clear()
                val block = findBlock(text, i + 1)
                if (block != null) {
                    // The newline before the block stays; the fence lines and the newline before the closing fence go.
                    for (k in block.openStart until block.contentStart) removed[k] = true
                    for (k in block.contentEnd until block.after) removed[k] = true
                    ranges += Triple(block.contentStart, block.contentEnd, setOf(Style.Mono, Style.Block))
                    i = block.after
                } else {
                    i++
                }
                continue
            }
            if (i == 0) {
                val block = findBlock(text, 0)
                if (block != null) {
                    for (k in block.openStart until block.contentStart) removed[k] = true
                    for (k in block.contentEnd until block.after) removed[k] = true
                    ranges += Triple(block.contentStart, block.contentEnd, setOf(Style.Mono, Style.Block))
                    i = block.after
                    continue
                }
            }
            if ((c == '`' || c in MARKERS) && joined(text, i)) { i++; continue }
            if (c == '`') {
                var found = -1
                // A scan that reached the end of the line without a closer means no later opener on that line has one.
                if (i >= noCloserUntil && canOpen(i)) {
                    var j = i + 1
                    while (j < n && text[j] != '\n' && text[j] != '\r') {
                        monoScanSteps++
                        if (text[j] == '`' && canClose(j)) { found = j; break }
                        j++
                    }
                    if (found < 0) noCloserUntil = j
                }
                if (found >= 0) {
                    removed[i] = true
                    removed[found] = true
                    ranges += Triple(i + 1, found, setOf(Style.Mono))
                    i = found + 1
                } else {
                    i++
                }
                continue
            }
            if (c in MARKERS) {
                val at = if (canClose(i)) stack.indexOfLast { it.ch == c } else -1
                if (at >= 0 && i > stack[at].at + 1) {
                    ranges += Triple(stack[at].at + 1, i, setOf(styleOf(c)))
                    removed[stack[at].at] = true
                    removed[i] = true
                    // Anything opened inside and not closed is shown as typed.
                    while (stack.size > at) stack.removeAt(stack.size - 1)
                } else if (canOpen(i)) {
                    stack += Open(c, i)
                }
            }
            i++
        }

        // Original index -> plain index.
        val map = IntArray(n + 1)
        val plain = StringBuilder(n)
        for (k in 0 until n) {
            map[k] = plain.length
            if (!removed[k]) plain.append(text[k])
        }
        map[n] = plain.length

        val spans = ranges
            .map { (s, e, st) -> Span(map[s], map[e], st) }
            .filter { it.end > it.start }
            .sortedWith(compareBy<Span>({ it.start }, { -it.end }))

        val plainText = plain.toString()
        val code = spans.filter { Style.Mono in it.styles }
        val links = LinkFinder.find(plainText)
            .filter { l -> code.none { l.range.first >= it.start && l.range.first < it.end } }
            .map { Link(it.range.first, it.range.last + 1, it.url) }
        return Layout(plainText, spans, links)
    }

    private class Block(val openStart: Int, val contentStart: Int, val contentEnd: Int, val after: Int)

    /** A block starts at [at] (a line start): a line of exactly three backticks, non-blank lines, a line of exactly three backticks. */
    private fun findBlock(text: String, at: Int): Block? {
        val n = text.length
        if (!text.startsWith("```\n", at)) return null
        val contentStart = at + 4
        var lineStart = contentStart
        while (lineStart <= n) {
            val nl = text.indexOf('\n', lineStart).let { if (it < 0) n else it }
            if (text.substring(lineStart, nl) == "```") {
                if (lineStart == contentStart) return null
                val content = text.substring(contentStart, lineStart - 1)
                if (content.isBlank()) return null
                // Also drop the newline before the closing fence.
                return Block(at, contentStart, lineStart - 1, nl)
            }
            if (nl >= n) return null
            lineStart = nl + 1
        }
        return null
    }
}
