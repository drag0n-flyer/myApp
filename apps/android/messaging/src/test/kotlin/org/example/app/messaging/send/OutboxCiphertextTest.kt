package org.example.app.messaging.send

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.GroupId
import org.example.app.core.people.Expiry
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.sync.ModePin
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

/**
 * A queued message is encrypted once per epoch and the same bytes are posted again on every retry. Each `encrypt`
 * advances the sender's MLS generation, and a receiver skips at most 1,024 of them, so a device that is offline for
 * days must not burn one on every attempt.
 */
class OutboxCiphertextTest {
    private suspend fun joined(): Pair<Rig, ConversationRecord> {
        val rig = Rig("e2ee").start()
        val record = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        rig.ana2.processor.sync()
        return rig to record
    }

    private fun text(id: String, t: String = "hi") = ContentEnvelope.Text(id, NOW, t)

    /** Moves the clock to when the oldest queued row is due. */
    private fun TestDevice.waitForBackoff() {
        nowMs = maxOf(nowMs, repo.dueOutboxHeads(Long.MAX_VALUE).minOf { it.nextAttemptAt })
    }

    /** A commit by ana1 (removes bob2), which moves the group to the next epoch. Once per test. */
    private suspend fun advanceEpoch(rig: Rig, record: ConversationRecord) {
        rig.ana1.manager.postCommitWithRetry(record.serverId, GroupId(record.groupId), emptyList(), listOf("bob2")) {
            rig.ana1.mls.remove(listOf("acc-bob/bob2"), GroupId(record.groupId))
        }
    }

    @Test
    fun failedPostsReuseOneCiphertext() = runTest {
        val (rig, _) = joined()
        val before = rig.ana1.mls.encryptCalls
        rig.server.failNextMessages = 5
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        repeat(5) {
            rig.ana1.outbox.flush()
            rig.ana1.waitForBackoff()
        }
        rig.ana1.outbox.flush()
        assertEquals("sent", rig.ana1.repo.message("m-1")?.status)
        assertEquals(1, rig.ana1.mls.encryptCalls - before, "one encrypt for six attempts")
        val attempts = rig.server.postAttempts.filter { it.clientMsgId == "m-1" }
        assertEquals(6, attempts.size)
        attempts.forEach { assertContentEquals(attempts[0].payload, it.payload) }
        assertEquals(1, attempts.map { it.clientMsgId }.toSet().size)
        rig.bob1.processor.sync()
        assertEquals("m-1", rig.bob1.repo.messages("acc-ana", null, 10).single { !it.undecryptable }.id)
        rig.assertLockDiscipline()
    }

    @Test
    fun epochChangeEncryptsOnceMoreAndPostsTheNewBytes() = runTest {
        val (rig, record) = joined()
        val before = rig.ana1.mls.encryptCalls
        rig.server.failNextMessages = 2
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.outbox.flush()
        rig.ana1.waitForBackoff()
        rig.ana1.outbox.flush()
        assertEquals(1, rig.ana1.mls.encryptCalls - before)
        val stored = rig.ana1.repo.dueOutbox(Long.MAX_VALUE).single()
        assertNotNull(stored.ciphertext)
        val oldEpoch = stored.cipherEpoch

        advanceEpoch(rig, record)
        rig.ana1.waitForBackoff()
        rig.ana1.outbox.flush()
        assertEquals(2, rig.ana1.mls.encryptCalls - before, "exactly one more encrypt in the new epoch")
        val attempts = rig.server.postAttempts.filter { it.clientMsgId == "m-1" }
        assertEquals(3, attempts.size)
        assertContentEquals(attempts[0].payload, attempts[1].payload)
        assertFalse(attempts[1].payload.contentEquals(attempts[2].payload), "new bytes after the epoch changed")
        assertEquals(oldEpoch!! + 1, attempts[2].epoch)
        assertEquals("sent", rig.ana1.repo.message("m-1")?.status)
    }

