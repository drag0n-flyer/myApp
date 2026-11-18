package org.example.app.messaging

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.core.chats.LinkPreviewCard
import org.example.app.core.chats.MessageContent
import org.example.app.messaging.content.Att
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.content.LinkPreview
import org.example.app.messaging.ports.ConversationRecord
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

private val JPEG = byteArrayOf(-1, -40, 1, 2, 3)
private val ATT = Att("att-1", ByteArray(32), ByteArray(32), 100, 120)

/** Captions on photos and files, edits of them, and link previews on text (task P4), through the real store and handler. */
class CaptionsTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private class Social : SocialPort {
        override suspend fun lookUpUser(username: String) = null
        override suspend fun block(accountId: String) = Unit
        override suspend fun unblock(accountId: String) = Unit
        override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
    }

    private fun tmp(n: String): File = Files.createTempDirectory(n).toFile().also { it.deleteOnExit() }

    private fun store(d: TestDevice) = LiveChatStore(
        device = d.me, mls = d.mls, server = d.api, repo = d.repo, lock = d.lock, clock = d.clock, conversations = d.manager,
        inbox = d.processor, membership = d.membership, sealer = d.sealer(), escrow = d.escrow,
        readReceiptsEnabled = { true }, maxAttachmentBytes = 1234L,
        dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp")), social = Social(), scope = scope,
    )

    private class Setup(val rig: Rig, val conv: ConversationRecord) {
        val bob get() = rig.bob1
        val ana get() = rig.ana1
    }

    private suspend fun setup(): Setup {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        val conv = rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return Setup(rig, conv)
    }

    private suspend fun Setup.deliver(env: ContentEnvelope) {
        ana.send(conv, env)
        bob.processor.sync()
    }

    private fun photo(id: String, c: String?, ttl: Int? = null) = ContentEnvelope.Photo(id, NOW, ATT, 10, 10, null, null, ttl = ttl, c = c)

    // Receive and show

    @Test fun aCaptionedPhotoShowsItsCaptionInTheChat() = runTest {
        val s = setup()
        s.deliver(photo("p1", "the *harbour*"))
        val store = store(s.bob)
        store.refresh()
        val shown = store.chat("acc-ana")!!.messages.single { it.id == "p1" }.content
        assertEquals("the *harbour*", assertIs<MessageContent.Photo>(shown).caption)
    }

    @Test fun aCaptionedFileShowsItsCaptionAndAnUncaptionedOneNone() = runTest {
        val s = setup()
        s.deliver(ContentEnvelope.File("f1", NOW, ATT, "a.pdf", "application/pdf", c = "the contract"))
        s.deliver(ContentEnvelope.File("f2", NOW + 1, Att("att-2", ByteArray(32), ByteArray(32), 100, 120), "b.pdf", "application/pdf"))
        val store = store(s.bob)
        store.refresh()
        val messages = store.chat("acc-ana")!!.messages
        assertEquals("the contract", assertIs<MessageContent.File>(messages.single { it.id == "f1" }.content).caption)
        assertNull(assertIs<MessageContent.File>(messages.single { it.id == "f2" }.content).caption)
    }

    @Test fun aReceivedCaptionIsStoredAndSearchable() = runTest {
        val s = setup()
        s.deliver(photo("p1", "Sunset over the harbour"))
        val row = s.bob.repo.message("p1")!!
        assertEquals("Sunset over the harbour", row.caption)
        assertTrue(row.folded.contains("harbour"))
        assertTrue(s.bob.repo.search("harbour", 10).any { it.messageId == "p1" })
    }

    // Edit

    @Test fun anEditOfACaptionedPhotoChangesTheCaptionOnTheReceiver() = runTest {
        val s = setup()
        s.deliver(photo("p1", "old words", ttl = 3600))
        s.deliver(ContentEnvelope.Edit("e1", NOW + 1, "p1", "new words"))
        val row = s.bob.repo.message("p1")!!
        val env = assertIs<ContentEnvelope.Photo>(ContentCodec.decode(row.envelope))
        assertEquals("new words", env.c)
        assertEquals(ATT, env.a)
        assertEquals(3600, env.ttl)
        assertEquals("new words", row.caption)
        assertTrue(row.folded.contains("new") && !row.folded.contains("old"))
        assertEquals(NOW + 1, row.editedAt)
    }

    @Test fun anEditOfAFileKeepsItsNameInTheSearchText() = runTest {
        val s = setup()
        s.deliver(ContentEnvelope.File("f1", NOW, ATT, "report.pdf", "application/pdf", c = "draft"))
        s.deliver(ContentEnvelope.Edit("e1", NOW + 1, "f1", "final"))
        val row = s.bob.repo.message("f1")!!
        assertEquals("final", (ContentCodec.decode(row.envelope) as ContentEnvelope.File).c)
        assertTrue(row.folded.contains("report.pdf") && row.folded.contains("final") && !row.folded.contains("draft"))
    }

    @Test fun anEditOfAPhotoWithoutACaptionChangesNothing() = runTest {
        val s = setup()
        s.deliver(photo("p1", null))
        s.deliver(ContentEnvelope.Edit("e1", NOW + 1, "p1", "sneaked in"))
        val row = s.bob.repo.message("p1")!!
        assertNull((ContentCodec.decode(row.envelope) as ContentEnvelope.Photo).c)
        assertNull(row.caption)
        assertNull(row.editedAt)
    }

    @Test fun onlyTheSenderEditsACaption() = runTest {
        val s = setup()
        s.deliver(photo("p1", "mine"))
        val bobRecord = assertNotNull(s.bob.repo.conversation(s.conv.serverId))
        s.bob.send(bobRecord, ContentEnvelope.Edit("e1", NOW + 1, "p1", "forged"))
        s.ana.processor.sync()
        s.bob.processor.sync()
        assertEquals("mine", s.bob.repo.message("p1")!!.caption)
    }

    @Test fun myEditOfACaptionChangesMyRowAndSendsAnEdit() = runTest {
        val s = setup()
        val me = s.ana.me.accountId
        val env = photo("p9", "old")
        s.ana.repo.insertMessage(
            MessageRecord(
                id = "p9", chatId = "acc-bob", conversationId = null, senderAccount = me, senderDevice = s.ana.me.deviceId,
                sentAt = NOW, receivedAt = NOW, envelope = ContentCodec.encode(env), status = "sent", folded = "old", caption = "old",
            ),
        )
        val anaStore = store(s.ana)
        anaStore.edit("p9", "acc-bob", "fresh")
        anaStore.awaitWrites()
        val row = s.ana.repo.message("p9")!!
        assertEquals("fresh", (ContentCodec.decode(row.envelope) as ContentEnvelope.Photo).c)
        assertEquals("fresh", row.caption)
        assertTrue(row.folded.contains("fresh"))
        val sent = s.ana.repo.dueOutbox(Long.MAX_VALUE).map { ContentCodec.decode(it.envelope) }
        assertTrue(sent.any { it is ContentEnvelope.Edit && it.target == "p9" && it.t == "fresh" })
    }

    // Link previews on text

    private fun lp(url: String) = LinkPreview(url, "Title", "Desc", JPEG)

    @Test fun anEditKeepsThePreviewWhileItsLinkIsStillInTheText() = runTest {
        val s = setup()
        s.deliver(ContentEnvelope.Text("t1", NOW, "see https://example.org/a", lp = lp("https://example.org/a"), ttl = 3600))
        s.deliver(ContentEnvelope.Edit("e1", NOW + 1, "t1", "look at https://example.org/a now"))
        val row = s.bob.repo.message("t1")!!
        val env = assertIs<ContentEnvelope.Text>(ContentCodec.decode(row.envelope))
        assertEquals("look at https://example.org/a now", env.t)
        assertEquals("https://example.org/a", env.lp!!.url)
        assertEquals(3600, env.ttl)
        assertNotNull(row.linkPreview)
    }

    @Test fun anEditThatRemovesTheLinkRemovesThePreview() = runTest {
        val s = setup()
        s.deliver(ContentEnvelope.Text("t1", NOW, "see https://example.org/a", lp = lp("https://example.org/a")))
        s.deliver(ContentEnvelope.Edit("e1", NOW + 1, "t1", "never mind"))
        val row = s.bob.repo.message("t1")!!
        assertNull((ContentCodec.decode(row.envelope) as ContentEnvelope.Text).lp)
        assertNull(row.linkPreview)
    }

    @Test fun aReceivedPreviewIsShownFromTheMessageAndAnInvalidOneIsDropped() = runTest {
        val s = setup()
        s.deliver(ContentEnvelope.Text("t1", NOW, "see https://example.org/a", lp = lp("https://example.org/a")))
        s.deliver(ContentEnvelope.Text("t2", NOW + 1, "no link in the text", lp = lp("https://example.org/a")))
        val store = store(s.bob)
        store.refresh()
        val messages = store.chat("acc-ana")!!.messages
        val shown = assertIs<MessageContent.Text>(messages.single { it.id == "t1" }.content).preview!!
        assertEquals(LinkPreviewCard("https://example.org/a", "Title", "Desc", JPEG), shown)
        assertNull(assertIs<MessageContent.Text>(messages.single { it.id == "t2" }.content).preview)
    }

    private suspend fun sendWithPreview(text: String, card: LinkPreviewCard?): ContentEnvelope.Text? {
        val s = setup()
        val anaStore = store(s.ana)
        anaStore.send(MessageContent.Text(text, card), "acc-bob")
        anaStore.awaitWrites()
        return s.ana.repo.messages("acc-bob", null, 10).map { ContentCodec.decode(it.envelope) }.filterIsInstance<ContentEnvelope.Text>().singleOrNull()
    }

    @Test fun aSentPreviewTravelsAsLpAndIsStoredOnMyRow() = runTest {
        val sent = sendWithPreview("see https://example.org/a", LinkPreviewCard("https://example.org/a", "Title", "Desc", JPEG))!!
        assertEquals(LinkPreview("https://example.org/a", "Title", "Desc", JPEG), sent.lp)
    }

    @Test fun anInvalidPreviewIsLeftOffAndTheTextStillSends() = runTest {
        val cases = listOf(
            LinkPreviewCard("https://example.org/other", "T"), // url not in the text
            LinkPreviewCard("http://example.org/a", "T"),
            LinkPreviewCard("https://example.org/a", ""),
            LinkPreviewCard("https://example.org/a", "   "),
            LinkPreviewCard("https://example.org/a", "t".repeat(201)),
            LinkPreviewCard("https://example.org/a", "T", "d".repeat(301)),
            LinkPreviewCard("https://example.org/a", "T", null, byteArrayOf(1, 2, 3)),
            LinkPreviewCard("https://example.org/a", "T", null, ByteArray(8_193).also { it[0] = -1; it[1] = -40 }),
            LinkPreviewCard("https://127.0.0.1/a", "T"),
        )
        for (card in cases) {
            val text = if (card.url.endsWith("other")) "see https://example.org/a" else "see ${card.url} now"
            val sent = assertNotNull(sendWithPreview(text, card), card.url)
            assertNull(sent.lp, "preview for ${card.url} / ${card.title.length}")
        }
    }

    @Test fun aTextWithoutAPreviewSendsExactlyAsBefore() = runTest {
        val sent = sendWithPreview("hello", null)!!
        assertNull(sent.lp)
        assertFalse(String(ContentCodec.encode(sent)).contains("\"lp\""))
    }
}
