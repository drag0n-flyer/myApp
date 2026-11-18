package org.example.app.core.groups

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.app.core.chats.ChatTimeline
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SystemEvent

class GroupRunsTest {
    private val zone = ZoneId.of("UTC")
    private val day = 86_400_000L

    private fun m(id: String, sender: String, at: Long, content: MessageContent = MessageContent.Text(id)) =
        Message(id, sender, Instant.ofEpochMilli(at), content, DeliveryStatus.Read, null, emptyList())

    private fun ids(messages: List<Message>) = GroupRuns.senderLineIds(ChatTimeline.rows(messages, null, zone), "me")

    @Test fun theFirstMessageOfEachRunOfAnotherMemberShowsTheName() {
        val messages = listOf(m("1", "ana", 1000), m("2", "ana", 2000), m("3", "bob", 3000), m("4", "ana", 4000))
        assertEquals(setOf("1", "3", "4"), ids(messages))
    }

    @Test fun myMessagesNeverShowAName() {
        assertEquals(setOf("2"), ids(listOf(m("1", "me", 1000), m("2", "ana", 2000), m("3", "me", 3000))))
    }

    @Test fun aSystemLineBreaksARun() {
        val line = m("sys", "system", 2000, MessageContent.System(SystemEvent.AddedYou(null)))
        assertEquals(setOf("1", "3"), ids(listOf(m("1", "ana", 1000), line, m("3", "ana", 3000))))
    }

    @Test fun aNewDayBreaksARun() {
        assertEquals(setOf("1", "2"), ids(listOf(m("1", "ana", 1000), m("2", "ana", 1000 + day))))
    }

    @Test fun theUnreadLineBreaksARun() {
        val messages = listOf(m("1", "ana", 1000), m("2", "ana", 2000))
        assertEquals(setOf("1", "2"), GroupRuns.senderLineIds(ChatTimeline.rows(messages, "2", zone), "me"))
    }

    @Test fun aDeletedOrUnreadableMessageStillCountsAsTheMembersOwn() {
        val gone = m("2", "ana", 2000, MessageContent.Deleted)
        assertEquals(setOf("1"), ids(listOf(m("1", "ana", 1000), gone)))
    }

    @Test fun nothingIsNothing() {
        assertEquals(emptySet(), ids(emptyList()))
    }
}