    @Test
    fun epochChangeWhileTheCiphertextIsStoredReplacesIt() = runTest {
        val (rig, record) = joined()
        rig.server.failNextMessages = 1
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.outbox.flush()
        val old = rig.ana1.repo.dueOutbox(Long.MAX_VALUE).single()
        advanceEpoch(rig, record)
        rig.server.failNextMessages = 1
        rig.ana1.waitForBackoff()
        rig.ana1.outbox.flush()
        val fresh = rig.ana1.repo.dueOutbox(Long.MAX_VALUE).single()
        assertEquals(old.cipherEpoch!! + 1, fresh.cipherEpoch)
        assertFalse(old.ciphertext!!.contentEquals(fresh.ciphertext))
    }

    @Test
    fun restartBetweenAttemptsDoesNotEncryptAgain() = runTest {
        val (rig, _) = joined()
        val before = rig.ana1.mls.encryptCalls
        rig.server.failNextMessages = 2
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.outbox.flush()
        rig.ana1.restart()
        rig.ana1.waitForBackoff()
        rig.ana1.outbox.flush()
        rig.ana1.restart()
        rig.ana1.waitForBackoff()
        rig.ana1.outbox.flush()
        assertEquals(1, rig.ana1.mls.encryptCalls - before)
        val attempts = rig.server.postAttempts.filter { it.clientMsgId == "m-1" }
        assertEquals(3, attempts.size)
        attempts.forEach { assertContentEquals(attempts[0].payload, it.payload) }
        assertEquals("sent", rig.ana1.repo.message("m-1")?.status)
    }

    @Test
    fun replacedEnvelopeIsEncryptedAgainAndDeliveredLeavesNothingStored() = runTest {
        val (rig, _) = joined()
        val before = rig.ana1.mls.encryptCalls
        rig.server.failNextMessages = 1
        rig.ana1.outbox.enqueue(text("m-1", "first"), "acc-bob")
        rig.ana1.outbox.flush()
        assertNotNull(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).single().ciphertext)

