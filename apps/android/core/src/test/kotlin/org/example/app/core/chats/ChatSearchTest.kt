package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatSearchTest {
    private fun hits(n: Int) = (0 until n).map { MessageHit("c", "m$it", "t$it") }

    @Test fun noResultsHasNoCurrent() {
        assertNull(ChatSearch.step(emptyList(), null, ChatSearch.Direction.Next))
        assertNull(ChatSearch.step(emptyList(), 0, ChatSearch.Direction.Previous))
    }

    @Test fun firstMoveFromNothingNextIsTheNewestAndPreviousTheOldest() {
        assertEquals(0, ChatSearch.step(hits(3), null, ChatSearch.Direction.Next))
        assertEquals(2, ChatSearch.step(hits(3), null, ChatSearch.Direction.Previous))
    }

    @Test fun nextGoesToOlderResults() = assertEquals(1, ChatSearch.step(hits(3), 0, ChatSearch.Direction.Next))
    @Test fun previousGoesToNewerResults() = assertEquals(1, ChatSearch.step(hits(3), 2, ChatSearch.Direction.Previous))
    @Test fun nextWrapsFromTheOldestToTheNewest() = assertEquals(0, ChatSearch.step(hits(3), 2, ChatSearch.Direction.Next))
    @Test fun previousWrapsFromTheNewestToTheOldest() = assertEquals(2, ChatSearch.step(hits(3), 0, ChatSearch.Direction.Previous))
    @Test fun oneResultStaysPut() {
        assertEquals(0, ChatSearch.step(hits(1), 0, ChatSearch.Direction.Next))
        assertEquals(0, ChatSearch.step(hits(1), 0, ChatSearch.Direction.Previous))
    }
    @Test fun aStaleIndexIsClamped() = assertEquals(0, ChatSearch.step(hits(2), 7, ChatSearch.Direction.Next))
    @Test fun findsTheSelectedMessageInNewResults() {
        assertEquals(2, ChatSearch.indexOf(hits(4), "m2"))
        assertNull(ChatSearch.indexOf(hits(4), "gone"))
    }
}
