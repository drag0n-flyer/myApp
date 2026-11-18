package org.example.app.core.chats

import org.example.app.core.chats.MessageFormat.Style
import kotlin.test.Test
import kotlin.test.assertEquals

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
        span(0, 2, Style.Bold), span(2, 3, Style.Bold, Style.Italic), span(3, 5, Style.Bold),
    )
    @Test fun wordsAroundAndInside() = check("x *a* y", "x a y", span(2, 3, Style.Bold))

    @Test fun digitsAroundStarAreNotFormatted() = check("2*3*4", "2*3*4")
    @Test fun snakeCaseIsNotFormatted() = check("snake_case_name", "snake_case_name")
    @Test fun spacedStarsAreNotFormatted() = check("a * b * c", "a * b * c")
    @Test fun unclosedIsNotFormatted() = check("*a", "*a")
    @Test fun spanDoesNotCrossNewline() = check("*a\nb*", "*a\nb*")
    @Test fun emptyPairIsNotFormatted() = check("**", "**")
    @Test fun spaceInsideMarkersIsNotFormatted() = check("* a *", "* a *")

    @Test fun punctuationBeforeAndAfterIsFine() = check("(*a*)", "(a)", span(1, 2, Style.Bold))
    @Test fun commaAfterIsFine() = check("*a*,", "a,", span(0, 1, Style.Bold))

    @Test fun blockKeepsInnerTextVerbatim() = check("```\n*a*\n_b_\n```", "*a*\n_b_", span(0, 7, Style.Block))
    @Test fun blockBetweenLines() = check("x\n```\ny\n```\nz", "x\ny\nz", span(2, 3, Style.Block))
    @Test fun unclosedBlockIsNotFormatted() = check("```\na", "```\na")

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
    @Test fun emojiBeforeMarkerCountsAsPunctuation() = check("😀*a*", "😀a", span(2, 3, Style.Bold))
    @Test fun combinedCharacters() = check("x *é* y", "x é y", span(2, 4, Style.Bold))
    @Test fun familyEmojiJoined() = check("_👨‍👩‍👧_", "👨‍👩‍👧", span(0, 8, Style.Italic))

    @Test fun plainTextIsReturnedAsTyped() {
        val typed = "hello world, 2*3*4"
        assertEquals(typed, MessageFormat.plain(typed))
        assertEquals(emptyList(), MessageFormat.parse(typed))
    }
}
