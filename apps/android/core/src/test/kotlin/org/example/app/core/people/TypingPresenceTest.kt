package org.example.app.core.people

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypingPresenceTest {
    private fun step(s: TypingSender.State, e: TypingSender.Event, at: Long) = TypingSender.step(s, e, at)
    private val typed = TypingSender.Event.TextChanged(isEmpty = false)
    private val tick = TypingSender.Event.Tick

    @Test fun firstKeystrokeSendsOn() {
        val (state, send) = step(TypingSender.State(), typed, 1_000)
        assertEquals(true, send)
        assertTrue(state.typing)
    }

    @Test fun furtherKeystrokesInsideEightSecondsSendNothing() {
        var s = step(TypingSender.State(), typed, 0).state
        s = step(s, typed, 3_000).state
        assertNull(step(s, typed, 3_001).send)
    }

    @Test fun repeatsAtEightSecondsNotBefore() {
        var s = step(TypingSender.State(), typed, 0).state
        s = step(s, typed, 3_000).state
        s = step(s, typed, 6_000).state
        assertNull(step(s, tick, 7_999).send)
        val (after, send) = step(s, tick, 8_000)
        assertEquals(true, send)
        assertNull(step(after, tick, 8_001).send)
        // the next repeat is eight seconds after that one
        var keep = step(after, typed, 11_000).state
        keep = step(keep, typed, 14_000).state
        assertNull(step(keep, tick, 15_999).send)
        assertEquals(true, step(keep, tick, 16_000).send)
    }

    @Test fun keystrokeAtEightSecondsAlsoRepeats() {
        var s = step(TypingSender.State(), typed, 0).state
        s = step(s, typed, 4_000).state
        assertNull(step(s, typed, 7_999).send)
        assertEquals(true, step(s, typed, 8_000).send)
    }

    @Test fun idleFiveSecondsSendsOffNotBefore() {
        val s = step(TypingSender.State(), typed, 0).state
        assertNull(step(s, tick, 4_999).send)
        val (after, send) = step(s, tick, 5_000)
        assertEquals(false, send)
        assertFalse(after.typing)
        assertNull(step(after, tick, 6_000).send) // already off
    }

    @Test fun keystrokeRestartsTheIdleClock() {
        var s = step(TypingSender.State(), typed, 0).state
        s = step(s, typed, 4_000).state
        assertEquals(true, step(s, tick, 8_000).send) // a repeat is due, the idle off is not
        assertNull(step(s, tick, 7_999).send)
        val after = step(s, tick, 8_000).state
        assertNull(step(after, tick, 8_999).send)
        assertEquals(false, step(after, tick, 9_000).send)
    }

    @Test fun emptyTextAndSentSendOffOnlyWhileTyping() {
        val s = step(TypingSender.State(), typed, 0).state
        assertEquals(false, step(s, TypingSender.Event.TextChanged(isEmpty = true), 100).send)
        assertEquals(false, step(s, TypingSender.Event.Sent, 100).send)
        val idle = TypingSender.State()
        assertNull(step(idle, TypingSender.Event.TextChanged(isEmpty = true), 100).send)
        assertNull(step(idle, TypingSender.Event.Sent, 100).send)
    }

    @Test fun typingStartsAgainAfterOff() {
        var s = step(TypingSender.State(), typed, 0).state
        s = step(s, TypingSender.Event.Sent, 10).state
        assertEquals(true, step(s, typed, 20).send)
    }

    @Test fun shownForTenSecondsAfterTheLastOn() {
        assertFalse(TypingShown.isTyping(null, 5_000))
        assertTrue(TypingShown.isTyping(1_000, 1_000))
        assertTrue(TypingShown.isTyping(1_000, 10_999))
        assertFalse(TypingShown.isTyping(1_000, 11_000))
    }

    @Test fun onlineForNinetySecondsAfterTheLastOnline() {
        assertEquals(PresenceLine.Result.Online, PresenceLine.text(0, null, 89_999))
        assertEquals(PresenceLine.Result.None, PresenceLine.text(0, null, 90_000))
    }

    @Test fun afterNinetySecondsItIsLastSeenAtTheWitnessedTime() {
        assertEquals(PresenceLine.Result.LastSeen(60_000), PresenceLine.text(0, 60_000, 90_000))
    }

    @Test fun awayEndsOnlineAtOnce() {
        // The model clears the last `online` on `away` and records the witness time.
        assertEquals(PresenceLine.Result.LastSeen(5_000), PresenceLine.text(null, 5_000, 5_001))
    }

    @Test fun nothingWitnessedShowsNothing() {
        assertEquals(PresenceLine.Result.None, PresenceLine.text(null, null, 1_000))
    }

    @Test fun aClockThatWentBackIsNotTypingNorOnline() {
        assertFalse(TypingShown.isTyping(1_000, 999))
        assertTrue(TypingShown.isTyping(1_000, 1_000))
        assertEquals(PresenceLine.Result.None, PresenceLine.text(1_000, null, 999))
        assertEquals(PresenceLine.Result.LastSeen(500), PresenceLine.text(1_000, 500, 999))
        assertEquals(PresenceLine.Result.Online, PresenceLine.text(1_000, null, 1_000))
    }
}
