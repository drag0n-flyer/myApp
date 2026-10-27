package org.example.app.store

import org.example.app.messaging.ports.ChatRecord
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.ports.OutboxRecord
import org.example.app.messaging.ports.PayloadHash
import org.example.app.messaging.ports.RawInboxRecord
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour every [org.example.app.messaging.ports.Repository] on Room must have, whatever the open helper. */
abstract class RepositoryContract {
    protected abstract fun openDatabase(): AppDatabase

    protected lateinit var db: AppDatabase
    protected lateinit var repo: RoomRepository

    @Before fun setUp() {
        db = openDatabase()
        repo = RoomRepository(db)
        repo.upsertChat(ChatRecord("chat1", isRequest = false, unread = 0, muted = false, lastActivity = 1))
    }

    @After fun tearDown() = db.close()

    protected fun message(id: String, text: String = "", sentAt: Long = 1_000, chat: String = "chat1", status: String = "sent") =
        MessageRecord(
            id = id, chatId = chat, conversationId = null, senderAccount = "alice", senderDevice = null, sentAt = sentAt,
            receivedAt = sentAt, envelope = id.toByteArray(), status = status, folded = TextFold.fold(text),
        )

    private fun raw(cursor: Long, payload: ByteArray) =
        RawInboxRecord(cursor, "message", "conv1", cursor, 1, "alice", "dev1", payload, 10)

    @Test fun duplicateMessageIdIsIgnored() {
        assertTrue(repo.insertMessage(message("m1", "first")))
        assertFalse(repo.insertMessage(message("m1", "second")))
        assertEquals(1, repo.messages("chat1", null, 10).size)
        assertEquals(TextFold.fold("first"), repo.message("m1")?.folded)
    }

    @Test fun inboxRawThenProcessIsAtomic() {
        val payload = "ciphertext".toByteArray()
        repo.insertRaw(listOf(raw(1, payload)))
        // A crash half way through processing leaves nothing behind and the raw row in place.
        assertFailsWith<IllegalStateException> {
            repo.transaction {
                repo.insertMessage(message("m1", "hello"))
                repo.markProcessed(PayloadHash.of(payload), 20)
                repo.deleteRaw(1)
                throw IllegalStateException("crash")
            }
        }
        assertNull(repo.message("m1"))
        assertFalse(repo.isProcessed(PayloadHash.of(payload)))
        assertEquals(1, repo.rawItems(10).size)
        // The retry completes all three steps together.
        repo.transaction {
            repo.insertMessage(message("m1", "hello"))
            repo.markProcessed(PayloadHash.of(payload), 20)
            repo.deleteRaw(1)
        }
        assertNotNull(repo.message("m1"))
        assertTrue(repo.isProcessed(PayloadHash.of(payload)))
        assertTrue(repo.rawItems(10).isEmpty())
    }

    @Test fun processedHashDedups() {
        val hash = PayloadHash.of("same ciphertext".toByteArray())
        assertEquals(32, hash.size)
        assertFalse(repo.isProcessed(hash))
        repo.markProcessed(hash, 1)
        repo.markProcessed(PayloadHash.of("same ciphertext".toByteArray()), 2)
        assertTrue(repo.isProcessed(hash))
        assertFalse(repo.isProcessed(PayloadHash.of("other".toByteArray())))
        repo.pruneProcessed(olderThan = 100)
        assertFalse(repo.isProcessed(hash))
    }

    @Test fun rawInboxKeepsCursorOrderAndIgnoresRepeats() {
        repo.insertRaw(listOf(raw(2, byteArrayOf(2)), raw(1, byteArrayOf(1))))
        repo.insertRaw(listOf(raw(1, byteArrayOf(9))))
        val items = repo.rawItems(10)
        assertEquals(listOf(1L, 2L), items.map { it.cursor })
        assertContentEquals(byteArrayOf(1), items[0].payload)
    }

