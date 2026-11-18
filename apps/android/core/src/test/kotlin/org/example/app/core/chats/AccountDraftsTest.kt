package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AccountDraftsTest {
    @Test fun theSameAccountKeepsItsDrafts() {
        val a = AccountDrafts()
        a.keeperFor("acc-1").set("hello", "chat")
        assertSame(a.keeperFor("acc-1"), a.keeperFor("acc-1"))
        assertEquals("hello", a.keeperFor("acc-1").draft("chat"))
    }

    @Test fun anotherAccountSigningInLaterStartsWithNone() {
        val a = AccountDrafts()
        a.keeperFor("acc-1").set("private", "chat")
        assertEquals("", a.keeperFor("acc-2").draft("chat"))
        // And the first account's drafts are gone too: they are never shown to anyone else.
        assertEquals("", a.keeperFor("acc-1").draft("chat"))
    }
}
