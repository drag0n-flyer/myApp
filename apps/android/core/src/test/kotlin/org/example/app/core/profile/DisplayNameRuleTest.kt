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
}
