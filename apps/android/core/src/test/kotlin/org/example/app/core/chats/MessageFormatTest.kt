package org.example.app.core.chats

import org.example.app.core.chats.MessageFormat.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same table runs on iPhone and Mac. Ranges are UTF-16 units of `MessageFormat.plain(text)`, end exclusive
 * (`Span(start, end, styles)`), as the platform text type counts them.
 */
class MessageFormatTest {
    private fun span(s: Int, e: Int, vararg st: Style) = MessageFormat.Span(s, e, st.toSet())

    private fun check(input: String, plain: String, vararg spans: MessageFormat.Span) {
        assertEquals(plain, MessageFormat.plain(input), "plain of <$input>")
        assertEquals(spans.toList(), MessageFormat.parse(input), "spans of <$input>")
    }

    @Test fun bold() = check("*a*", "a", span(0, 1, Style.Bold))
    @Test fun italic() = check("_a_", "a", span(0, 1, Style.Italic))
    @Test fun strike() = check("~a~", "a", span(0, 1, Style.Strike))
    @Test fun monoParsesNothingInside() = check("`a*b*`", "a*b*", span(0, 4, Style.Mono))
    @Test fun nested() = check(
        "*a _b_ c*", "a b c",
        span(0, 5, Style.Bold), span(2, 3, Style.Italic),
    )
    @Test fun wordsAroundAndInside() = check("x *a* y", "x a y", span(2, 3, Style.Bold))

    @Test fun digitsAroundStarAreNotFormatted() = check("2*3*4", "2*3*4")
    @Test fun snakeCaseIsNotFormatted() = check("snake_case_name", "snake_case_name")
    @Test fun spacedStarsAreNotFormatted() = check("a * b * c", "a * b * c")
    @Test fun unclosedIsNotFormatted() = check("*a", "*a")
    @Test fun spanDoesNotCrossNewline() = check("*a\nb*", "*a\nb*")
    @Test fun emptyPairIsNotFormatted() = check("**", "**")
    @Test fun doubledMarkerIsNeverRead() {
        check("a ** b", "a ** b")
        check("**a**", "**a**")
        check("a ``` b", "a ``` b")
        check("```", "```")
        check("```a```", "```a```")
        check("``` a ```", "``` a ```")
    }
    @Test fun spaceInsideMarkersIsNotFormatted() = check("* a *", "* a *")

    @Test fun punctuationBeforeAndAfterIsFine() = check("(*a*)", "(a)", span(1, 2, Style.Bold))
    @Test fun commaAfterIsFine() = check("*a*,", "a,", span(0, 1, Style.Bold))

    @Test fun blockKeepsInnerTextVerbatim() = check("```\n*a*\n_b_\n```", "*a*\n_b_", span(0, 7, Style.Mono, Style.Block))
    @Test fun blockBetweenLines() = check("x\n```\n*a*\n_b_\n```\ny", "x\n*a*\n_b_\ny", span(2, 9, Style.Mono, Style.Block))
    @Test fun unclosedBlockIsNotFormatted() = check("```\na", "```\na")
    @Test fun emptyBlockIsNotFormatted() {
        check("```\n```", "```\n```")
        check("```\n\n```", "```\n\n```")
    }
    @Test fun fenceWithTextOnItsLineIsNotABlock() = check("```a\nb\n```", "```a\nb\n```")

    @Test fun linkStaysWhole() = check("*a* https://x.example/_b_", "a https://x.example/_b_", span(0, 1, Style.Bold))
    @Test fun linksAreFoundInPlainCoordinates() =
        assertEquals(listOf(MessageFormat.Link(2, 23, "https://x.example/_b_")), MessageFormat.links("*a* https://x.example/_b_"))
    @Test fun linkInsideBoldLosesTheClosingMarker() {
        assertEquals(listOf(MessageFormat.Link(0, 17, "https://x.example")), MessageFormat.links("*https://x.example*"))
        assertEquals("https://x.example", MessageFormat.plain("*https://x.example*"))
    }
    @Test fun noLinkInsideMono() = assertEquals(emptyList(), MessageFormat.links("`https://x.example`"))
    @Test fun linkOutsideMonoStays() = assertEquals(1, MessageFormat.links("`a` https://x.example").size)

