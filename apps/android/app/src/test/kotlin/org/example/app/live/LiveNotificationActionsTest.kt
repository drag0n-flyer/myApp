package org.example.app.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatEncryption
import org.example.app.push.Deadline

private class Clock { var ms = 0L; fun nanos() = ms * 1_000_000 }

private class FakeStore(
    val chats: Map<String, Chat> = mapOf("c1" to chat("c1")),
    var blocked: Set<String> = emptySet(),
) : NotificationStore {
    override val meId = "me"
    val calls = mutableListOf<String>()
    var newId: String? = "m1"
    var settleThrows = false
    var settleHangs = false
    val waitsAsked = mutableListOf<Long>()
    var onSend: () -> Unit = {}

    val drafts = mutableMapOf<String, String>()
    override fun chat(chatId: String) = chats[chatId]?.copy(draft = drafts[chatId])
    override suspend fun setDraft(chatId: String, text: String?) {
        if (text.isNullOrBlank()) drafts.remove(chatId) else drafts[chatId] = text
    }
    override fun blocked() = blocked
    override suspend fun markRead(chatId: String) { calls += "read:$chatId" }
    override suspend fun sendReturningId(chatId: String, text: String, waitMs: Long): String? {
        calls += "send:$chatId:$text"
        waitsAsked += waitMs
        onSend()
        return newId
    }
    override suspend fun settle() {
        calls += "settle"
        if (settleThrows) throw IllegalStateException("boom")
        if (settleHangs) CompletableDeferred<Unit>().await()
    }
}

private fun chat(id: String, request: Boolean = false) = Chat(
    id = id, contactId = id, encryption = ChatEncryption.EndToEnd, folderIds = emptySet(), unreadCount = 0,
    messages = emptyList(), isRequest = request,
)

class LiveNotificationActionsTest {
    private fun fresh() = Deadline.after(9_000)

    private fun actions(store: NotificationStore?, settleMs: Long = 50) =
        LiveNotificationActions({ store }, settleMs = settleMs)

    @Test
    fun aKnownChatIsMarkedRead() = runTest {
        val s = FakeStore()
        assertTrue(actions(s).markRead("c1"))
        assertEquals(listOf("read:c1"), s.calls)
    }

    @Test
    fun anUnknownChatOrNoSessionIsNotMarkedRead() = runTest {
        val s = FakeStore()
        assertFalse(actions(s).markRead("zz"))
        assertFalse(actions(null).markRead("c1"))
        assertTrue(s.calls.isEmpty())
    }

    @Test
    fun aReplyIsQueuedAndTheOutboxIsGivenTimeToFlush() = runTest {
        val s = FakeStore()
        assertTrue(actions(s).reply("c1", "hello", fresh()))
        assertEquals(listOf("send:c1:hello", "settle"), s.calls)
    }

    @Test
    fun aBlockedChatIsRefusedWithoutSending() = runTest {
        val s = FakeStore(blocked = setOf("c1"))
        assertFalse(actions(s).reply("c1", "hello", fresh()))
        assertTrue(s.calls.isEmpty())
    }

    @Test
    fun aMessageRequestIsRefusedWithoutSending() = runTest {
        val s = FakeStore(chats = mapOf("c1" to chat("c1", request = true)))
        assertFalse(actions(s).reply("c1", "hello", fresh()))
        assertTrue(s.calls.isEmpty())
    }

    @Test
    fun anUnknownChatIsRefusedWithoutSending() = runTest {
        val s = FakeStore()
        assertFalse(actions(s).reply("zz", "hello", fresh()))
        assertTrue(s.calls.isEmpty())
    }

    @Test
    fun aTextOverTheLimitIsRefusedWholeNotCutShort() = runTest {
        val s = FakeStore()
        assertFalse(actions(s).reply("c1", "x".repeat(4097), fresh()))
        assertTrue(actions(s).reply("c1", "x".repeat(4096), fresh()))
    }

    @Test
    fun noSessionIsRefused() = runTest {
        assertFalse(actions(null).reply("c1", "hello", fresh()))
    }

    @Test
    fun nothingQueuedMeansNotSent() = runTest {
        val s = FakeStore().apply { newId = null }
        assertFalse(actions(s).reply("c1", "hello", fresh()))
        assertEquals(listOf("send:c1:hello"), s.calls, "no flush wait when nothing was queued")
    }

