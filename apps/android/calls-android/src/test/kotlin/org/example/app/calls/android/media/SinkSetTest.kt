package org.example.app.calls.android.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SinkSetTest {
    @Test
    fun closingHandsEverySinkToTheTrackOnceAndRefusesNewOnes() {
        val set = SinkSet<String>()
        assertTrue(set.add("a"))
        assertTrue(set.add("b"))
        val removed = mutableListOf<String>()
        set.closeAll { removed += it }
        set.closeAll { removed += it } // a second close does nothing
        assertEquals(listOf("a", "b"), removed)
        assertEquals(0, set.size)
        assertFalse(set.add("c"))
    }

    @Test
    fun detachingAfterCloseIsANoOp() {
        val set = SinkSet<String>()
        set.add("a")
        set.closeAll { }
        assertFalse(set.remove("a")) // the track is disposed: nothing to remove from
    }

    @Test
    fun aSinkIsRemovedFromTheTrackOnlyWhenItWasAttached() {
        val set = SinkSet<String>()
        set.add("a")
        assertTrue(set.remove("a"))
        assertFalse(set.remove("a"))
    }

    @Test
    fun twoConsecutiveCallsKeepTheirSinksApart() {
        val first = SinkSet<String>()
        first.add("renderer1")
        val removedFirst = mutableListOf<String>()
        first.closeAll { removedFirst += it }
        val second = SinkSet<String>()
        assertTrue(second.add("renderer2"))
        assertEquals(listOf("renderer1"), removedFirst)
        assertEquals(1, second.size)
    }
}
