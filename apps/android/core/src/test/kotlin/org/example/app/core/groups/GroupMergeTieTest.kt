package org.example.app.core.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupMergeTieTest {
    private val a = byteArrayOf(1, 2, 3)
    private val b = byteArrayOf(4, 5, 6)

    @Test
    fun theGreaterNameWinsByCodePointsNotByUtf16Units() {
        assertTrue(GroupMerge.beats("Alpha", null, "Beta", null))
        assertFalse(GroupMerge.beats("Beta", null, "Alpha", null))
        // U+FF5E (one unit) against U+1F600 (a surrogate pair): by code point the emoji is greater; by UTF-16 it would be less.
        assertTrue(GroupMerge.beats("～", null, "😀", null))
        assertFalse(GroupMerge.beats("😀", null, "～", null))
        assertTrue(GroupMerge.beats("Ab", null, "Abc", null), "a prefix is less")
    }

    @Test
    fun withEqualNamesThePhotoHashDecidesAndNoPhotoIsLeast() {
        val ha = java.security.MessageDigest.getInstance("SHA-256").digest(a).joinToString("") { "%02x".format(it) }
        val hb = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        val greater = if (ha > hb) a else b
        val lesser = if (ha > hb) b else a
        assertTrue(GroupMerge.beats("N", lesser, "N", greater))
        assertFalse(GroupMerge.beats("N", greater, "N", lesser))
        assertTrue(GroupMerge.beats("N", null, "N", a))
        assertFalse(GroupMerge.beats("N", a, "N", null))
        assertFalse(GroupMerge.beats("N", a, "N", a.copyOf()), "identical: nothing changes")
        assertFalse(GroupMerge.beats("N", null, "N", null))
        assertEquals(true, GroupMerge.beats("A", greater, "B", null), "the name decides before the photo")
    }
}