    @Test
    fun aFailureAfterQueueingStillCountsAsQueuedSoNoDraftAndNoDuplicate() = runTest {
        val s = FakeStore().apply { settleThrows = true }
        assertTrue(actions(s).reply("c1", "hello", fresh()))
    }

    @Test
    fun aFlushThatNeverEndsIsGivenUpOnAfterTheBound() = runTest {
        val s = FakeStore().apply { settleHangs = true }
        assertTrue(actions(s, settleMs = 20).reply("c1", "hello", fresh()), "queued is queued; the outbox keeps the message")
    }

    @Test
    fun aDraftIsAppendedWithANewlineToWhatIsAlreadyTyped() = runTest {
        val s = FakeStore().apply { drafts["c1"] = "half a thought" }
        actions(s).saveDraft("c1", "my reply")
        assertEquals("half a thought\nmy reply", s.drafts["c1"])
        actions(s).saveDraft("c1", "and again")
        assertEquals("half a thought\nmy reply\nand again", s.drafts["c1"], "never replaced")
    }

    @Test
    fun aRefusedReplyLeavesItsTextAsADraftInTheStore() = runTest {
        val s = FakeStore(blocked = setOf("c1"))
        val a = actions(s)
        assertFalse(a.reply("c1", "lost words", fresh()))
        a.saveDraft("c1", "lost words")
        assertEquals("lost words", s.drafts["c1"])
    }

    @Test
    fun aDraftIsJustTheTextWhenNothingWasTyped() = runTest {
        val s = FakeStore()
        actions(s).saveDraft("c1", "my reply")
        assertEquals("my reply", s.drafts["c1"])
    }

    @Test
    fun noSessionKeepsNoDraftAndDoesNotCrash() = runTest {
        actions(null).saveDraft("c1", "my reply")
    }

    // ---- the time budget: the whole reply must end before the deadline the receiver set

    private class Run(val clock: Clock = Clock()) {
        val store = FakeStore().also { st -> st.onSend = { clock.ms += queueCost } }
        val waits = mutableListOf<Long>()
        var openCost = 0L
        var queueCost = 0L
        val sink = LiveNotificationActions(
            store = { clock.ms += openCost; store },
            bounded = { ms, block -> waits += ms; block() },
        )
        fun deadline() = Deadline.after(9_000, clock::nanos)
    }

    @Test
    fun withTimeToSpareTheWaitsAreTheirFullBounds() = runTest {
        val r = Run()
        assertTrue(r.sink.reply("c1", "hello", r.deadline()))
        assertEquals(listOf(4_000L), r.store.waitsAsked)
        assertEquals(listOf(3_000L), r.waits)
    }

    @Test
    fun aSlowSessionOpenLeavesLessTimeForTheWaits() = runTest {
        val r = Run().apply { openCost = 6_000; queueCost = 1_000 }
        assertTrue(r.sink.reply("c1", "hello", r.deadline()))
        assertEquals(listOf(3_000L), r.store.waitsAsked, "min(4 s, 3 s left)")
        assertEquals(listOf(2_000L), r.waits, "1 s went on queueing: min(3 s, 2 s left)")
        assertTrue(r.store.waitsAsked.single() + 6_000 <= 9_000)
    }

    @Test
    fun noTimeLeftAfterQueueingSkipsTheFlushWaitButStaysQueued() = runTest {
        val r = Run().apply { openCost = 5_000; queueCost = 4_000 }
        assertTrue(r.sink.reply("c1", "hello", r.deadline()), "the message is durable once queued")
        assertTrue(r.waits.isEmpty())
        assertTrue("settle" !in r.store.calls)
    }

    @Test
    fun aDeadlinePassedBeforeQueueingMeansNothingIsSent() = runTest {
        val r = Run().apply { openCost = 9_500 }
        assertFalse(r.sink.reply("c1", "hello", r.deadline()))
        assertTrue(r.store.calls.none { it.startsWith("send") }, "sendReturningId was never asked")
    }

    @Test
    fun aSessionThatNeverOpensEndsAtTheDeadlineAsNotSent() = runTest {
        val hung = LiveNotificationActions(store = { CompletableDeferred<NotificationStore?>().await() })
        assertFalse(hung.reply("c1", "hello", Deadline.after(50)))
    }
}
