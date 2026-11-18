package org.example.app.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatEncryption
import org.example.app.core.chats.DraftKeeper

private class FakeStore(
    val chats: Map<String, Chat> = mapOf("c1" to chat("c1")),
    var blocked: Set<String> = emptySet(),
) : NotificationStore {
    override val meId = "me"
    val calls = mutableListOf<String>()
    var newId: String? = "m1"
    var settleThrows = false
    var settleHangs = false

    override fun chat(chatId: String) = chats[chatId]
    override fun blocked() = blocked
    override suspend fun markRead(chatId: String) { calls += "read:$chatId" }
    override suspend fun sendReturningId(chatId: String, text: String): String? {
        calls += "send:$chatId:$text"
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
    private val keeper = DraftKeeper()
    private fun actions(store: NotificationStore?, settleMs: Long = 50) =
        LiveNotificationActions({ store }, { keeper }, settleMs)

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
        assertTrue(actions(s).reply("c1", "hello"))
        assertEquals(listOf("send:c1:hello", "settle"), s.calls)
    }

    @Test
    fun aBlockedChatIsRefusedWithoutSending() = runTest {
        val s = FakeStore(blocked = setOf("c1"))
        assertFalse(actions(s).reply("c1", "hello"))
        assertTrue(s.calls.isEmpty())
    }

    @Test
    fun aMessageRequestIsRefusedWithoutSending() = runTest {
        val s = FakeStore(chats = mapOf("c1" to chat("c1", request = true)))
        assertFalse(actions(s).reply("c1", "hello"))
        assertTrue(s.calls.isEmpty())
    }

    @Test
    fun anUnknownChatIsRefusedWithoutSending() = runTest {
        val s = FakeStore()
        assertFalse(actions(s).reply("zz", "hello"))
        assertTrue(s.calls.isEmpty())
    }

    @Test
    fun aTextOverTheLimitIsRefusedWholeNotCutShort() = runTest {
        val s = FakeStore()
        assertFalse(actions(s).reply("c1", "x".repeat(4097)))
        assertTrue(actions(s).reply("c1", "x".repeat(4096)))
    }

    @Test
    fun noSessionIsRefused() = runTest {
        assertFalse(actions(null).reply("c1", "hello"))
    }

    @Test
    fun nothingQueuedMeansNotSent() = runTest {
        val s = FakeStore().apply { newId = null }
        assertFalse(actions(s).reply("c1", "hello"))
        assertEquals(listOf("send:c1:hello"), s.calls, "no flush wait when nothing was queued")
    }

    @Test
    fun aFailureAfterQueueingStillCountsAsQueuedSoNoDraftAndNoDuplicate() = runTest {
        val s = FakeStore().apply { settleThrows = true }
        assertTrue(actions(s).reply("c1", "hello"))
    }

    @Test
    fun aFlushThatNeverEndsIsGivenUpOnAfterTheBound() = runTest {
        val s = FakeStore().apply { settleHangs = true }
        assertTrue(actions(s, settleMs = 20).reply("c1", "hello"), "queued is queued; the outbox keeps the message")
    }

    @Test
    fun aDraftIsAppendedWithANewlineToWhatIsAlreadyTyped() = runTest {
        keeper.set("half a thought", "c1")
        actions(FakeStore()).saveDraft("c1", "my reply")
        assertEquals("half a thought\nmy reply", keeper.draft("c1"))
    }

    @Test
    fun aDraftIsJustTheTextWhenNothingWasTyped() = runTest {
        actions(FakeStore()).saveDraft("c1", "my reply")
        assertEquals("my reply", keeper.draft("c1"))
    }

    @Test
    fun noSessionKeepsNoDraftAndDoesNotCrash() = runTest {
        actions(null).saveDraft("c1", "my reply")
        assertEquals("", keeper.draft("c1"))
    }
}
