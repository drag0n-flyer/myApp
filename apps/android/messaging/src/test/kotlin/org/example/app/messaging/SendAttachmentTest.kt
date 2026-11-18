package org.example.app.messaging

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.example.app.contracts.AttachmentRef
import org.example.app.contracts.DeliveryApi
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.MessageContent
import org.example.app.core.settings.MediaKind
import org.example.app.messaging.ports.AttachmentState
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

/** A server that takes uploads, can be made to fail or wait, and counts the calls a sender should not need to make. */
private class UploadApi(private val inner: DeliveryApi) : DeliveryApi by inner {
    val attempts = AtomicInteger()
    val uploads = AtomicInteger()
    val downloads = AtomicInteger()
    @Volatile var failUploads = false
    @Volatile var gate: CompletableDeferred<Unit>? = null
    override suspend fun uploadAttachment(bytes: InputStream, length: Long): AttachmentRef {
        attempts.incrementAndGet()
        bytes.readBytes()
        gate?.await()
        if (failUploads) throw IOException("offline")
        return AttachmentRef("up${uploads.incrementAndGet()}", Instant.EPOCH)
    }
    override suspend fun downloadAttachment(id: String, fromByte: Long): InputStream {
        downloads.incrementAndGet()
        throw IllegalStateException("no download expected")
    }
}

class SendAttachmentTest {
    private fun tmp(n: String): File = Files.createTempDirectory(n).toFile().also { it.deleteOnExit() }

    private class Fixture(val rig: Rig, val d: TestDevice, val dirs: MediaDirs, val preparedDir: File) {
        val prepares = AtomicInteger()
        @Volatile var prepareFails = false
        val preparer = MediaPreparer { path, _ ->
            prepares.incrementAndGet()
            if (prepareFails) null else {
                val copy = File(preparedDir, File(path).name + ".prepared")
                File(path).copyTo(copy, overwrite = true)
                PreparedMedia(copy, name = File(path).name)
            }
        }
    }

    private class Session(val store: LiveChatStore, val api: UploadApi, val scope: CoroutineScope)

    private suspend fun fixture(): Fixture {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return Fixture(rig, rig.bob1, MediaDirs(tmp("blob"), tmp("open"), tmp("temp")), tmp("prepared"))
    }

    /** One app session over the fixture's database; a second one models the app started again. */
    private fun session(f: Fixture): Session {
        val d = f.d
        val api = UploadApi(d.api)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val store = LiveChatStore(
            device = d.me, mls = d.mls, server = api, repo = d.repo, lock = d.lock, clock = d.clock, conversations = d.manager,
            inbox = d.processor, membership = d.membership, sealer = d.sealer(), escrow = d.escrow,
            readReceiptsEnabled = { true }, maxAttachmentBytes = 10_000_000L, dirs = f.dirs,
            social = object : SocialPort {
                override suspend fun lookUpUser(username: String) = null
                override suspend fun block(accountId: String) = Unit
                override suspend fun unblock(accountId: String) = Unit
                override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
            },
            scope = scope, mediaPreparer = f.preparer, mediaSweep = {},
        )
        return Session(store, api, scope)
    }

    private fun sentId(f: Fixture) = f.d.repo.messages("acc-ana", null, 50).last { it.senderAccount == f.d.me.accountId }.id
    private fun statusOf(f: Fixture, id: String) = f.d.repo.message(id)!!.status

    private fun source(dir: File, text: String = "hello there") = File(dir, "notes.txt").also { it.writeBytes(text.encodeToByteArray()) }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun aFileISentIsLocalOnMyDeviceAndOpensWithoutANetworkCall() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        s.store.sendAttachment(source(tmp("src")).path, MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        val record = assertNotNull(f.d.repo.attachmentForMessage(id))
        assertEquals(AttachmentState.LOCAL, record.state)
        val blob = File(assertNotNull(record.localPath))
        assertTrue(blob.isFile, "the ciphertext stays")
        assertEquals(f.dirs.blobDir, blob.parentFile)
        val opened = assertNotNull(s.store.openAttachment(id))
        assertEquals("hello there", opened.file.readText())
        opened.close()
        assertEquals(0, s.api.downloads.get())
    }

