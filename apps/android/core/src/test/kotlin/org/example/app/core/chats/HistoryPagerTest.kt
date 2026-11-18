package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryPagerTest {
    @Test fun asksWithinFiveOfTheOldest() {
        val p = HistoryPager()
        assertTrue(p.shouldLoad(4))
        assertFalse(p.shouldLoad(5))
        assertTrue(p.shouldLoad(0))
    }

    @Test fun doesNotAskWhileLoading() {
        val p = HistoryPager()
        p.begin()
        assertFalse(p.shouldLoad(0))
        assertTrue(p.isLoading)
        p.finish(50)
        assertFalse(p.isLoading)
        assertTrue(p.shouldLoad(0))
    }

    @Test fun aShortPageEndsHistory() {
        val p = HistoryPager()
        p.begin(); p.finish(49)
        assertTrue(p.reachedStart)
        assertFalse(p.shouldLoad(0))
    }

    @Test fun aFullPageDoesNot() {
        val p = HistoryPager()
        p.begin(); p.finish(50)
        assertFalse(p.reachedStart)
    }

    @Test fun beginIsRefusedWhenBusyOrFinished() {
        val p = HistoryPager()
        assertTrue(p.begin())
        assertFalse(p.begin())
        p.finish(0)
        assertFalse(p.begin())
    }

    @Test fun pageSizeIs50() = assertEquals(50, HistoryPager.PAGE_SIZE)
}
