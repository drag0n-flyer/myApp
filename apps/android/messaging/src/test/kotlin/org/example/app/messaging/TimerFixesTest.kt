package org.example.app.messaging

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.MessageContent
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.LinkPreview
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

/** Review fixes for the timer work (task P3): one transaction, expired uploads discarded, edits keep `ttl` and the preview. */
class TimerFixesTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private class Social : SocialPort {
        override suspend fun lookUpUser(username: String) = null
        override suspend fun block(accountId: String) = Unit
        override suspend fun unblock(accountId: String) = Unit
        override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
    }

    private val mediaRoot: File = Files.createTempDirectory("media").toFile().also { it.deleteOnExit() }

    private fun store(d: TestDevice) = LiveChatStore(
        device = d.me, mls = d.mls, server = d.api, repo = d.repo, lock = d.lock, clock = d.clock, conversations = d.manager,
        inbox = d.processor, membership = d.membership, sealer = d.sealer(), escrow = d.escrow,
        readReceiptsEnabled = { true }, maxAttachmentBytes = 1234L,
        dirs = MediaDirs(File(mediaRoot, "blobs"), File(mediaRoot, "open"), File(mediaRoot, "outgoing")), social = Social(), scope = scope,
    )

    private suspend fun rig(): Rig {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return rig
    }

    private fun ownText(id: String, env: ContentEnvelope.Text, expiresAt: Long? = null, status: String = "sent") = MessageRecord(
        id = id, chatId = "acc-ana", conversationId = null, senderAccount = "acc-bob", senderDevice = "bob1", sentAt = NOW, receivedAt = NOW,
        envelope = ContentCodec.encode(env), status = status, folded = env.t, expiresAt = expiresAt,
    )

    @Test fun aFailureWhileQueuingTheTimerLeavesTheTimerAsItWas() = runTest {
        val rig = rig()
        val st = store(rig.bob1)
        rig.bob1.repo.failNextTransactions = 1
        st.setTimer("acc-ana", 604_800); st.settle()
        assertEquals(0, rig.bob1.repo.timerSeconds("acc-ana"), "column unchanged")
        assertNull(rig.bob1.repo.get("timer-at:acc-ana"), "no timestamp")
        assertTrue(st.messages("acc-ana", null, 20).none { it.content is MessageContent.System }, "no local line")
        assertEquals(0, rig.server.messages.count { it.sender == "acc-bob/bob1" }, "nothing sent")
        // And the next try works.
        st.setTimer("acc-ana", 604_800); st.settle()
        assertEquals(604_800, rig.bob1.repo.timerSeconds("acc-ana"))
        assertEquals(1, rig.server.messages.count { it.sender == "acc-bob/bob1" })
    }

    @Test fun aSweptFailedUploadLeavesNoPendingFolderOrKey() = runTest {
        val rig = rig()
        val st = store(rig.bob1)
        val pending = File(mediaRoot, "pending/p1").also { it.mkdirs() }
        File(pending, "prepared.bin").writeText("x")
        rig.bob1.repo.set("pending.upload.p1", byteArrayOf(1))
        rig.bob1.repo.insertMessage(ownText("p1", ContentEnvelope.Text("p1", NOW, "photo placeholder", ttl = 60), expiresAt = NOW + 60_000, status = "failed"))

        assertEquals(listOf("p1"), st.sweepExpired(NOW + 60_000))
        assertFalse(pending.exists(), "the pending folder is gone")
        assertTrue(rig.bob1.repo.get("pending.upload.p1")?.isEmpty() ?: true, "the pending key is cleared")
    }

    @Test fun editingKeepsTtlAndThePreviewWhileTheLinkIsStillThere() = runTest {
        val rig = rig()
        val st = store(rig.bob1)
        val lp = LinkPreview("https://example.com/a", "Example")
        rig.bob1.repo.insertMessage(ownText("e1", ContentEnvelope.Text("e1", NOW, "look https://example.com/a", ttl = 600, lp = lp), expiresAt = NOW + 600_000))
        st.edit("e1", "acc-ana", "see https://example.com/a now"); st.settle()
        val kept = assertIs<ContentEnvelope.Text>(ContentCodec.decode(rig.bob1.repo.message("e1")!!.envelope))
        assertEquals("see https://example.com/a now", kept.t)
        assertEquals(600, kept.ttl)
        assertEquals(lp, kept.lp)
        assertEquals(NOW + 600_000, rig.bob1.repo.message("e1")!!.expiresAt)

        st.edit("e1", "acc-ana", "no link any more"); st.settle()
        val dropped = assertIs<ContentEnvelope.Text>(ContentCodec.decode(rig.bob1.repo.message("e1")!!.envelope))
        assertEquals(600, dropped.ttl)
        assertNull(dropped.lp, "a preview whose link is gone from the text goes with it")
    }

    @Test fun anEditArrivingFromThePeerKeepsTtl() = runTest {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        val conv = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        rig.ana1.send(conv, ContentEnvelope.Text("x", NOW, "hi", ttl = 600))
        rig.bob1.processor.sync()
        rig.ana1.send(conv, ContentEnvelope.Edit("ed", NOW + 1, "x", "hello"))
        rig.bob1.processor.sync()
        val e = assertIs<ContentEnvelope.Text>(ContentCodec.decode(rig.bob1.repo.message("x")!!.envelope))
        assertEquals("hello", e.t)
        assertEquals(600, e.ttl)
    }

    // Tie-break at the same millisecond

    private suspend fun deliverTimer(rig: Rig, conv: org.example.app.messaging.ports.ConversationRecord, id: String, seconds: Int) {
        rig.ana1.send(conv, ContentEnvelope.Timer(id, NOW, seconds))
        rig.bob1.processor.sync()
    }

    @Test fun atTheSameMillisecondTheGreaterEnvelopeIdWinsAndASmallerOneLoses() = runTest {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        val conv = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        deliverTimer(rig, conv, "m-5", 604_800)
        assertEquals(604_800, rig.bob1.repo.timerSeconds("acc-ana"))
        assertEquals("m-5", rig.bob1.repo.get("timer-id:acc-ana")!!.decodeToString())
        deliverTimer(rig, conv, "m-7", 86_400) // greater id, same time: applies
        assertEquals(86_400, rig.bob1.repo.timerSeconds("acc-ana"))
        deliverTimer(rig, conv, "m-3", 0) // smaller id: ignored
        assertEquals(86_400, rig.bob1.repo.timerSeconds("acc-ana"))
        deliverTimer(rig, conv, "m-7", 0) // the same id again: ignored
        assertEquals(86_400, rig.bob1.repo.timerSeconds("acc-ana"))
        assertEquals("m-7", rig.bob1.repo.get("timer-id:acc-ana")!!.decodeToString())
    }

    @Test fun withoutAStoredIdATieStillApplies() = runTest {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        val conv = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        rig.bob1.repo.set("timer-at:acc-ana", NOW.toString().encodeToByteArray()) // older data: a time but no id
        deliverTimer(rig, conv, "a", 604_800)
        assertEquals(604_800, rig.bob1.repo.timerSeconds("acc-ana"))
    }

    @Test fun theLocallyStoredIdIsTheIdOfTheQueuedControl() = runTest {
        val rig = rig()
        val st = store(rig.bob1)
        st.setTimer("acc-ana", 86_400); st.settle()
        val id = rig.bob1.repo.get("timer-id:acc-ana")!!.decodeToString()
        assertTrue(Regex("[0-9a-f-]{36}").matches(id), "a lowercase UUID: $id")
        assertEquals(listOf(id), rig.server.messages.filter { it.sender == "acc-bob/bob1" }.map { it.clientMsgId })
    }

    @Test fun twoDevicesThatSetDifferentValuesAtTheSameMillisecondEndOnTheSameValue() = runTest {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        val bob = store(rig.bob1)
        val ana = store(rig.ana1)
        bob.setTimer("acc-ana", 86_400)
        ana.setTimer("acc-bob", 604_800)
        bob.settle(); ana.settle()
        val bobId = rig.bob1.repo.get("timer-id:acc-ana")!!.decodeToString()
        val anaId = rig.ana1.repo.get("timer-id:acc-bob")!!.decodeToString()
        rig.bob1.processor.sync(); rig.ana1.processor.sync()
        val winner = if (bobId > anaId) 86_400 else 604_800
        assertEquals(winner, rig.bob1.repo.timerSeconds("acc-ana"))
        assertEquals(winner, rig.ana1.repo.timerSeconds("acc-bob"))
    }

    @Test fun sweepingRemovesTheDecryptedCopyInTheOpenFolderOfThatAttachmentOnly() = runTest {
        val rig = rig()
        val st = store(rig.bob1)
        val open = File(mediaRoot, "open").also { it.mkdirs() }
        val mine = File(open, org.example.app.messaging.media.Downloader.openName("att-gone", ByteArray(1), ByteArray(1))).also { it.writeText("plain") }
        val other = File(open, org.example.app.messaging.media.Downloader.openName("att-keep", ByteArray(1), ByteArray(1))).also { it.writeText("plain") }
        rig.bob1.repo.insertMessage(ownText("g1", ContentEnvelope.Text("g1", NOW, "photo", ttl = 60), expiresAt = NOW + 60_000))
        rig.bob1.repo.insertMessage(ownText("k1", ContentEnvelope.Text("k1", NOW, "photo", ttl = 600), expiresAt = NOW + 600_000))
        rig.bob1.repo.upsertAttachment(org.example.app.messaging.ports.AttachmentRecord("att-gone", "g1", "photo", ByteArray(1), ByteArray(1), 1, 1, "remote", 0, null))
        rig.bob1.repo.upsertAttachment(org.example.app.messaging.ports.AttachmentRecord("att-keep", "k1", "photo", ByteArray(1), ByteArray(1), 1, 1, "remote", 0, null))
        assertEquals(listOf("g1"), st.sweepExpired(NOW + 60_000))
        assertFalse(mine.exists(), "the plaintext copy of the swept message is gone")
        assertTrue(other.exists(), "another message's copy stays")
    }

    @Test fun anAttachmentIdThatIsAPrefixOfAnotherLeavesTheOthersOpenCopy() = runTest {
        val rig = rig()
        val st = store(rig.bob1)
        val open = File(mediaRoot, "open").also { it.mkdirs() }
        val key = ByteArray(32) { 1 }
        val dg = ByteArray(32) { 2 }
        val mine = File(open, org.example.app.messaging.media.Downloader.openName("att", dg, key)).also { it.writeText("plain") }
        val other = File(open, org.example.app.messaging.media.Downloader.openName("att-x", dg, key)).also { it.writeText("plain") }
        rig.bob1.repo.insertMessage(ownText("g2", ContentEnvelope.Text("g2", NOW, "photo", ttl = 60), expiresAt = NOW + 60_000))
        rig.bob1.repo.insertMessage(ownText("k2", ContentEnvelope.Text("k2", NOW, "photo", ttl = 600), expiresAt = NOW + 600_000))
        rig.bob1.repo.upsertAttachment(org.example.app.messaging.ports.AttachmentRecord("att", "g2", "photo", key, dg, 1, 1, "remote", 0, null))
        rig.bob1.repo.upsertAttachment(org.example.app.messaging.ports.AttachmentRecord("att-x", "k2", "photo", key, dg, 1, 1, "remote", 0, null))
        st.sweepExpired(NOW + 60_000)
        assertFalse(mine.exists())
        assertTrue(other.exists(), "the longer id's copy is not matched by a prefix")
    }
}
