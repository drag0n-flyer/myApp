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

    @Test fun r1AnEditCanAddACaptionWhereThereWasNone() = runTest {
        val s = setup()
        s.deliver(photo("p1", null))
        s.deliver(ContentEnvelope.Edit("e1", NOW + 1, "p1", "now with words"))
        val row = s.bob.repo.message("p1")!!
        assertEquals("now with words", (ContentCodec.decode(row.envelope) as ContentEnvelope.Photo).c)
        assertEquals("now with words", row.caption)
        assertTrue(row.folded.contains("words"))
        assertEquals(NOW + 1, row.editedAt)
        assertTrue(s.bob.repo.search("words", 10).any { it.messageId == "p1" })
    }

    @Test fun r1AnEditWithEmptyTextClearsTheCaptionOnTheReceiver() = runTest {
        val s = setup()
        s.deliver(ContentEnvelope.File("f1", NOW, ATT, "report.pdf", "application/pdf", c = "draft"))
        s.deliver(ContentEnvelope.Edit("e1", NOW + 1, "f1", ""))
        val row = s.bob.repo.message("f1")!!
        assertNull((ContentCodec.decode(row.envelope) as ContentEnvelope.File).c)
        assertNull(row.caption)
        assertTrue(row.folded.contains("report.pdf") && !row.folded.contains("draft"))
        s.deliver(photo("p2", "x"))
        s.deliver(ContentEnvelope.Edit("e2", NOW + 2, "p2", "   "))
        assertNull(s.bob.repo.message("p2")!!.caption)
        val store = store(s.bob)
        store.refresh()
        assertNull(assertIs<MessageContent.File>(store.chat("acc-ana")!!.messages.single { it.id == "f1" }.content).caption)
    }

    @Test fun r1AnEmptyCaptionOnTheWireIsNoCaption() = runTest {
        val s = setup()
        val raw = String(ContentCodec.encode(photo("p1", "x"))).replace("\"c\":\"x\"", "\"c\":\"\"")
        assertNull((ContentCodec.decode(raw.toByteArray()) as ContentEnvelope.Photo).c)
        s.deliver(ContentCodec.decode(raw.toByteArray()))
        assertNull(s.bob.repo.message("p1")!!.caption)
        val store = store(s.bob)
        store.refresh()
        assertNull(assertIs<MessageContent.Photo>(store.chat("acc-ana")!!.messages.single { it.id == "p1" }.content).caption)
    }

    @Test fun r1ATextEditToBlankIsStillRefused() = runTest {
        val s = setup()
        val me = s.ana.me.accountId
        s.ana.repo.insertMessage(
            MessageRecord(
                id = "t9", chatId = "acc-bob", conversationId = null, senderAccount = me, senderDevice = s.ana.me.deviceId,
                sentAt = NOW, receivedAt = NOW, envelope = ContentCodec.encode(ContentEnvelope.Text("t9", NOW, "hello")), status = "sent", folded = "hello",
            ),
        )
        val anaStore = store(s.ana)
        anaStore.edit("t9", "acc-bob", "   ")
        anaStore.awaitWrites()
        assertEquals("hello", (ContentCodec.decode(s.ana.repo.message("t9")!!.envelope) as ContentEnvelope.Text).t)
    }

    @Test fun r1MyBlankEditRemovesMyCaptionAndMyEditAddsOne() = runTest {
        val s = setup()
        val me = s.ana.me.accountId
        fun own(id: String, c: String?) = s.ana.repo.insertMessage(
            MessageRecord(
                id = id, chatId = "acc-bob", conversationId = null, senderAccount = me, senderDevice = s.ana.me.deviceId,
                sentAt = NOW, receivedAt = NOW, envelope = ContentCodec.encode(photo(id, c)), status = "sent", folded = c.orEmpty(), caption = c,
            ),
        )
        own("a1", "old"); own("a2", null)
        val anaStore = store(s.ana)
        anaStore.edit("a1", "acc-bob", "  ")
        anaStore.edit("a2", "acc-bob", "added")
        anaStore.awaitWrites()
        assertNull((ContentCodec.decode(s.ana.repo.message("a1")!!.envelope) as ContentEnvelope.Photo).c)
        assertNull(s.ana.repo.message("a1")!!.caption)
        assertEquals("added", s.ana.repo.message("a2")!!.caption)
        assertEquals("added", (ContentCodec.decode(s.ana.repo.message("a2")!!.envelope) as ContentEnvelope.Photo).c)
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

    @Test fun r2AnEditAfterTheUploadCommittedWithTheOldCaptionStillReachesTheReceiver() = runTest {
        val s = setup()
        // The receiver has the photo as the upload committed it, with the old caption.
        s.deliver(photo("p8", "old"))
        val me = s.ana.me.accountId
        // On the sender: committed (the pending marker is cleared) but not yet posted, so the status is still sending.
        s.ana.repo.insertMessage(
            MessageRecord(
                id = "p8", chatId = "acc-bob", conversationId = null, senderAccount = me, senderDevice = s.ana.me.deviceId,
                sentAt = NOW, receivedAt = NOW, envelope = ContentCodec.encode(photo("p8", "old")), status = "sending", folded = "old", caption = "old",
            ),
        )
        val anaStore = store(s.ana)
        anaStore.edit("p8", "acc-bob", "fresh")
        anaStore.awaitWrites()
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            s.bob.processor.sync()
            if (s.bob.repo.message("p8")!!.caption == "fresh") break
            Thread.sleep(25)
        }
        assertEquals("fresh", s.bob.repo.message("p8")!!.caption, "the edit control reached the receiver")
    }

    // A hostile preview is cleaned or dropped when it is decoded

    private fun decodedPreview(text: String, lp: LinkPreview): LinkPreview? =
        (ContentCodec.decode(ContentCodec.encode(ContentEnvelope.Text("t", NOW, text, lp = lp))) as ContentEnvelope.Text).lp

    @Test fun aTitleWithDirectionAndControlCharactersIsCleaned() {
        val got = decodedPreview("see https://example.org/a", LinkPreview("https://example.org/a", "ab\u202Ec\u200Bd\u0007e", "x\u2066y\nz"))!!
        assertEquals("abcde", got.title)
        assertEquals("xy z", got.desc)
    }

    @Test fun aBlankOrInvisibleTitleDropsThePreviewButKeepsTheText() {
        for (title in listOf("   ", "\u200B\u200B", "\u202E", "\n\t")) {
            assertNull(decodedPreview("see https://example.org/a", LinkPreview("https://example.org/a", title)), title)
        }
    }

    @Test fun aPreviewUrlThatCheckUrlRefusesIsDropped() {
        for (url in listOf("https://user@example.org/a", "https://user:pw@example.org/a", "https://127.0.0.1/a", "https://example.org:8443/a", "https://localhost/a")) {
            assertNull(decodedPreview("see $url", LinkPreview(url, "Title")), url)
        }
    }

    @Test fun aCleanPreviewIsKeptAsItWas() {
        val lp = LinkPreview("https://example.org/a", "Title", "Desc", JPEG)
        assertEquals(lp, decodedPreview("see https://example.org/a", lp))
    }

    // R6 on the receiver, R3 for the picture

    @Test fun r6TheExtraBlankLookingCharactersAreStrippedOnTheReceiver() {
        for (blank in listOf("\u2028", "\u2029", "\u3164", "\u115F", "\u1160", "\uFFA0", "\u2800")) {
            assertEquals("ab", decodedPreview("see https://example.org/a", LinkPreview("https://example.org/a", "a${blank}b"))!!.title, blank)
            assertNull(decodedPreview("see https://example.org/a", LinkPreview("https://example.org/a", blank + blank)), blank)
        }
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200C"
        assertEquals(family, decodedPreview("see https://example.org/a", LinkPreview("https://example.org/a", family))!!.title)
    }

    private fun jpegOf(w: Int, h: Int) = byteArrayOf(-1, -40, -1, -64, 0, 11, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 1, 1, 0x11, 0)

    @Test fun r3APreviewImageDeclaringMoreThan1024By1024IsRefusedWithoutDecoding() {
        val url = "https://example.org/a"
        assertEquals(1, decodedPreview("see $url", LinkPreview(url, "T", null, jpegOf(1024, 1024)))!!.let { 1 })
        for ((w, h) in listOf(1025 to 10, 10 to 1025, 65535 to 65535, 4000 to 4000)) {
            assertNull(decodedPreview("see $url", LinkPreview(url, "T", null, jpegOf(w, h))), "${w}x$h")
        }
    }
}
