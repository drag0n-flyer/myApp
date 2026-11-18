package org.example.app.core.chats

/**
 * Display-only formatting of message text: `*bold*`, `_italic_`, `~strike~`, `` `mono` `` and a block between three
 * backticks. The stored and sent text never changes; copy, edit, forward, quote and search use it as typed.
 *
 * Rules (the same on iPhone and Mac):
 * 1. An opening marker is at the start or after a space, newline or punctuation, and is followed by a non-space. A
 *    closing marker follows a non-space and is at the end or before a space, newline or punctuation.
 * 2. A span does not cross a newline (except the block).
 * 3. Nothing is parsed inside mono or a block.
 * 4. Bold, italic and strike nest; an unclosed marker stays as typed.
 * 5. Links stay whole and tappable outside mono.
 *
 * All ranges are UTF-16 units of [plain], end exclusive. "Punctuation" is any character that is not a letter, digit,
 * combining mark or white space (symbols and emoji count).
 */
object MessageFormat {
    enum class Style { Bold, Italic, Strike, Mono, Block }

    /** [start] until [end] of [plain]; spans are separate (never overlap), unstyled text has none. */
    data class Span(val start: Int, val end: Int, val styles: Set<Style>)

    /** A tappable link, in [plain] coordinates. */
    data class Link(val start: Int, val end: Int, val url: String)

    private class Result(val plain: String, val spans: List<Span>, val links: List<Link>)

    fun parse(text: String): List<Span> = analyze(text).spans

    /** The text with the markers of valid spans removed. */
    fun plain(text: String): String = analyze(text).plain

    /** Links outside mono and blocks, in [plain] coordinates. */
    fun links(text: String): List<Link> = analyze(text).links

    private const val MARKERS = "*_~"

    private fun styleOf(c: Char) = when (c) {
        '*' -> Style.Bold
        '_' -> Style.Italic
        else -> Style.Strike
    }

    private fun isSpace(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    private fun isBoundary(cp: Int): Boolean {
        if (isSpace(cp)) return true
        if (Character.isLetterOrDigit(cp)) return false
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> false
            else -> true
        }
    }

    private class Open(val ch: Char, val at: Int)

    private fun analyze(text: String): Result {
        val n = text.length
        if (n == 0) return Result(text, emptyList(), emptyList())
        val removed = BooleanArray(n)
        // Styled ranges in original coordinates, end exclusive.
        val ranges = ArrayList<Triple<Int, Int, Style>>()
        val rawLinks = LinkFinder.find(text)
        val linkEnd = IntArray(n) { -1 }
        for (l in rawLinks) for (i in l.range) linkEnd[i] = l.range.last
        val stack = ArrayList<Open>()

        fun before(i: Int) = text.codePointBefore(i)

        fun canOpen(i: Int, width: Int): Boolean {
            val next = i + width
            if (next >= n) return false
            if (i > 0 && !isBoundary(before(i))) return false
            return !isSpace(text.codePointAt(next))
        }

        fun canClose(i: Int, width: Int): Boolean {
            if (i == 0 || isSpace(before(i))) return false
            val next = i + width
            return next >= n || isBoundary(text.codePointAt(next))
        }

        var i = 0
        while (i < n) {
            val c = text[i]
            // Links stay whole: nothing inside is a marker, except a last character that closes an open span
            // (`*https://x.example*`).
            if (linkEnd[i] >= 0) {
                val end = linkEnd[i]
                if (end > i && text[end] in MARKERS) {
                    val m = text[end]
                    val at = stack.indexOfLast { it.ch == m }
                    if (at >= 0 && canClose(end, 1) && end > stack[at].at + 1) {
                        ranges += Triple(stack[at].at + 1, end, styleOf(m))
                        removed[stack[at].at] = true
                        removed[end] = true
                        while (stack.size > at) stack.removeAt(stack.size - 1)
                    }
                }
                i = end + 1
                continue
            }
            if (c == '\n') { stack.clear(); i++; continue }
            if (c == '`' && text.startsWith("```", i)) {
                val block = findBlock(text, i)
                if (block != null) {
                    stack.clear()
                    for (k in i until block.contentStart) removed[k] = true
                    for (k in block.contentEnd until block.after) removed[k] = true
                    ranges += Triple(block.contentStart, block.contentEnd, Style.Block)
                    i = block.after
                    continue
                }
                i += 3
                continue
            }
            if (c == '`') {
                var found = -1
                if (canOpen(i, 1)) {
                    var j = i + 1
                    while (j < n && text[j] != '\n') {
                        if (text[j] == '`' && j > i + 1 && canClose(j, 1)) { found = j; break }
                        j++
                    }
                }
                if (found >= 0) {
                    removed[i] = true
                    removed[found] = true
                    ranges += Triple(i + 1, found, Style.Mono)
                    i = found + 1
                } else {
                    i++
                }
                continue
            }
            if (c in MARKERS) {
                val at = stack.indexOfLast { it.ch == c }
                if (at >= 0 && canClose(i, 1) && i > stack[at].at + 1) {
                    ranges += Triple(stack[at].at + 1, i, styleOf(c))
                    removed[stack[at].at] = true
                    removed[i] = true
                    // Anything opened inside and not closed is shown as typed.
                    while (stack.size > at) stack.removeAt(stack.size - 1)
                } else if (canOpen(i, 1)) {
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

        val styles = Array(plain.length) { HashSet<Style>(0) }
        for ((s, e, st) in ranges) for (k in map[s] until map[e]) styles[k] += st
        val spans = ArrayList<Span>()
        var k = 0
        while (k < styles.size) {
            if (styles[k].isEmpty()) { k++; continue }
            var e = k + 1
            while (e < styles.size && styles[e] == styles[k]) e++
            spans += Span(k, e, styles[k].toSet())
            k = e
        }

        val mono = ranges.filter { it.third == Style.Mono || it.third == Style.Block }
        val links = ArrayList<Link>()
        for (l in rawLinks) {
            if (mono.any { l.range.first >= it.first && l.range.first < it.second }) continue
            val kept = l.range.filter { !removed[it] }
            if (kept.isEmpty()) continue
            val body = kept.joinToString("") { text[it].toString() }
            val url = if (kept.size == l.range.count()) l.url else LinkFinder.find(body).firstOrNull()?.url ?: body
            links += Link(map[l.range.first], map[l.range.last + 1], url)
        }
        return Result(plain.toString(), spans, links)
    }

    private class Block(val contentStart: Int, val contentEnd: Int, val after: Int)

    /** A block opens at start or after white space, closes before white space or the end; one newline each side is part of the fence. */
    private fun findBlock(text: String, at: Int): Block? {
        if (at > 0 && !isSpace(text.codePointBefore(at))) return null
        var contentStart = at + 3
        if (contentStart < text.length && text[contentStart] == '\n') contentStart++
        var j = text.indexOf("```", at + 3)
        while (j >= 0) {
            val after = j + 3
            if (after >= text.length || isSpace(text.codePointAt(after))) {
                var contentEnd = j
                if (contentEnd > contentStart && text[contentEnd - 1] == '\n') contentEnd--
                if (contentEnd > contentStart) return Block(contentStart, contentEnd, after)
                return null
            }
            j = text.indexOf("```", j + 1)
        }
        return null
    }
}
