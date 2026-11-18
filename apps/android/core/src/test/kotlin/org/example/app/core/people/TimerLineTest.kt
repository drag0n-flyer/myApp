package org.example.app.core.people

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessagePreview
import org.example.app.core.chats.SystemEvent

/** The timer system line has the spec's three wordings, with the durations "1 day", "7 days" and "90 days". */
class TimerLineTest {
    private fun preview(seconds: Int, name: String?) =
        Message("t", "system", Instant.EPOCH, MessageContent.System(SystemEvent.Timer(seconds, name)), DeliveryStatus.Sent, null, emptyList()).preview

    @Test fun ownLineNamesTheDuration() {
        assertEquals(MessagePreview.Resource("system_timer_set_you_day"), preview(86_400, null))
        assertEquals(MessagePreview.Resource("system_timer_set_you_week"), preview(604_800, null))
        assertEquals(MessagePreview.Resource("system_timer_set_you_quarter"), preview(7_776_000, null))
    }

    @Test fun otherPersonsLineCarriesTheirName() {
        assertEquals(MessagePreview.Resource("system_timer_set_other_week", listOf("Ana")), preview(604_800, "Ana"))
        assertEquals(MessagePreview.Resource("system_timer_set_other_day", listOf("Ana")), preview(86_400, "Ana"))
        assertEquals(MessagePreview.Resource("system_timer_set_other_quarter", listOf("Ana")), preview(7_776_000, "Ana"))
    }

    @Test fun turnedOffHasItsOwnWording() {
        assertEquals(MessagePreview.Resource("system_timer_off_you"), preview(0, null))
        assertEquals(MessagePreview.Resource("system_timer_off_other", listOf("Ana")), preview(0, "Ana"))
    }
}
