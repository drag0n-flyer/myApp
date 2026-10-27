package org.example.app.messaging.content

import kotlin.test.Test
import kotlin.test.assertEquals

class TextLimitTest {
    @Test fun shortTextUnchanged() = assertEquals("hello", TextLimit.truncate("hello"))

    @Test fun plainTextCutAt4096() {
        val r = TextLimit.truncate("a".repeat(5000))
        assertEquals(4096, r.length)
    }

    @Test fun surrogatePairsAreNotSplit() {
        val s = "🙂".repeat(4100) // 4100 emoji, 8200 UTF-16 units
        val r = TextLimit.truncate(s)
        assertEquals("🙂".repeat(4096), r)
    }

    @Test fun combiningMarkStaysWithItsBase() {
        val e = "é" // one grapheme, two code points
        assertEquals(e.repeat(10), TextLimit.truncate(e.repeat(20), 10))
        assertEquals("ab" + e, TextLimit.truncate("ab" + e + "zz", 3))
    }

    @Test fun zwjSequenceStaysWhole() {
        val family = "👨‍👩‍👧" // man, woman, girl
        assertEquals("x" + family, TextLimit.truncate("x" + family + family, 2))
        assertEquals(family.repeat(4096), TextLimit.truncate(family.repeat(5000)))
    }

    @Test fun exactlyAtLimitIsKept() {
        val s = "é".repeat(4096)
        assertEquals(s, TextLimit.truncate(s))
    }
}
