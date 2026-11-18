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
    /** The repository's clock: nothing is expired while it reads 0. */
    protected var nowMs = 0L
    protected open fun newRepository(db: AppDatabase) = RoomRepository(db, clock = { nowMs })

    protected lateinit var db: AppDatabase
    protected lateinit var repo: RoomRepository

    @Before fun setUp() {
        db = openDatabase()
        repo = newRepository(db)
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

    @Test fun markProcessedReportsWhetherNew() {
        val hash = PayloadHash.of("x".toByteArray())
        assertTrue(repo.markProcessed(hash, 1))
        assertFalse(repo.markProcessed(hash, 2))
    }

    @Test fun badModesAreRejectedAndNeverPinned() {
        val bad = ConversationRecord("c1", "chat1", byteArrayOf(1), "bogus", null, false, 0, true, 1)
        assertFailsWith<IllegalArgumentException> { repo.upsertConversation(bad) }
        assertFailsWith<IllegalArgumentException> { repo.upsertConversation(bad.copy(mode = "e2ee", innerMode = "bogus")) }
        repo.upsertConversation(bad.copy(mode = "e2ee"))
        assertFailsWith<IllegalArgumentException> { repo.pinInnerMode("c1", "bogus") }
        assertFailsWith<IllegalArgumentException> { repo.setServerMode("c1", "bogus") }
        val c = repo.conversation("c1")!!
        assertNull(c.innerMode)
        assertFalse(c.modeMismatch)
        assertEquals("e2ee", c.mode)
    }

    @Test fun badAttachmentStateIsRejected() {
        repo.insertMessage(message("m1"))
        val a = org.example.app.messaging.ports.AttachmentRecord("a1", "m1", "photo", byteArrayOf(1), byteArrayOf(2), 1, 2, "remote", 0, null)
        assertFailsWith<IllegalArgumentException> { repo.upsertAttachment(a.copy(state = "bogus")) }
        repo.upsertAttachment(a)
        assertFailsWith<IllegalArgumentException> { repo.setAttachmentState("a1", "bogus", 0, null) }
        assertEquals("remote", repo.attachment("a1")?.state)
    }

    @Test fun databaseRejectsBadValuesEvenBehindTheRepository() {
        repo.upsertConversation(ConversationRecord("c1", "chat1", byteArrayOf(1), "e2ee", null, false, 0, true, 1))
        val sqlite = db.openHelper.writableDatabase
        assertFailsWith<android.database.SQLException> { sqlite.execSQL("UPDATE conversations SET inner_mode='bogus' WHERE server_id='c1'") }
        assertFailsWith<android.database.SQLException> { sqlite.execSQL("UPDATE conversations SET mode='bogus' WHERE server_id='c1'") }
        assertFailsWith<android.database.SQLException> {
            sqlite.execSQL("INSERT INTO conversations VALUES('c2','chat1',x'02','bogus',NULL,0,0,1,1)")
        }
        repo.insertMessage(message("m1"))
        assertFailsWith<android.database.SQLException> {
            sqlite.execSQL("INSERT INTO attachments VALUES('a1','m1','photo',x'01',x'02',1,2,'bogus',0,NULL)")
        }
    }

    @Test fun foreignKeysAreOn() {
        db.openHelper.writableDatabase.query("PRAGMA foreign_keys").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
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

    @Test fun anUnsentEnvelopeCanBeReplacedButASentOneCannot() {
        repo.insertMessage(message("m1", "x").copy(status = "sending"))
        assertTrue(repo.replaceUnsentEnvelope("m1", "alice", byteArrayOf(7), "f", 5))
        assertEquals(7, repo.message("m1")!!.envelope.single().toInt())
        assertEquals(null, repo.message("m1")!!.editedAt)
        assertEquals(5L, repo.message("m1")!!.receivedAt)
        assertFalse(repo.replaceUnsentEnvelope("m1", "mallory", byteArrayOf(8), "f", 5))
        repo.updateStatus(listOf("m1"), "failed")
        assertTrue(repo.replaceUnsentEnvelope("m1", "alice", byteArrayOf(9), "f", 5))
        repo.updateStatus(listOf("m1"), "sent")
        assertFalse(repo.replaceUnsentEnvelope("m1", "alice", byteArrayOf(10), "f", 5))
    }

    @Test fun onlyAnUnsentOwnMessageCanBeRemoved() {
        repo.insertMessage(message("m1", "x").copy(status = "sending"))
        repo.insertMessage(message("m2", "y").copy(status = "sent"))
        assertFalse(repo.deleteUnsentMessage("m1", "mallory"))
        assertFalse(repo.deleteUnsentMessage("m2", "alice"))
        assertTrue(repo.deleteUnsentMessage("m1", "alice"))
        assertEquals(null, repo.message("m1"))
        assertEquals("m2", repo.message("m2")!!.id)
        assertFalse(repo.deleteUnsentMessage("m1", "alice"))
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

    @Test fun aSecondRetryOfAFailedMessageEnqueuesNothing() {
        repo.enqueueSend(message("m1", status = "sending"), OutboxRecord("m1", "chat1", byteArrayOf(1), 0, 100))
        repo.completeSend("m1", "failed")
        assertTrue(repo.requeueSend("m1", 200))
        // Two quick taps on "Try again": the second finds the message already sending.
        assertFalse(repo.requeueSend("m1", 201))
        assertEquals(1, repo.dueOutbox(Long.MAX_VALUE).count { it.messageId == "m1" })
        assertFalse(repo.requeueSend("unknown", 202))
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

    @Test fun pinMuteAndArchiveChangeOnlyTheirOwnColumn() {
        repo.setUnread("chat1", 3)
        repo.setMuted("chat1", true)
        repo.setArchived("chat1", true)
        repo.setPinnedAt("chat1", 500) // directly: the store itself never leaves an archived chat pinned
        val row = repo.chat("chat1")!!
        assertEquals(500L, row.pinnedAt)
        assertTrue(row.muted)
        assertTrue(row.archived)
        assertEquals(3, row.unread)
        // What an incoming message does: unread and last activity, written back from the row just read.
        repo.upsertChat(row.copy(unread = row.unread + 1, lastActivity = 99))
        val after = repo.chat("chat1")!!
        assertEquals(4, after.unread)
        assertEquals(500L, after.pinnedAt)
        assertTrue(after.muted)
        assertTrue(after.archived)
        repo.setPinnedAt("chat1", null)
        repo.setMuted("chat1", false)
        repo.setArchived("chat1", false)
        assertNull(repo.chat("chat1")!!.pinnedAt)
        assertFalse(repo.chat("chat1")!!.muted)
        assertFalse(repo.chat("chat1")!!.archived)
    }

    @Test fun draftsAreKeptPerChatBlankRemovesAndAMissingChatIsIgnored() {
        repo.upsertChat(ChatRecord("chat2", isRequest = false, unread = 0, muted = false, lastActivity = 2))
        repo.setDraft("chat1", "one", 10)
        repo.setDraft("chat2", "two", 11)
        assertEquals("one", repo.draft("chat1"))
        assertEquals(mapOf("chat1" to "one", "chat2" to "two"), repo.drafts())
        repo.setDraft("chat1", "one, edited", 12)
        assertEquals("one, edited", repo.draft("chat1"))
        repo.setDraft("chat1", "  \n", 13)
        assertNull(repo.draft("chat1"))
        repo.setDraft("nobody", "lost", 14)
        assertNull(repo.draft("nobody"))
        assertEquals(setOf("chat2"), repo.drafts().keys)
    }

    @Test fun deletingAChatAndWipingRemoveItsDrafts() {
        repo.setDraft("chat1", "gone with the chat", 1)
        repo.deleteChat("chat1")
        assertTrue(repo.drafts().isEmpty())
        repo.upsertChat(ChatRecord("chat1", isRequest = false, unread = 0, muted = false, lastActivity = 1))
        repo.setDraft("chat1", "gone with the account", 2)
        repo.wipe()
        assertTrue(repo.drafts().isEmpty())
    }

    @Test fun bumpActivityRaisesOnlyLastActivityAndNeverUndoesPinMuteArchive() {
        repo.setMuted("chat1", true)
        repo.setArchived("chat1", true)
        repo.setPinnedAt("chat1", 500) // directly: the store itself never leaves an archived chat pinned
        repo.bumpActivity("chat1", 900)
        val row = repo.chat("chat1")!!
        assertEquals(900L, row.lastActivity)
        assertEquals(500L, row.pinnedAt)
        assertTrue(row.muted)
        assertTrue(row.archived)
        repo.bumpActivity("chat1", 100)
        assertEquals(900L, repo.chat("chat1")!!.lastActivity, "an older time changes nothing")
        repo.bumpActivity("nobody", 5) // no row, no error
    }

    @Test fun bumpChatAddsUnreadAndRaisesActivityInOneStep() {
        repo.setPinnedAt("chat1", 500)
        repo.setMuted("chat1", true)
        repo.setUnread("chat1", 2)
        repo.bumpChat("chat1", 1, 700)
        var row = repo.chat("chat1")!!
        assertEquals(3, row.unread)
        assertEquals(700L, row.lastActivity)
        assertEquals(500L, row.pinnedAt)
        assertTrue(row.muted)
        repo.bumpChat("chat1", 0, 10)
        row = repo.chat("chat1")!!
        assertEquals(3, row.unread)
        assertEquals(700L, row.lastActivity)
    }

    @Test fun archivingAlsoUnpinsInOneWriteAndUnarchivingDoesNotRepin() {
        repo.setPinnedAt("chat1", 500)
        repo.setArchived("chat1", true)
        var row = repo.chat("chat1")!!
        assertTrue(row.archived)
        assertNull(row.pinnedAt)
        repo.setArchived("chat1", false)
        row = repo.chat("chat1")!!
        assertFalse(row.archived)
        assertNull(row.pinnedAt)
    }

    @Test fun reduceUnreadNeverGoesBelowZeroAndTouchesNothingElse() {
        repo.setMuted("chat1", true)
        repo.setUnread("chat1", 3)
        repo.reduceUnread("chat1", 2)
        assertEquals(1, repo.chat("chat1")!!.unread)
        repo.reduceUnread("chat1", 5)
        val row = repo.chat("chat1")!!
        assertEquals(0, row.unread)
        assertTrue(row.muted)
        repo.reduceUnread("nobody", 1) // no row, no error
    }

    // People base (version 3)

    @Test fun timerIsPerChatAndNothingElseUndoesIt() {
        assertEquals(0, repo.timerSeconds("chat1"))
        assertEquals(0, repo.timerSeconds("nobody"))
        repo.setTimer("chat1", 604_800)
        assertEquals(604_800, repo.timerSeconds("chat1"))
        repo.setMuted("chat1", true)
        repo.bumpChat("chat1", 1, 50)
        repo.setArchived("chat1", true)
        assertEquals(604_800, repo.timerSeconds("chat1"))
        assertEquals(604_800, repo.chat("chat1")!!.timerSeconds)
        repo.setTimer("chat1", 0)
        assertEquals(0, repo.timerSeconds("chat1"))
    }

    @Test fun messageKeepsExpiryCaptionAndPreview() {
        repo.insertMessage(message("m1", "x").copy(expiresAt = 9_000, caption = "a caption", linkPreview = """{"url":"https://a.example"}"""))
        val row = repo.message("m1")!!
        assertEquals(9_000L, row.expiresAt)
        assertEquals("a caption", row.caption)
        assertEquals("""{"url":"https://a.example"}""", row.linkPreview)
        repo.insertMessage(message("m2"))
        assertNull(repo.message("m2")!!.expiresAt)
        assertNull(repo.message("m2")!!.caption)
    }

    @Test fun captionIsFoundBySearch() {
        repo.insertMessage(message("m1", "sunset over the harbour").copy(caption = "sunset over the harbour"))
        assertEquals(listOf("m1"), repo.search("harbour", 10).map { it.messageId })
    }

    @Test fun sweepExpiredRemovesRowFileSearchEntryReactionAndOutboxInOneGo() {
        val file = java.io.File.createTempFile("blob", ".enc").apply { writeText("x") }
        try {
            repo.insertMessage(message("old", "findable word", sentAt = 100).copy(expiresAt = 1_000))
            repo.upsertAttachment(
                org.example.app.messaging.ports.AttachmentRecord("att", "old", "photo", ByteArray(1), ByteArray(1), 1, 1, "local", 1, file.path),
            )
            repo.setReaction("old", "bob", "x")
            repo.enqueue(OutboxRecord("old", "chat1", ByteArray(1), 0, 5))
            repo.insertMessage(message("edge", "edge word", sentAt = 101).copy(expiresAt = 2_000))
            repo.insertMessage(message("later", "later word", sentAt = 102).copy(expiresAt = 2_001))
            repo.insertMessage(message("forever", "forever word", sentAt = 103))

            assertEquals(emptyList(), repo.sweepExpired(999, "me"))
            assertEquals(setOf("old", "edge"), repo.sweepExpired(2_000, "me").toSet())

            assertNull(repo.message("old"))
            assertNull(repo.message("edge"))
            assertNull(repo.attachment("att"))
            assertTrue(repo.reactions("old").isEmpty())
            assertTrue(repo.dueOutbox(Long.MAX_VALUE).isEmpty())
            assertFalse(file.exists(), "the attachment file is deleted")
            assertTrue(repo.search("findable", 10).isEmpty(), "the search entry is gone")
            assertEquals(listOf("later"), repo.search("later word", 10).map { it.messageId })
            assertNotNull(repo.message("forever"))
            assertEquals(emptyList(), repo.sweepExpired(2_000, "me"))
        } finally {
            file.delete()
        }
    }

    @Test fun sweepingAnUnreadMessageLowersTheUnreadCount() = unreadSweep()

    private fun unreadSweep() {
        repo.setUnread("chat1", 3)
        repo.insertMessage(message("u1", "a", sentAt = 1).copy(status = "delivered", expiresAt = 10))
        repo.insertMessage(message("r1", "b", sentAt = 2).copy(status = "read", expiresAt = 10))
        repo.insertMessage(message("own", "c", sentAt = 3).copy(senderAccount = "me", status = "delivered", expiresAt = 10))
        repo.insertMessage(message("sys", "d", sentAt = 4).copy(senderAccount = "system", status = "sent", expiresAt = 10))
        repo.insertMessage(message("keep", "e", sentAt = 5).copy(status = "delivered", expiresAt = 99))
        assertEquals(setOf("u1", "r1", "own", "sys"), repo.sweepExpired(10, "me").toSet())
        assertEquals(2, repo.chat("chat1")!!.unread, "only the unread incoming message counted")
        repo.setUnread("chat1", 0)
        repo.insertMessage(message("u2", "f", sentAt = 6).copy(status = "delivered", expiresAt = 10))
        repo.sweepExpired(10, "me")
        assertEquals(0, repo.chat("chat1")!!.unread, "never below zero")
    }

    @Test fun anExpiredMessageIsHiddenFromEveryReadBeforeTheSweep() {
        repo.insertMessage(message("gone", "needle gone", sentAt = 10).copy(expiresAt = 100))
        repo.insertMessage(message("edge", "needle edge", sentAt = 11).copy(expiresAt = 200))
        repo.insertMessage(message("live", "needle live", sentAt = 12).copy(expiresAt = 201))
        repo.insertMessage(message("forever", "needle forever", sentAt = 13))
        for (id in listOf("gone", "edge", "live")) {
            repo.upsertAttachment(org.example.app.messaging.ports.AttachmentRecord("att-$id", id, "photo", ByteArray(1), ByteArray(1), 1, 1, "local", 1, null))
        }
        nowMs = 200
        assertEquals(listOf("live", "forever"), repo.messages("chat1", null, 10).map { it.id })
        assertEquals(emptyList(), repo.messages("chat1", repo.message("live"), 10).map { it.id }, "older than live: both expired")
        assertEquals(listOf("forever", "live"), repo.search("needle", 10).map { it.messageId })
        assertEquals(listOf("forever", "live"), repo.search("ne", 10).map { it.messageId }, "the short LIKE search too")
        assertNull(repo.attachment("att-gone")); assertNull(repo.attachmentForMessage("edge"))
        assertNotNull(repo.attachment("att-live")); assertNotNull(repo.attachmentForMessage("live"))
        nowMs = 100
        assertEquals(listOf("edge", "live", "forever"), repo.messages("chat1", null, 10).map { it.id })
        nowMs = 0
        assertEquals(4, repo.messages("chat1", null, 10).size, "nothing hides while the clock is before every expiry")
    }

    @Test fun expiredUnreadCountsOnlyUnreadIncomingMessagesPastTheirTime() {
        repo.setUnread("chat1", 3)
        repo.insertMessage(message("u1", "a", sentAt = 1).copy(status = "delivered", expiresAt = 10))
        repo.insertMessage(message("r1", "b", sentAt = 2).copy(status = "read", expiresAt = 10))
        repo.insertMessage(message("own", "c", sentAt = 3).copy(senderAccount = "me", status = "delivered", expiresAt = 10))
        repo.insertMessage(message("sys", "d", sentAt = 4).copy(senderAccount = "system", status = "sent", expiresAt = 10))
        repo.insertMessage(message("keep", "e", sentAt = 5).copy(status = "delivered", expiresAt = 99))
        assertEquals(emptyMap(), repo.expiredUnread(9, "me"))
        assertEquals(mapOf("chat1" to 1), repo.expiredUnread(10, "me"))
        assertEquals(mapOf("chat1" to 2), repo.expiredUnread(99, "me"))
        assertEquals(3, repo.chat("chat1")!!.unread, "the stored count is only lowered by the sweep")
    }

    @Test fun saveProfileKeepsOnlyAStrictlyHigherVersion() {
        assertNull(repo.profile("bob"))
        assertTrue(repo.saveProfile("bob", "Bob", byteArrayOf(1, 2), 1))
        var p = repo.profile("bob")!!
        assertEquals("Bob", p.name)
        assertContentEquals(byteArrayOf(1, 2), p.photo)
        assertEquals(1, p.ver)

        assertFalse(repo.saveProfile("bob", "Other", null, 1))
        assertFalse(repo.saveProfile("bob", "Older", null, 0))
        assertEquals("Bob", repo.profile("bob")!!.name)

        assertTrue(repo.saveProfile("bob", "Robert", null, 2))
        p = repo.profile("bob")!!
        assertEquals("Robert", p.name)
        assertNull(p.photo)
        assertEquals(2, p.ver)
    }

    @Test fun wipeRemovesProfiles() {
        repo.saveProfile("bob", "Bob", null, 1)
        repo.wipe()
        assertNull(repo.profile("bob"))
    }

    // Groups (version 5)

    private val photo = byteArrayOf(-1, -40, 1, 2)
    private fun member(id: String, role: String = "member") = org.example.app.messaging.ports.GroupMemberRecord(id, role)

    private fun newGroup(chat: String = "g:c1", conversation: String = "c1") {
        repo.upsertGroup(chat, conversation, "Book club", 5)
    }

    @Test fun existingChatsAreDirectWithNoGroupFields() {
        val c = repo.chat("chat1")!!
        assertEquals("direct", c.kind)
        assertNull(c.conversationId); assertNull(c.title); assertNull(c.photo); assertNull(c.groupVer); assertNull(c.membersVersion)
        assertEquals("member", c.myState)
        assertTrue(repo.groupMembers("chat1").isEmpty())
    }

    @Test fun upsertGroupCreatesAGroupChatOnceAndKeepsLocalChoices() {
        newGroup()
        val g = repo.chat("g:c1")!!
        assertEquals("group", g.kind); assertEquals("c1", g.conversationId); assertEquals("Book club", g.title)
        assertEquals(5L, g.lastActivity); assertFalse(g.isRequest); assertEquals(0, g.unread); assertEquals("member", g.myState)
        repo.setMuted("g:c1", true); repo.setPinnedAt("g:c1", 9)
        repo.upsertGroup("g:c1", "c1", "Ignored", 99)
        val again = repo.chat("g:c1")!!
        assertTrue(again.muted); assertEquals(9L, again.pinnedAt); assertEquals("Book club", again.title); assertEquals(5L, again.lastActivity)
    }

    @Test fun upsertGroupRefusesADirectChatAndAnotherChatForTheSameConversation() {
        assertFailsWith<IllegalArgumentException> { repo.upsertGroup("chat1", "c9", "X", 1) }
        assertEquals("direct", repo.chat("chat1")!!.kind)
        newGroup()
        assertFailsWith<Exception> { repo.upsertGroup("g:other", "c1", "Y", 1) }
        assertNull(repo.chat("g:other"))
    }

    @Test fun setGroupMembersReturnsTheOldListAndKeepsTheHighestVersion() {
        newGroup()
        assertEquals(emptyList(), repo.setGroupMembers("g:c1", listOf(member("b"), member("a", "owner")), 0))
        assertEquals(listOf(member("a", "owner"), member("b")), repo.groupMembers("g:c1"))
        assertEquals(0L, repo.chat("g:c1")!!.membersVersion)
        val old = repo.setGroupMembers("g:c1", listOf(member("a", "owner"), member("c", "admin")), 3)
        assertEquals(listOf(member("a", "owner"), member("b")), old)
        assertEquals(listOf(member("a", "owner"), member("c", "admin")), repo.groupMembers("g:c1"))
        assertEquals(3L, repo.chat("g:c1")!!.membersVersion)
    }

    @Test fun aStaleOrEqualMembersVersionIsIgnoredWhole() {
        newGroup()
        repo.setGroupMembers("g:c1", listOf(member("a", "owner"), member("b")), 4)
        assertNull(repo.setGroupMembers("g:c1", listOf(member("z")), 4))
        assertNull(repo.setGroupMembers("g:c1", listOf(member("z")), 2))
        assertEquals(listOf(member("a", "owner"), member("b")), repo.groupMembers("g:c1"))
        assertEquals(4L, repo.chat("g:c1")!!.membersVersion)
    }

    @Test fun setGroupMembersRefusesHostileListsAndChangesNothing() {
        newGroup()
        repo.setGroupMembers("g:c1", listOf(member("a", "owner")), 1)
        assertFailsWith<IllegalArgumentException> { repo.setGroupMembers("g:c1", listOf(member("a", "root")), 2) }
        assertFailsWith<IllegalArgumentException> { repo.setGroupMembers("g:c1", listOf(member("a"), member("a")), 2) }
        assertFailsWith<IllegalArgumentException> { repo.setGroupMembers("g:c1", listOf(member("")), 2) }
        assertFailsWith<IllegalArgumentException> { repo.setGroupMembers("g:c1", (1..101).map { member("m$it") }, 2) }
        assertFailsWith<IllegalArgumentException> { repo.setGroupMembers("g:c1", listOf(member("a")), -1) }
        assertEquals(listOf(member("a", "owner")), repo.groupMembers("g:c1"))
        assertEquals(1L, repo.chat("g:c1")!!.membersVersion)
        assertEquals(1, repo.setGroupMembers("g:c1", (1..100).map { member("m$it") }, 2)!!.size)
        assertEquals(100, repo.groupMembers("g:c1").size)
    }

    @Test fun setGroupMembersIgnoresADirectOrUnknownChat() {
        assertNull(repo.setGroupMembers("chat1", listOf(member("a")), 1))
        assertNull(repo.setGroupMembers("nobody", listOf(member("a")), 1))
        assertTrue(repo.groupMembers("chat1").isEmpty())
    }

    @Test fun groupInfoKeepsTheHighestVersionAndValidatesWhatIsStored() {
        newGroup()
        assertTrue(repo.setGroupInfo("g:c1", "New name", photo, 2))
        var g = repo.chat("g:c1")!!
        assertEquals("New name", g.title); assertContentEquals(photo, g.photo); assertEquals(2, g.groupVer)
        assertFalse(repo.setGroupInfo("g:c1", "Same ver", null, 2))
        assertFalse(repo.setGroupInfo("g:c1", "Older", null, 1))
        assertFailsWith<IllegalArgumentException> { repo.setGroupInfo("g:c1", "", null, 3) }
        assertFailsWith<IllegalArgumentException> { repo.setGroupInfo("g:c1", "a".repeat(51), null, 3) }
        assertFailsWith<IllegalArgumentException> { repo.setGroupInfo("g:c1", "Ok", ByteArray(24_577).also { it[0] = -1; it[1] = -40 }, 3) }
        assertFailsWith<IllegalArgumentException> { repo.setGroupInfo("g:c1", "Ok", byteArrayOf(1, 2, 3), 3) }
        assertFalse(repo.setGroupInfo("chat1", "Direct", null, 1))
        g = repo.chat("g:c1")!!
        assertEquals("New name", g.title); assertEquals(2, g.groupVer)
        assertTrue(repo.setGroupInfo("g:c1", "Third", null, 3))
        assertNull(repo.chat("g:c1")!!.photo)
    }

    @Test fun myStateIsOneOfThreeAndNothingElseUndoesIt() {
        newGroup()
        repo.setMyState("g:c1", "removed")
        repo.bumpChat("g:c1", 1, 50); repo.setMuted("g:c1", true)
        assertEquals("removed", repo.chat("g:c1")!!.myState)
        assertFailsWith<IllegalArgumentException> { repo.setMyState("g:c1", "gone") }
        repo.setMyState("g:c1", "left")
        assertEquals("left", repo.chat("g:c1")!!.myState)
    }

    @Test fun receiptsAreUniquePerMemberAndKindAndTheFirstOneStays() {
        repo.insertMessage(message("m1", "x"))
        assertTrue(repo.addReceipt("m1", "bob", "delivered", 100))
        assertFalse(repo.addReceipt("m1", "bob", "delivered", 200))
        assertTrue(repo.addReceipt("m1", "bob", "read", 300))
        assertTrue(repo.addReceipt("m1", "carol", "delivered", 150))
        val all = repo.receipts("m1")
        assertEquals(3, all.size)
        assertEquals(100L, all.single { it.accountId == "bob" && it.kind == "delivered" }.at)
        assertEquals(setOf("bob" to "delivered", "bob" to "read", "carol" to "delivered"), all.map { it.accountId to it.kind }.toSet())
        assertTrue(repo.receipts("nobody").isEmpty())
    }

    @Test fun aReceiptForNoMessageOrWithAnUnknownKindIsNotStored() {
        assertFalse(repo.addReceipt("missing", "bob", "read", 1))
        repo.insertMessage(message("m1", "x"))
        assertFailsWith<IllegalArgumentException> { repo.addReceipt("m1", "bob", "seen", 1) }
        assertTrue(repo.receipts("m1").isEmpty())
    }

    @Test fun deletingAChatRemovesItsMembersAndAMessagesReceipts() {
        newGroup()
        repo.setGroupMembers("g:c1", listOf(member("a", "owner")), 1)
        repo.insertMessage(message("m1", "x", chat = "g:c1"))
        repo.addReceipt("m1", "a", "read", 5)
        repo.deleteChat("g:c1")
        assertTrue(repo.groupMembers("g:c1").isEmpty())
        assertTrue(repo.receipts("m1").isEmpty())
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM group_members").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM receipts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun wipeRemovesGroupsMembersAndReceipts() {
        newGroup()
        repo.setGroupMembers("g:c1", listOf(member("a", "owner")), 1)
        repo.insertMessage(message("m1", "x", chat = "g:c1"))
        repo.addReceipt("m1", "a", "read", 5)
        repo.wipe()
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM group_members").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM receipts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun senderOfAGroupMessageIsStoredPerMessage() {
        newGroup()
        repo.insertMessage(message("m1", "x", chat = "g:c1").copy(senderAccount = "bob"))
        repo.insertMessage(message("m2", "y", chat = "g:c1", sentAt = 2_000).copy(senderAccount = "carol"))
        assertEquals(listOf("bob", "carol"), repo.messages("g:c1", null, 10).map { it.senderAccount })
    }
}