    @Test
    fun theRowShowsAsSendingBeforeTheUploadFinishes() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        val gate = CompletableDeferred<Unit>()
        s.api.gate = gate
        s.store.sendAttachment(source(tmp("src")).path, MediaKind.File, "acc-ana")
        waitUntil("the upload to start") { s.api.attempts.get() == 1 }
        val id = sentId(f)
        assertEquals("sending", statusOf(f, id))
        val line = s.store.chat("acc-ana")!!.messages.last { it.id == id }
        assertEquals(DeliveryStatus.Sending, line.status)
        assertTrue(line.content is MessageContent.File)
        gate.complete(Unit)
        s.store.settle()
        assertEquals(AttachmentState.LOCAL, f.d.repo.attachmentForMessage(id)!!.state)
        assertEquals(1, f.d.repo.messages("acc-ana", null, 50).count { it.senderAccount == f.d.me.accountId })
    }

    @Test
    fun aFailedUploadShowsAsFailedKeepsTheFileAndTryAgainSendsOnce() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        s.api.failUploads = true
        s.store.sendAttachment(source(tmp("src")).path, MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        assertEquals("failed", statusOf(f, id))
        assertTrue(id in s.store.failedMessageIds("acc-ana"))
        val prepared = f.preparedDir.listFiles()!!.single()
        assertNull(f.d.repo.attachmentForMessage(id), "nothing was uploaded")

        s.api.failUploads = false
        s.store.retrySend(id)
        s.store.retrySend(id)
        s.store.awaitWrites()
        s.store.settle()
        assertEquals(1, s.api.uploads.get(), "one upload, however many taps")
        assertEquals(2, s.api.attempts.get())
        assertEquals(1, f.prepares.get(), "the kept file is uploaded again, not prepared again")
        assertEquals(AttachmentState.LOCAL, f.d.repo.attachmentForMessage(id)!!.state)
        assertFalse(statusOf(f, id) == "failed")
        assertFalse(prepared.exists(), "the kept copy goes after the send")
        assertEquals(1, f.d.repo.messages("acc-ana", null, 50).count { it.senderAccount == f.d.me.accountId })
    }

    @Test
    fun aFileThatCouldNotBePreparedShowsAsFailed() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        f.prepareFails = true
        s.store.sendAttachment(source(tmp("src")).path, MediaKind.File, "acc-ana")
        s.store.settle()
        assertEquals("failed", statusOf(f, sentId(f)))
        assertEquals(0, s.api.attempts.get())
    }

    @Test
    fun aSentFileLeavesNoStagedCopyInsideTheTempFolderAndNeverTouchesOtherFiles() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        val staged = source(File(f.dirs.tempDir, "files").also { it.mkdirs() })
        val outside = source(tmp("elsewhere"), "mine")
        s.store.sendAttachment(staged.path, MediaKind.File, "acc-ana")
        s.store.sendAttachment(outside.path, MediaKind.File, "acc-ana")
        s.store.settle()
        assertFalse(staged.exists(), "a copy staged in the temp folder is removed")
        assertTrue(outside.exists(), "a file anywhere else is left alone")
        assertEquals(emptyList(), f.preparedDir.listFiles()!!.toList())
        // A path that only looks like it is inside the folder (`..`) is not inside it.
        val sneaky = File(f.dirs.tempDir, "../${outside.parentFile.name}-x").also { it.writeText("x") }
        val sneakyPath = File(f.dirs.tempDir, "files/../../${sneaky.name}").path
        s.store.sendAttachment(sneakyPath, MediaKind.File, "acc-ana")
        s.store.settle()
        assertTrue(sneaky.exists())
        sneaky.delete()
    }

    @Test
    fun aRowLeftSendingByARestartIsFailedAndCanBeTriedAgain() = runBlocking<Unit> {
        val f = fixture()
        val first = session(f)
        first.api.gate = CompletableDeferred() // never completes: the app is killed mid-upload
        first.store.sendAttachment(source(tmp("src")).path, MediaKind.File, "acc-ana")
        waitUntil("the upload to start") { first.api.attempts.get() == 1 }
        val id = sentId(f)
        first.scope.cancel()
        assertEquals("sending", statusOf(f, id))

        val second = session(f)
        second.store.sweepStartup()
        assertEquals("failed", statusOf(f, id))
        second.store.retrySend(id)
        second.store.awaitWrites()
        second.store.settle()
        assertEquals(1, second.api.uploads.get())
        assertEquals(AttachmentState.LOCAL, f.d.repo.attachmentForMessage(id)!!.state)
    }

    @Test
    fun openingNeverDownloadsAndDoesNothingAfterClose() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        s.store.sendAttachment(source(tmp("src")).path, MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        val blob = File(f.d.repo.attachmentForMessage(id)!!.localPath!!)

        // The blob is gone: the open path does not fetch it again.
        val saved = blob.readBytes()
        blob.delete()
        assertNull(s.store.openAttachment(id))
        assertEquals(0, s.api.downloads.get())
        assertEquals(emptyList(), f.dirs.blobDir.list()!!.toList(), "nothing is created in the blob folder")

        // Closed: the same message does not open even though its blob is back.
        blob.writeBytes(saved)
        s.store.shutdown()
        assertNull(s.store.openAttachment(id))
        assertEquals(emptyList(), f.dirs.openDir.list()!!.toList())
    }
}
