package org.example.app.core.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayNameRuleTest {
    @Test fun trims() = assertEquals("Asha", DisplayNameRule.clean("  Asha  "))

    @Test fun keepsInnerSpacesAndUnicode() {
        assertEquals("Ana María", DisplayNameRule.clean("Ana María"))
        assertEquals("李小龍", DisplayNameRule.clean("李小龍"))
    }

    @Test fun refusesEmptyAndOnlySpaces() {
        assertNull(DisplayNameRule.clean(""))
        assertNull(DisplayNameRule.clean("    "))
        assertNull(DisplayNameRule.clean("  "))
    }

    @Test fun limitIsFortyCodePointsNotUtf16Units() {
        assertEquals("a".repeat(40), DisplayNameRule.clean("a".repeat(40)))
        assertNull(DisplayNameRule.clean("a".repeat(41)))
        val faces = "😀".repeat(40) // 40 code points, 80 UTF-16 units
        assertEquals(faces, DisplayNameRule.clean(faces))
        assertNull(DisplayNameRule.clean(faces + "a"))
        assertNull(DisplayNameRule.clean("  " + "a".repeat(41) + "  "))
        assertEquals("a".repeat(40), DisplayNameRule.clean("  " + "a".repeat(40) + "  "))
    }

    @Test fun refusesLineBreaksAndControlCharacters() {
        for (c in listOf("\n", "\r", "\u0085", " ", " ", "\t", "\u0000", "\u001B", "\u007F")) {
            assertNull(DisplayNameRule.clean("A${c}B"), "U+%04X inside".format(c.codePointAt(0)))
        }
        assertNull(DisplayNameRule.clean("Ana\nMaría"))
    }

    @Test fun refusesDirectionAndFormatControls() {
        val bad = (0x202A..0x202E) + (0x2066..0x2069) + listOf(0x200E, 0x200F, 0x061C)
        for (cp in bad) {
            val c = String(Character.toChars(cp))
            assertNull(DisplayNameRule.clean("A${c}B"), "U+%04X inside".format(cp))
            assertNull(DisplayNameRule.clean("${c}AB"), "U+%04X leading".format(cp))
        }
        assertNull(DisplayNameRule.clean("Asha‮"))
    }

    @Test fun refusesUnpairedSurrogates() = assertNull(DisplayNameRule.clean("A\uD83DB"))

    @Test fun refusesNamesWithNoVisibleCharacter() {
        for (cp in listOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800, 0x034F)) {
            val c = String(Character.toChars(cp))
            assertNull(DisplayNameRule.clean(c), "U+%04X alone".format(cp))
            assertNull(DisplayNameRule.clean(" $c$c "), "U+%04X repeated".format(cp))
        }
    }

    @Test fun acceptsLettersDigitsSymbolsAndPunctuationAndKeepsInvisibleOnesBesideThem() {
        assertEquals("A", DisplayNameRule.clean("A"))
        assertEquals("7", DisplayNameRule.clean("7"))
        assertEquals("!", DisplayNameRule.clean("!"))
        assertEquals("\u00A9", DisplayNameRule.clean("\u00A9"))
        assertEquals("\uD83D\uDE00", DisplayNameRule.clean("\uD83D\uDE00"))
        assertEquals(null, DisplayNameRule.clean("A\u200BB")) // hidden format characters are refused inside a name
        assertEquals("A\u200DB", DisplayNameRule.clean("A\u200DB"))
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // joined emoji keep their joiners
        assertEquals(family, DisplayNameRule.clean(family))
    }

    @Test fun unassignedCodePointsAreNotRefused() {
        // U+0378 is unassigned in current Unicode; whether a phone knows a newer emoji must not decide.
        assertEquals("A\u0378B", DisplayNameRule.clean("A\u0378B"))
    }

    @Test fun forDisplayRemovesDirectionAndBreakControls() {
        assertEquals("Anaxyz", DisplayNameRule.forDisplay("Ana\u202Exyz", "fb"))
        assertEquals("AB", DisplayNameRule.forDisplay("A\u2066\u2069\u200E\u200F\u061CB", "fb"))
        assertEquals("AB", DisplayNameRule.forDisplay("A\u2028\u2029\nB\u0000", "fb"))
        assertEquals("Asha", DisplayNameRule.forDisplay("  Asha  ", "fb"))
    }

    @Test fun forDisplayFallsBackWhenNothingVisibleRemains() {
        assertEquals("riya", DisplayNameRule.forDisplay("", "riya"))
        assertEquals("riya", DisplayNameRule.forDisplay("\u202E\u200B\u3164 ", "riya"))
        assertEquals("riya", DisplayNameRule.forDisplay("\n\u2028", "riya"))
    }

    @Test fun forDisplayLeavesANormalNameAlone() {
        assertEquals("Ana María \uD83D\uDE00 O\u2019Neil", DisplayNameRule.forDisplay("Ana María \uD83D\uDE00 O\u2019Neil", "fb"))
    }

    @Test fun forDisplayCutsToSixtyFourCodePoints() {
        val faces = "\uD83D\uDE00".repeat(70)
        assertEquals("\uD83D\uDE00".repeat(64), DisplayNameRule.forDisplay(faces, "fb"))
        assertEquals("a".repeat(64), DisplayNameRule.forDisplay("a".repeat(100), "fb"))
    }
}
