package org.example.app.core.people

import kotlin.test.Test
import kotlin.test.assertEquals

/** One name rule for the chat list, titles, system lines and notifications (ruling C): nickname, profile name, lookup name. */
class ContactDisplayTest {
    @Test fun nicknameThenProfileNameThenLookupName() {
        assertEquals("Mum", ContactDisplay.name("Mum", "Ann", "Ana", "ana"))
        assertEquals("Ann", ContactDisplay.name(null, "Ann", "Ana", "ana"))
        assertEquals("Ana", ContactDisplay.name(null, null, "Ana", "ana"))
    }

    @Test fun anEmptyOrInvisibleNameFallsThrough() {
        assertEquals("Ann", ContactDisplay.name("  ", "Ann", "Ana", "ana"))
        assertEquals("Ana", ContactDisplay.name(null, "‮​", "Ana", "ana"))
        assertEquals("ana", ContactDisplay.name(null, null, "", "ana"))
    }

    @Test fun everyNameIsCleanedForDisplay() {
        assertEquals("Ann", ContactDisplay.name(null, "A‮nn", "Ana", "ana"))
    }
}
