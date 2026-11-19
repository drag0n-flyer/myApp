package org.example.app.core.chats

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PollRulesTest {
    private val options = listOf(PollOption("a", "Pizza"), PollOption("b", "Sushi"), PollOption("c", "Tacos"))

    @Test fun limitsOnQuestionAndOptions() {
        assertTrue(PollRules.valid("Lunch?", options))
        assertFalse(PollRules.valid("", options))
        assertFalse(PollRules.valid("   ", options))
        assertTrue(PollRules.valid("q".repeat(300), options))
        assertFalse(PollRules.valid("q".repeat(301), options))
        assertFalse(PollRules.valid("Lunch?", options.take(1)))
        assertTrue(PollRules.valid("Lunch?", (1..12).map { PollOption("o$it", "x$it") }))
        assertFalse(PollRules.valid("Lunch?", (1..13).map { PollOption("o$it", "x$it") }))
        assertFalse(PollRules.valid("Lunch?", listOf(PollOption("a", "x"), PollOption("a", "y"))))
        assertFalse(PollRules.valid("Lunch?", listOf(PollOption("", "x"), PollOption("b", "y"))))
        assertFalse(PollRules.valid("Lunch?", listOf(PollOption("123456789", "x"), PollOption("b", "y"))))
        assertFalse(PollRules.valid("Lunch?", listOf(PollOption("a", "x".repeat(101)), PollOption("b", "y"))))
        assertTrue(PollRules.valid("Lunch?", listOf(PollOption("a", "x".repeat(100)), PollOption("b", "y"))))
        // Lengths count characters, not UTF-16 units.
        assertTrue(PollRules.valid("Lunch?", listOf(PollOption("a", "😀".repeat(100)), PollOption("b", "y"))))
    }

    @Test fun buildTrimsDropsBlanksAndNumbersTheOptions() {
        val (q, opts) = PollRules.build("  Lunch? ", listOf(" Pizza ", "", "Sushi", "  "))!!
        assertEquals("Lunch?", q)
        assertEquals(listOf(PollOption("o1", "Pizza"), PollOption("o2", "Sushi")), opts)
        assertNull(PollRules.build("Lunch?", listOf("only one", " ")))
        assertNull(PollRules.build(" ", listOf("a", "b")))
    }

    @Test fun tallyCountsVotersAndMyVote() {
        val t = PollRules.tally(options, multi = true, votes = mapOf("me" to listOf("a", "b"), "bob" to listOf("b")), me = "me")
        assertEquals(mapOf("a" to 1, "b" to 2, "c" to 0), t.counts)
        assertEquals(listOf("me", "bob"), t.voters["b"])
        assertEquals(emptyList(), t.voters["c"])
        assertEquals(setOf("a", "b"), t.mine)
        assertEquals(2, t.voterCount)
    }

    @Test fun invalidIdsAreIgnoredAndAnEmptyVoteIsNoVote() {
        val t = PollRules.tally(options, true, mapOf("bob" to listOf("zzz", "a", "a"), "eve" to listOf("zzz"), "al" to emptyList()), "me")
        assertEquals(mapOf("a" to 1, "b" to 0, "c" to 0), t.counts)
        assertEquals(1, t.voterCount)
        assertEquals(emptySet(), t.mine)
    }

    @Test fun singleChoiceKeepsTheFirstValidId() {
        val t = PollRules.tally(options, multi = false, votes = mapOf("bob" to listOf("zzz", "c", "a")), me = "me")
        assertEquals(mapOf("a" to 0, "b" to 0, "c" to 1), t.counts)
    }

    @Test fun toggleSwitchesOrTakesBack() {
        assertEquals(listOf("b"), PollRules.toggle(setOf("a"), "b", multi = false))
        assertEquals(emptyList(), PollRules.toggle(setOf("a"), "a", multi = false))
        assertEquals(listOf("a", "b"), PollRules.toggle(setOf("a"), "b", multi = true))
        assertEquals(listOf("b"), PollRules.toggle(setOf("a", "b"), "a", multi = true))
    }

    @Test fun aPollOffersOnlyReplyReactAndDelete() {
        val m = Message("p", "a", Instant.EPOCH, MessageContent.Poll("Lunch?", options, false), DeliveryStatus.Sent, null, emptyList())
        assertEquals(listOf(MessageAction.Reply, MessageAction.React, MessageAction.Delete), MessageActions.available(m, isMine = true))
        assertEquals(listOf(MessageAction.Reply, MessageAction.React), MessageActions.available(m, isMine = false))
        assertEquals(MessagePreview.Resource("preview_poll", listOf("Lunch?")), m.preview)
    }
}
