package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkPreviewParseTest {
    private fun parse(html: String, limit: Int = 524_288) = LinkPreviewParse.fromHTML(html.encodeToByteArray(), limit)

    @Test fun readsTheOpenGraphFields() {
        val p = parse(
            """<html><head><title>Plain</title>
            <meta property="og:title" content="The Title">
            <meta property="og:description" content="A description.">
            <meta property="og:image" content="https://cdn.example.org/i.jpg">
            </head><body>x</body></html>""",
        )!!
        assertEquals("The Title", p.title)
        assertEquals("A description.", p.desc)
        assertEquals("https://cdn.example.org/i.jpg", p.imageUrl)
    }

    @Test fun fallsBackToTheTitleElementAndHasNoDescriptionOrImage() {
        val p = parse("<html><head><title>  Just a\n   title </title></head></html>")!!
        assertEquals("Just a title", p.title)
        assertNull(p.desc)
        assertNull(p.imageUrl)
    }

    @Test fun attributeOrderQuotesCaseAndNameAttributeAreAllRead() {
        assertEquals("A", parse("<META CONTENT='A' PROPERTY='OG:TITLE'>")!!.title)
        assertEquals("B", parse("<meta name=\"og:title\" content=B>")!!.title)
        assertEquals("C", parse("<meta content = \"C\" property = \"og:title\" />")!!.title)
    }

    @Test fun anEmptyOpenGraphTitleFallsBackToTheTitleElement() {
        assertEquals("Fallback", parse("<meta property=\"og:title\" content=\"  \"><title>Fallback</title>")!!.title)
    }

    @Test fun theFirstOccurrenceWins() {
        assertEquals("one", parse("<meta property=\"og:title\" content=\"one\"><meta property=\"og:title\" content=\"two\">")!!.title)
    }

    @Test fun entitiesAreDecoded() {
        val p = parse("<meta property=\"og:title\" content=\"Tom &amp; Jerry &lt;3 &quot;q&quot; &#39;s&#x27; &#x1F600; &bogus;\">")!!
        assertEquals("Tom & Jerry <3 \"q\" 's' 😀 &bogus;", p.title)
    }

    @Test fun invalidNumericEntitiesAreDropped() {
        assertEquals("ab", parse("<title>a&#0;&#xD800;&#99999999;b</title>")!!.title)
    }

    @Test fun controlAndDirectionCharactersAreRemovedFromWhatIsShown() {
        assertEquals("abcde", parse("<meta property=\"og:title\" content=\"a‮b​c\u0007d⁦e\">")!!.title)
    }

    @Test fun lineAndParagraphSeparatorsBecomeASpace() {
        assertEquals("a b c", parse("<title>a b c</title>")!!.title)
    }

    @Test fun aTitleOf500CharactersIsCutTo200CodePoints() {
        val p = parse("<title>" + "x".repeat(500) + "</title>")!!
        assertEquals(200, p.title.length)
    }

    @Test fun lengthsCountCodePointsNotUtf16Units() {
        val emoji = "😀"
        val p = parse("<meta property=\"og:title\" content=\"" + emoji.repeat(250) + "\"><meta property=\"og:description\" content=\"" + emoji.repeat(400) + "\">")!!
        assertEquals(200, p.title.codePointCount(0, p.title.length))
        assertEquals(300, p.desc!!.codePointCount(0, p.desc!!.length))
        assertEquals(emoji.repeat(200), p.title)
    }

    @Test fun aDescriptionOf1000CharactersIsCutTo300() {
        val p = parse("<title>t</title><meta property=\"og:description\" content=\"" + "d".repeat(1_000) + "\">")!!
        assertEquals(300, p.desc!!.length)
    }

    @Test fun anOverlongImageUrlIsDropped() {
        val p = parse("<title>t</title><meta property=\"og:image\" content=\"https://a.example/" + "i".repeat(3_000) + "\">")!!
        assertNull(p.imageUrl)
    }

    @Test fun noTitleAtAllGivesNull() {
        assertNull(parse("<html><body>nothing</body></html>"))
        assertNull(parse(""))
        assertNull(parse("<title>   </title>"))
        assertNull(parse("<meta property=\"og:description\" content=\"only a description\">"))
    }

    @Test fun malformedHtmlNeverThrows() {
        val cases = listOf(
            "<", "<<<<", "<meta", "<meta property=\"og:title", "<meta property=\"og:title\" content=\"unterminated",
            "<meta =\"\" ==  property=og:title content>", "\u0000\u0001<title>\u0000</title>", "<title>&", "<title>&#", "<title>&#x",
        )
        for (html in cases) parse(html) // must not throw
        assertNull(parse("<!-- never closed <title>x</title>"))
        assertNull(parse("<script>never closed <title>x</title>"))
        assertNull(parse("<title>never closed"))
    }

    @Test fun invalidUtf8NeverThrows() {
        val bytes = "<title>ok ".encodeToByteArray() + byteArrayOf(0xFF.toByte(), 0xC3.toByte()) + "</title>".encodeToByteArray()
        assertTrue(LinkPreviewParse.fromHTML(bytes, 1_000)!!.title.startsWith("ok"))
    }

    @Test fun commentsScriptsAndStylesAreNotRead() {
        val p = parse("<!-- <title>no</title> --><script>var a='<title>no</title>'</script><style><title>no</title></style><title>yes</title>")!!
        assertEquals("yes", p.title)
    }

    @Test fun onlyTheFirstLimitBytesAreRead() {
        val html = "<title>early</title>" + " ".repeat(100) + "<meta property=\"og:description\" content=\"late\">"
        assertEquals("early", parse(html, limit = 60)!!.title)
        assertNull(parse(html, limit = 60)!!.desc)
        assertEquals("late", parse(html)!!.desc)
    }

    @Test fun hostileInputIsReadInLinearTime() {
        val started = System.nanoTime()
        parse("<!--".repeat(130_000))
        parse("<meta ".repeat(80_000))
        parse("<script>".repeat(60_000))
        parse("<title>".repeat(70_000))
        parse("<meta a=\"".repeat(50_000))
        parse("&".repeat(500_000))
        assertTrue((System.nanoTime() - started) < 5_000_000_000L, "took too long")
    }
}
