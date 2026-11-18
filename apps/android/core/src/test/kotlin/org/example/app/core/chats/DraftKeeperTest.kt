package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals

class DraftKeeperTest {
    @Test fun setThenGet() {
        val d = DraftKeeper(); d.set("hello", "c1")
        assertEquals("hello", d.draft("c1"))
    }

    @Test fun chatsAreIndependent() {
        val d = DraftKeeper(); d.set("hello", "c1")
        assertEquals("", d.draft("c2"))
    }

    @Test fun whitespaceOnlyIsDropped() {
        val d = DraftKeeper(); d.set("hello", "c1"); d.set("   ", "c1")
        assertEquals("", d.draft("c1"))
    }
}