    @Test fun emojiRangesAreUtf16() = check("*😀* b", "😀 b", span(0, 2, Style.Bold))
    @Test fun emojiIsNotABoundary() = check("😀*a*", "😀*a*")
    @Test fun precomposedLetterBeforeSpace() = check("é _x_", "é x", span(2, 3, Style.Italic))
    @Test fun boldRunWithFamilyEmojiAndCombiningAccent() = check("*👨‍👩‍👧 e\u0301*", "👨‍👩‍👧 e\u0301", span(0, 11, Style.Bold))
    @Test fun tildeAndBacktickAreBoundaries() {
        check("~*a*", "~a", span(1, 2, Style.Bold))
        check("*a* `b`", "a b", span(0, 1, Style.Bold), span(2, 3, Style.Mono))
    }
    @Test fun linkWordWithClosingMarkerAfterIt() =
        check("*see https://x.example*", "see https://x.example", span(0, 21, Style.Bold))
    @Test fun uppercaseSchemeIsAlsoProtected() = check("*a* HTTPS://X.example/_b_", "a HTTPS://X.example/_b_", span(0, 1, Style.Bold))
    @Test fun combinedCharacters() = check("x *é* y", "x é y", span(2, 4, Style.Bold))
    @Test fun familyEmojiJoined() = check("_👨‍👩‍👧_", "👨‍👩‍👧", span(0, 8, Style.Italic))

    // Rows agreed with the Swift side.
    @Test fun innermostCloserWins() = check("*a *b* c*", "a b c", span(0, 5, Style.Bold), span(2, 3, Style.Bold))
    @Test fun blockWithOnlyBlankLinesIsNotABlock() = check("```\n \n```", "```\n \n```")
    @Test fun starRunAtTheEndIsTyped() = check("*a**", "*a**")
    @Test fun doubledTildeInsideStrike() = check("~a~~b~", "a~~b", span(0, 4, Style.Strike))
    @Test fun doubledBacktickInsideMono() = check("`a``b`", "a``b", span(0, 4, Style.Mono))
    @Test fun crLfEndsTheLine() = check("*a\r\nb*", "*a\r\nb*")
    @Test fun monoInsideBoldHidesItsStar() = check("*a `b* c` d*", "a b* c d", span(0, 8, Style.Bold), span(2, 6, Style.Mono))
    @Test fun markerBeforeACombiningMarkIsNotAMarker() {
        check("*\u0301a* x", "*\u0301a* x")
        check("*a*\u0301 x", "*a*\u0301 x")
    }
    @Test fun markerBeforeAZeroWidthJoinerIsNotAMarker() {
        check("*\u200Da* x", "*\u200Da* x")
        check("*a*\u200D x", "*a*\u200D x")
    }

    // Hostile input: bounded work.
    @Test fun overTheLimitTheTextIsLeftAsTypedButLinksAreStillFound() {
        val long = "*a* https://x.example " + "x".repeat(5_000)
        assertEquals(long, MessageFormat.plain(long))
        assertEquals(emptyList(), MessageFormat.parse(long))
        assertEquals(listOf(MessageFormat.Link(4, 21, "https://x.example")), MessageFormat.links(long))
    }
    @Test fun exactlyAtTheLimitStillFormats() {
        val text = "*a* " + "x".repeat(4_092)
        assertEquals(listOf(span(0, 1, Style.Bold)), MessageFormat.parse(text))
    }
    @Test fun theLimitCountsGraphemesNotUnits() {
        // 3,000 letters with an accent are 6,000 UTF-16 units but 3,000 graphemes: still formatted.
        val text = "*a* " + "e\u0301".repeat(3_000)
        assertEquals(listOf(span(0, 1, Style.Bold)), MessageFormat.parse(text))
    }
    @Test fun oneScanPerLayout() {
        MessageFormat.analyzeCalls = 0
        MessageFormat.layout("*a* https://x.example")
        assertEquals(1, MessageFormat.analyzeCalls)
    }
    @Test fun backtickScanIsLinear() {
        val text = " `a".repeat(1_300)
        MessageFormat.monoScanSteps = 0
        assertEquals(text, MessageFormat.plain(text))
        assertTrue(MessageFormat.monoScanSteps <= 2 * text.length, "steps ${MessageFormat.monoScanSteps}")
    }
    @Test fun sixtyFourKilobytesOfBackticksIsFast() {
        val text = " `a".repeat(22_000)
        val t0 = System.nanoTime()
        assertEquals(text, MessageFormat.plain(text))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 200)
    }
    @Test fun manyClosingBracketsAfterALinkAreFast() {
        val text = "https://x.example" + ")".repeat(30_000)
        val t0 = System.nanoTime()
        assertEquals(listOf(MessageFormat.Link(0, 17, "https://x.example")), MessageFormat.links(text))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 200)
    }

    @Test fun plainTextIsReturnedAsTyped() {
        val typed = "hello world, 2*3*4"
        assertEquals(typed, MessageFormat.plain(typed))
        assertEquals(emptyList(), MessageFormat.parse(typed))
    }
}