        // The envelope of the unsent message is replaced: the stored ciphertext goes with it.
        val replaced = ContentCodec.encode(text("m-1", "second"))
        assertTrue(rig.ana1.repo.replaceUnsentEnvelope("m-1", rig.ana1.me.accountId, replaced, "second", NOW))
        assertNull(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).single().ciphertext)
        assertNull(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).single().cipherEpoch)

        // Upload completion queues the finished envelope in place of the old row.
        rig.ana1.outbox.enqueue(text("m-1", "second"), "acc-bob")
        assertNull(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).single().ciphertext)
        rig.ana1.waitForBackoff()
        rig.ana1.nowMs = NOW + 10_000
        rig.ana1.outbox.flush()
        assertEquals(2, rig.ana1.mls.encryptCalls - before, "the replaced envelope is encrypted afresh")
        assertEquals("sent", rig.ana1.repo.message("m-1")?.status)
        assertTrue(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).isEmpty(), "a delivered message leaves no row and no ciphertext")
    }

    @Test
    fun expiredMessageLeavesNoStoredCiphertext() = runTest {
        val (rig, _) = joined()
        rig.server.failNextMessages = 1
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.outbox.flush()
        assertNotNull(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).single().ciphertext)
        rig.ana1.nowMs = NOW + 24 * 3_600_000L + 1
        rig.ana1.outbox.flush()
        assertEquals("failed", rig.ana1.repo.message("m-1")?.status)
        assertTrue(rig.ana1.repo.dueOutbox(Long.MAX_VALUE).isEmpty())
    }

    /** A `mode` envelope that did not get through is retried by every later send; it must not burn a generation each time. */
    @Test
    fun pendingModeEnvelopeIsEncryptedOncePerEpochToo() = runTest {
        val (rig, record) = joined()
        ModePin.setPending(rig.ana1.repo, record.serverId, true)
        val before = rig.ana1.mls.encryptCalls
        val postsBefore = rig.server.postAttempts.size
        rig.server.failNextMessages = 4
        repeat(4) { assertFailsWith<Exception> { rig.ana1.manager.preferredConversation("acc-bob") } }
        rig.ana1.restart()
        assertFailsWith<Exception> { rig.server.failNextMessages = 1; rig.ana1.manager.preferredConversation("acc-bob") }
        rig.ana1.manager.preferredConversation("acc-bob")
        assertEquals(1, rig.ana1.mls.encryptCalls - before, "one encrypt for six attempts")
        val attempts = rig.server.postAttempts.drop(postsBefore)
        assertEquals(6, attempts.size)
        attempts.forEach {
            assertContentEquals(attempts[0].payload, it.payload)
            assertEquals(attempts[0].clientMsgId, it.clientMsgId)
        }
        assertFalse(ModePin.isPending(rig.ana1.repo, record.serverId))

        // After a change of epoch a new ciphertext is made (same envelope ID, so the server still deduplicates).
        ModePin.setPending(rig.ana1.repo, record.serverId, true)
        rig.server.failNextMessages = 1
        assertFailsWith<Exception> { rig.ana1.manager.preferredConversation("acc-bob") }
        advanceEpoch(rig, record)
        rig.ana1.manager.preferredConversation("acc-bob")
        assertEquals(3, rig.ana1.mls.encryptCalls - before)
    }

    /** Two conversations with one peer can be in the same epoch number: the epoch alone does not say who the bytes are for. */
    @Test
    fun ciphertextMadeForAnotherConversationIsNotPosted() = runTest {
        val (rig, record) = joined()
        val before = rig.ana1.mls.encryptCalls
        rig.server.failNextMessages = 1
        rig.ana1.outbox.enqueue(text("m-1"), "acc-bob")
        rig.ana1.outbox.flush()
        val row = rig.ana1.repo.dueOutbox(Long.MAX_VALUE).single()
        assertEquals(record.serverId, row.cipherConversation)
        // The preferred conversation of the chat is now a different one, in the same epoch number.
        val foreign = byteArrayOf(9, 9, 9)
        rig.ana1.repo.storeCiphertext("m-1", "srv-other", row.cipherEpoch!!, foreign)
        rig.ana1.waitForBackoff()
        rig.ana1.outbox.flush()
        assertEquals(2, rig.ana1.mls.encryptCalls - before, "encrypted again for this conversation")
        val posted = rig.server.postAttempts.last { it.clientMsgId == "m-1" }
        assertFalse(posted.payload.contentEquals(foreign), "the old bytes are not posted to the new conversation")
        assertEquals(record.serverId, posted.conversationId)
        assertEquals("sent", rig.ana1.repo.message("m-1")?.status)
    }

    @Test
    fun reusedCiphertextRePostStillCarriesTheExpiry() = runTest {
        val (rig, _) = joined()
        val before = rig.ana1.mls.encryptCalls
        rig.server.failNextMessages = 2
        rig.ana1.outbox.enqueue(ContentEnvelope.Text("m-1", NOW, "hi", ttl = 3600), "acc-bob")
        repeat(2) { rig.ana1.outbox.flush(); rig.ana1.waitForBackoff() }
        rig.ana1.outbox.flush()
        assertEquals(1, rig.ana1.mls.encryptCalls - before)
        val attempts = rig.server.postAttempts.filter { it.clientMsgId == "m-1" }
        assertEquals(3, attempts.size)
        attempts.forEach { assertEquals(Expiry.expiresAt(NOW, 3600), it.expiresAtMs) }
    }

    /**
     * The server deduplicates on the client message ID whatever the epoch, so an announce at a new epoch must carry a
     * new ID: the first post may have been stored (its reply lost) and the new member never saw it.
     */
    @Test
    fun announceAtANewEpochUsesANewMessageId() = runTest {
        val (rig, record) = joined()
        ModePin.setPending(rig.ana1.repo, record.serverId, true)
        val postsBefore = rig.server.postAttempts.size
        rig.server.afterMessageApplied = { throw org.example.app.contracts.ServerException(0, "network", null, null) }
        assertFailsWith<Exception> { rig.ana1.manager.preferredConversation("acc-bob") }
        rig.server.afterMessageApplied = null
        advanceEpoch(rig, record)
        rig.ana1.manager.preferredConversation("acc-bob")
        val attempts = rig.server.postAttempts.drop(postsBefore)
        assertEquals(2, attempts.size)
        assertEquals(attempts[0].epoch + 1, attempts[1].epoch)
        assertFalse(attempts[0].clientMsgId == attempts[1].clientMsgId, "a new ID at the new epoch")
    }

    @Test
    fun savedAnnounceWithAnImplausibleIdIsIgnored() = runTest {
        val (rig, record) = joined()
        val key = "mode-announce:${record.serverId}"
        val epoch = rig.ana1.mls.epoch(org.example.app.contracts.GroupId(record.groupId))
        for (id in listOf("not-a-uuid", "x".repeat(200), "")) {
            val bytes = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(bytes).use {
                it.writeUTF(id); it.writeLong(NOW); it.writeLong(epoch); it.writeInt(3); it.write(byteArrayOf(1, 2, 3))
            }
            ModePin.setPending(rig.ana1.repo, record.serverId, true)
            rig.ana1.repo.set(key, bytes.toByteArray())
            val before = rig.ana1.mls.encryptCalls
            val postsBefore = rig.server.postAttempts.size
            rig.ana1.manager.preferredConversation("acc-bob")
            assertEquals(1, rig.ana1.mls.encryptCalls - before)
            val posted = rig.server.postAttempts.drop(postsBefore).single()
            assertFalse(posted.payload.contentEquals(byteArrayOf(1, 2, 3)))
            assertFalse(posted.clientMsgId == id)
        }
    }

    @Test
    fun settingThePendingMarkerAgainDropsASavedAnnounce() = runTest {
        val (rig, record) = joined()
        val key = "mode-announce:${record.serverId}"
        rig.ana1.repo.set(key, byteArrayOf(1, 2, 3))
        ModePin.setPending(rig.ana1.repo, record.serverId, true)
        assertTrue((rig.ana1.repo.get(key) ?: ByteArray(0)).isEmpty())
    }

    @Test
    fun corruptedModeAnnounceValueFallsBackToAFreshEncrypt() = runTest {
        val (rig, record) = joined()
        val key = "mode-announce:${record.serverId}"
        for (garbage in listOf(byteArrayOf(1, 2, 3), byteArrayOf(0, 3, 'a'.code.toByte()), ByteArray(40) { 0x7f })) {
            ModePin.setPending(rig.ana1.repo, record.serverId, true)
            rig.ana1.repo.set(key, garbage)
            val before = rig.ana1.mls.encryptCalls
            val postsBefore = rig.server.postAttempts.size
            rig.ana1.manager.preferredConversation("acc-bob")
            assertEquals(1, rig.ana1.mls.encryptCalls - before)
            assertEquals(1, rig.server.postAttempts.size - postsBefore)
            assertFalse(ModePin.isPending(rig.ana1.repo, record.serverId))
        }
    }

    @Test
    fun fiveHundredFailedAttemptsEncryptOncePerMessage() = runTest {
        val (rig, _) = joined()
        val before = rig.ana1.mls.encryptCalls
        val messages = 50
        for (i in 1..messages) rig.ana1.outbox.enqueue(text("m-$i", "t$i"), "acc-bob")
        rig.server.failNextMessages = 500
        var guard = 0
        while (rig.server.postAttempts.size < 500 && guard++ < 2000) {
            rig.ana1.outbox.flush()
            if (rig.ana1.repo.dueOutbox(Long.MAX_VALUE).isEmpty()) break
            rig.ana1.waitForBackoff()
        }
        assertEquals(500, rig.server.postAttempts.size)
        assertEquals(messages, rig.ana1.mls.encryptCalls - before)
    }
}