    @Test fun searchFindsLatinIndicCjkRtl() {
        repo.insertMessage(message("latin", "Meet me at the Café tomorrow", sentAt = 1))
        repo.insertMessage(message("indic", "नमस्ते दोस्त कैसे हो", sentAt = 2))
        repo.insertMessage(message("cjk", "明日東京タワーで会いましょう", sentAt = 3))
        repo.insertMessage(message("rtl", "مرحبا بالعالم الجميل", sentAt = 4))
        fun ids(q: String) = repo.search(q, 10).map { it.messageId }
        assertEquals(listOf("latin"), ids("CAFE"))
        assertEquals(listOf("latin"), ids("tomorrow"))
        assertEquals(listOf("indic"), ids("दोस्त"))
        assertEquals(listOf("cjk"), ids("東京タワー"))
        assertEquals(listOf("rtl"), ids("بالعالم"))
        assertTrue(ids("nothing here").isEmpty())
        assertEquals("chat1", repo.search("tomorrow", 10).single().chatId)
    }

    @Test fun searchFollowsEditsAndDeletes() {
        repo.insertMessage(message("m1", "alpha bravo"))
        assertTrue(repo.applyEdit("m1", "alice", byteArrayOf(1), TextFold.fold("charlie delta"), 5))
        assertTrue(repo.search("alpha", 10).isEmpty())
        assertEquals(listOf("m1"), repo.search("charlie", 10).map { it.messageId })
        assertFalse(repo.applyEdit("m1", "mallory", byteArrayOf(1), "evil", 6))
        assertTrue(repo.markDeleted("m1", "alice"))
        assertTrue(repo.search("charlie", 10).isEmpty())
    }

    @Test fun shortQueryUsesLike() {
        repo.insertMessage(message("m1", "東京タワー", sentAt = 1))
        repo.insertMessage(message("m2", "100% sure_ok", sentAt = 2))
        assertEquals(listOf("m1"), repo.search("東京", 10).map { it.messageId })
        assertEquals(listOf("m2"), repo.search("%", 10).map { it.messageId })
        assertEquals(listOf("m2"), repo.search("_", 10).map { it.messageId })
        assertTrue(repo.search("", 10).isEmpty())
    }

    @Test fun bulkInsert10k() {
        val start = System.nanoTime()
        repo.transaction {
            for (i in 0 until 10_000) repo.insertMessage(message("bulk$i", "message number $i about topic${i % 50}", sentAt = i.toLong()))
        }
        val insertMs = (System.nanoTime() - start) / 1_000_000
        val searchStart = System.nanoTime()
        val hits = repo.search("topic7", 10_000)
        val searchMs = (System.nanoTime() - searchStart) / 1_000_000
        val pageStart = System.nanoTime()
        val page = repo.messages("chat1", null, 50)
        val pageMs = (System.nanoTime() - pageStart) / 1_000_000
        println("bulkInsert10k: insert ${insertMs}ms, search ${searchMs}ms (${hits.size} hits), newest page ${pageMs}ms")
        assertEquals(50, page.size)
        assertEquals(200, hits.size)
    }

    @Test fun statusNeverLowers() {
        repo.insertMessage(message("m1", status = "read"))
        repo.insertMessage(message("m2", status = "sending"))
        repo.updateStatus(listOf("m1", "m2"), "delivered")
        assertEquals("read", repo.message("m1")?.status)
        assertEquals("delivered", repo.message("m2")?.status)
        repo.updateStatus(listOf("m1"), "failed")
        assertEquals("failed", repo.message("m1")?.status)
    }

    @Test fun pagingReturnsOldestFirst() {
        for (i in 1..5) repo.insertMessage(message("m$i", sentAt = i.toLong()))
        val newest = repo.messages("chat1", null, 2)
        assertEquals(listOf("m4", "m5"), newest.map { it.id })
        assertEquals(listOf("m2", "m3"), repo.messages("chat1", newest.first(), 2).map { it.id })
    }

    @Test fun pinInnerModeKeepsFirstValueAndFlagsMismatch() {
        repo.upsertConversation(ConversationRecord("c1", "chat1", byteArrayOf(1), "e2ee", null, false, 0, true, 1))
        assertTrue(repo.pinInnerMode("c1", "e2ee"))
        assertTrue(repo.pinInnerMode("c1", "e2ee"))
        assertFalse(repo.pinInnerMode("c1", "server_readable"))
        val c = repo.conversation("c1")!!
        assertEquals("e2ee", c.innerMode)
        assertTrue(c.modeMismatch)
    }

