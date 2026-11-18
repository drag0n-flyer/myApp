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
        val removed = mutableListOf<Pair<List<String>, Set<String>>>()
        val sweeper = ExpirySweeper(s, { nowMs }) { ids, chats -> removed += ids to chats }

        nowMs = sentAt + 86_399_999
        assertEquals(sentAt + 86_400_000, sweeper.sweep(), "the next expiry is reported")
        assertTrue("secret" in texts(s))
        assertTrue(removed.isEmpty())

        nowMs = sentAt + 86_400_000
        assertNull(sweeper.sweep(), "nothing else is waiting")
        assertTrue("secret" !in texts(s))
        assertEquals(1, removed.size)
        assertEquals(setOf(chat), removed.single().second, "the chat of the removed message is named, for its notification")
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
