package org.example.app.core.notify

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent

class NotificationPlanTest {
    private fun msg(n: Int, text: String = "m$n") = Message(
        id = "id$n", senderId = "ana", sentAt = Instant.ofEpochSecond(1000L + n),
        content = MessageContent.Text(text), status = DeliveryStatus.Delivered, replyToId = null, reactions = emptyList(),
    )

    private fun plan(unread: List<Message>, muted: Boolean = false, locked: Boolean = false) =
        NotificationPlan.forChat("c1", unread, "Ana", muted, locked)

    @Test fun showsTheSenderAndTheMessage() {
        val n = plan(listOf(msg(1, "hello")))
        assertEquals("c1", n.chatId)
        assertEquals("Ana", n.title)
        assertEquals(listOf("hello"), n.lines.map { it.text })
        assertFalse(n.silent)
        assertFalse(n.hideContent)
    }

    @Test fun keepsTheNewestSixInOrder() {
        val n = plan((1..9).map { msg(it) })
        assertEquals(NotificationPlan.MAX_LINES, n.lines.size)
        assertEquals((4..9).map { "m$it" }, n.lines.map { it.text })
    }

    @Test fun ordersByTimeEvenWhenGivenOutOfOrder() {
        val n = plan(listOf(msg(3), msg(1), msg(2)))
        assertEquals(listOf("m1", "m2", "m3"), n.lines.map { it.text })
    }

    @Test fun mutedIsSilentButStillShowsTheText() {
        val n = plan(listOf(msg(1, "hi")), muted = true)
        assertTrue(n.silent)
        assertEquals(listOf("hi"), n.lines.map { it.text })
    }

    @Test fun appLockShowsOnlyNewMessageAndNamesNobody() {
        val n = plan(listOf(msg(1, "secret"), msg(2, "more")), locked = true)
        assertTrue(n.hideContent)
        assertEquals(listOf("New message"), n.lines.map { it.text })
        assertEquals("", n.title)
    }

    @Test fun appLockKeepsMutedSilent() {
        assertTrue(plan(listOf(msg(1)), muted = true, locked = true).silent)
    }

    @Test fun nonTextMessagesUseTheDescriber() {
        val photo = msg(1).copy(content = MessageContent.Photo(10))
        val n = NotificationPlan.forChat("c1", listOf(photo), "Ana", false, false) { "<" + (it as org.example.app.core.chats.MessagePreview.Resource).key + ">" }
        assertEquals(listOf("<preview_photo>"), n.lines.map { it.text })
    }

    @Test fun summaryOnlyWithTwoOrMoreChats() {
        assertFalse(NotificationPlan.needsSummary(emptySet()))
        assertFalse(NotificationPlan.needsSummary(setOf("a")))
        assertTrue(NotificationPlan.needsSummary(setOf("a", "b")))
    }

    @Test fun aChatIsReadWhenItsUnreadCountFallsToZero() {
        val before = mapOf("a" to 2, "b" to 0, "c" to 1, "d" to 3)
        val after = mapOf("a" to 0, "b" to 0, "c" to 4) // d vanished (archived or gone): not "read"
        assertEquals(setOf("a"), NotificationPlan.readChats(before, after))
    }

    @Test fun aChatThatLeftTheListHasItsNotificationsCancelled() {
        assertEquals(
            setOf("b"),
            NotificationPlan.leftChats(setOf("a", "b"), setOf("a"), emptySet(), emptySet()),
        )
    }

    @Test fun aChatBlockedSinceTheLastLookIsCancelledButAnOldBlockIsNot() {
        assertEquals(
            setOf("c"),
            NotificationPlan.leftChats(setOf("a", "c"), setOf("a", "c"), blockedBefore = setOf("a"), blockedAfter = setOf("a", "c")),
        )
    }

    @Test fun nothingChangedCancelsNothing() {
        assertEquals(emptySet(), NotificationPlan.leftChats(setOf("a"), setOf("a"), emptySet(), emptySet()))
    }
}
