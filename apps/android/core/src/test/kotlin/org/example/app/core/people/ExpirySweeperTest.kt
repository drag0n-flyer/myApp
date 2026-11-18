package org.example.app.core.people

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData

/** The sweeper with a fake clock: a message goes at its second, not before; the open chat changes without a reload. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpirySweeperTest {
    private val start = Instant.parse("2026-11-18T12:00:00Z")
    private val chat = "chat-riya"
    private var nowMs = start.toEpochMilli()

    private fun store() = SampleChatStore(SampleData.make(start), clock = { Instant.ofEpochMilli(nowMs) })
    private fun texts(s: SampleChatStore) = s.chat(chat)!!.messages.map { it.content }.filterIsInstance<MessageContent.Text>().map { it.text }

    @Test fun aSentMessageUnderATimerGoesAtItsSecondAndNotBefore() {
        val s = store()
        s.setTimer(chat, 86_400)
        s.send(MessageContent.Text("secret"), chat)
        val sentAt = nowMs
        val removed = mutableListOf<Pair<List<String>, Map<String, Int>>>()
        val sweeper = ExpirySweeper(s, { nowMs }) { ids, chats -> removed += ids to chats }

        nowMs = sentAt + 86_399_999
        assertEquals(sentAt + 86_400_000, sweeper.sweep(), "the next expiry is reported")
        assertTrue("secret" in texts(s))
        assertTrue(removed.isEmpty())

        nowMs = sentAt + 86_400_000
        assertNull(sweeper.sweep(), "nothing else is waiting")
        assertTrue("secret" !in texts(s))
        assertEquals(1, removed.size)
        assertTrue(removed.single().second.isEmpty(), "my own message never touches a notification")
    }

    private fun storeWithIncoming(vararg theirs: Pair<String, Long>, unread: Int): SampleChatStore {
        val data = SampleData.make(start)
        val base = data.chats.first { it.id == chat }
        val messages = theirs.map { (id, exp) ->
            org.example.app.core.chats.Message(id, "riya", start, MessageContent.Text(id), org.example.app.core.chats.DeliveryStatus.Delivered, null, emptyList(), expiresAt = Instant.ofEpochMilli(exp))
        } + org.example.app.core.chats.Message("sys", "system", start, MessageContent.System(org.example.app.core.chats.SystemEvent.Timer(86_400, null)), org.example.app.core.chats.DeliveryStatus.Sent, null, emptyList(), expiresAt = Instant.ofEpochMilli(start.toEpochMilli() + 1))
        return SampleChatStore(data.copy(chats = listOf(base.copy(messages = messages, unreadCount = unread))), clock = { Instant.ofEpochMilli(nowMs) })
    }

    @Test fun anExpiredIncomingMessageReportsTheChatAndWhatIsStillUnread() {
        val t = start.toEpochMilli()
        val s = storeWithIncoming("a" to t + 10, "b" to t + 1_000, unread = 2)
        val seen = mutableListOf<Map<String, Int>>()
        val sweeper = ExpirySweeper(s, { nowMs }) { _, chats -> seen += chats }
        nowMs = t + 10
        sweeper.sweep()
        assertEquals(listOf(mapOf(chat to 1)), seen, "one unread is left, so the notification must be re-posted, not cancelled")
        nowMs = t + 1_000
        sweeper.sweep()
        assertEquals(mapOf(chat to 0), seen.last(), "nothing unread is left: the notification goes")
    }

    @Test fun aSystemLineExpiringNamesNoChat() {
        val t = start.toEpochMilli()
        val s = storeWithIncoming("a" to t + 100_000, unread = 1)
        val seen = mutableListOf<Map<String, Int>>()
        nowMs = t + 5 // only the system line (expires t+1) is due
        ExpirySweeper(s, { nowMs }) { _, chats -> seen += chats }.sweep()
        assertEquals(listOf(emptyMap()), seen)
    }

    @Test fun messagesWithoutATimerNeverGo() {
        val s = store()
        s.send(MessageContent.Text("plain"), chat)
        val sweeper = ExpirySweeper(s, { nowMs })
        nowMs += 400L * 86_400_000
        assertNull(sweeper.sweep())
        assertTrue("plain" in texts(s))
    }

    @Test fun theTimerChoiceIsKeptAndLeavesALineInTheChat() {
        val s = store()
        assertEquals(0, s.chat(chat)!!.timerSeconds)
        s.setTimer(chat, 604_800)
        assertEquals(604_800, s.chat(chat)!!.timerSeconds)
        assertEquals(604_800, s.timerSeconds(chat))
        val lines = s.chat(chat)!!.messages.map { it.content }.filterIsInstance<MessageContent.System>()
        assertEquals(org.example.app.core.chats.SystemEvent.Timer(604_800, null), lines.last().event)
        s.setTimer(chat, 604_800) // no change, no second line
        assertEquals(lines.size, s.chat(chat)!!.messages.count { it.content is MessageContent.System })
        s.setTimer(chat, 12_345) // not one of the four choices
        assertEquals(604_800, s.timerSeconds(chat))
    }

    @Test fun theRunnerSweepsByATimerAndTheChatListUpdatesWithoutAReload() = runTest {
        val s = store()
        s.setTimer(chat, 86_400)
        s.send(MessageContent.Text("short-lived"), chat)
        val base = nowMs
        val sweeper = ExpirySweeper(s, { base + currentTime })
        val job = launch { sweeper.run() }
        runCurrent()
        assertTrue("short-lived" in texts(s))
        advanceTimeBy(86_399_999)
        runCurrent()
        assertTrue("short-lived" in texts(s), "not before its second")
        advanceTimeBy(1)
        runCurrent()
        assertTrue("short-lived" !in s.chats.value.first { it.id == chat }.messages.map { (it.content as? MessageContent.Text)?.text }, "gone from the published list")
        job.cancel()
    }
}