    @Test fun upsertConversationKeepsMembers() {
        repo.upsertConversation(ConversationRecord("c1", "chat1", byteArrayOf(1), "e2ee", null, false, 0, true, 1))
        repo.replaceMembers("c1", listOf(org.example.app.messaging.ports.MemberRecord("alice", byteArrayOf(7))))
        repo.upsertConversation(ConversationRecord("c1", "chat1", byteArrayOf(1), "e2ee", null, false, 1, true, 1))
        assertEquals(1, repo.members("c1").size)
        assertEquals(1L, repo.conversation("c1")?.epoch)
    }

    @Test fun preferredConversationIsSmallestActive() {
        repo.upsertConversation(ConversationRecord("b", "chat1", byteArrayOf(2), "e2ee", null, false, 0, true, 1))
        repo.upsertConversation(ConversationRecord("a", "chat1", byteArrayOf(1), "e2ee", null, false, 0, false, 1))
        repo.upsertConversation(ConversationRecord("c", "chat1", byteArrayOf(3), "e2ee", null, false, 0, true, 1))
        assertEquals("b", repo.preferredConversation("chat1")?.serverId)
    }

    @Test fun outboxKeepsOrderPerChat() {
        repo.enqueueSend(message("m1", status = "sending"), OutboxRecord("m1", "chat1", byteArrayOf(1), 0, 100))
        repo.enqueueSend(message("m2", status = "sending"), OutboxRecord("m2", "chat1", byteArrayOf(2), 0, 50))
        // m2 is due earlier but must not overtake m1.
        assertEquals(listOf("m1"), repo.dueOutboxHeads(200).map { it.messageId })
        assertTrue(repo.dueOutboxHeads(60).isEmpty())
        repo.completeSend("m1", "sent")
        assertEquals("sent", repo.message("m1")?.status)
        assertEquals(listOf("m2"), repo.dueOutboxHeads(200).map { it.messageId })
    }

    @Test fun deletingChatCascades() {
        repo.insertMessage(message("m1", "hello world"))
        repo.enqueue(OutboxRecord("m1", "chat1", byteArrayOf(1), 0, 1))
        repo.upsertConversation(ConversationRecord("c1", "chat1", byteArrayOf(1), "e2ee", null, false, 0, true, 1))
        repo.deleteChat("chat1")
        assertNull(repo.message("m1"))
        assertNull(repo.conversation("c1"))
        assertTrue(repo.dueOutbox(Long.MAX_VALUE).isEmpty())
        assertTrue(repo.search("hello", 10).isEmpty())
    }

    @Test fun kvAndBlockedAndFolders() {
        repo.set("settings.a", byteArrayOf(1))
        repo.set("settings.b", byteArrayOf(2))
        repo.set("other", byteArrayOf(3))
        assertEquals(setOf("settings.a", "settings.b"), repo.entries("settings.").keys)
        repo.block("bob", 5)
        assertTrue(repo.isBlocked("bob"))
        repo.unblock("bob")
        assertFalse(repo.isBlocked("bob"))
        repo.addFolder(org.example.app.messaging.ports.FolderRecord("f1", "One", 0))
        repo.addFolder(org.example.app.messaging.ports.FolderRecord("f2", "Two", 1))
        repo.moveFolder("f2", 0)
        assertEquals(listOf("f2", "f1"), repo.folders().map { it.id })
        repo.setFolderChats("f1", listOf("chat1"))
        assertEquals(listOf("chat1"), repo.folderChats("f1"))
    }

    @Test fun wipeEmptiesEverything() {
        repo.insertMessage(message("m1", "secret words"))
        repo.set("k", byteArrayOf(1))
        repo.markProcessed(byteArrayOf(1), 1)
        repo.wipe()
        assertTrue(repo.chats().isEmpty())
        assertNull(repo.message("m1"))
        assertNull(repo.get("k"))
        assertTrue(repo.search("secret", 10).isEmpty())
    }
}
