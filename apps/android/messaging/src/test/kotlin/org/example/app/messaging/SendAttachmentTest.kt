package org.example.app.messaging

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.example.app.core.chats.SendProblem
import kotlinx.coroutines.runBlocking
import org.example.app.contracts.AttachmentRef
import org.example.app.contracts.DeliveryApi
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.MessageContent
import org.example.app.core.settings.MediaKind
import org.example.app.messaging.content.Att
import org.example.app.messaging.content.ContentCodec
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.messaging.ports.AttachmentState
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.MessageRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

/** A server that takes uploads, can be made to fail or wait, and counts the calls a sender should not need to make. */
private class UploadApi(private val inner: DeliveryApi) : DeliveryApi by inner {
    val attempts = AtomicInteger()
    val uploads = AtomicInteger()
    val downloads = AtomicInteger()
    /** Messages and controls this device put on the wire. */
    val posts = AtomicInteger()
    override suspend fun postMessage(id: String, epoch: Long, ciphertext: ByteArray, escrow: ByteArray?, clientMsgId: String): Long {
        posts.incrementAndGet()
        return inner.postMessage(id, epoch, ciphertext, escrow, clientMsgId)
    }
    @Volatile var failUploads = false
    /** When set, the server answers uploads with this id. */
    @Volatile var answerId: String? = null
    @Volatile var gate: CompletableDeferred<Unit>? = null
    override suspend fun uploadAttachment(bytes: InputStream, length: Long): AttachmentRef {
        attempts.incrementAndGet()
        bytes.readBytes()
        gate?.await()
        if (failUploads) throw IOException("offline")
        return AttachmentRef(answerId ?: "up${uploads.incrementAndGet()}", Instant.EPOCH)
    }
    override suspend fun downloadAttachment(id: String, fromByte: Long): InputStream {
        downloads.incrementAndGet()
        throw IllegalStateException("no download expected")
    }
}

class SendAttachmentTest {
    private fun tmp(n: String): File = Files.createTempDirectory(n).toFile().also { it.deleteOnExit() }

    private class Fixture(val rig: Rig, val d: TestDevice, val dirs: MediaDirs) {
        /** Where the preparer writes, like `PhotoPrep` in the outgoing folder: inside the temp folder. */
        val preparedDir = File(dirs.tempDir, "prepared")
        val pendingRoot = File(dirs.blobDir.parentFile, "pending")
        val prepares = AtomicInteger()
        @Volatile var prepareFails = false
        /** Throws from the preparer, as a source that is briefly unreadable does. */
        @Volatile var prepareThrows: (() -> Unit)? = null
        @Volatile var duration = 0.0
        @Volatile var width = 0
        @Volatile var height = 0
        @Volatile var thumbnail: ByteArray? = null
        /** Runs right after a prepared copy is written, to hold two sends at the same point. */
        @Volatile var afterCopy: () -> Unit = {}
        val preparer = MediaPreparer { path, _ ->
            prepares.incrementAndGet()
            prepareThrows?.invoke()
            if (prepareFails) null else {
                preparedDir.mkdirs()
                // A unique name, like `PhotoPrep` (`createTempFile`): two sends in flight never share one prepared file.
                val copy = File.createTempFile("prepared", ".bin", preparedDir)
                File(path).copyTo(copy, overwrite = true)
                afterCopy()
                PreparedMedia(copy, width, height, thumbnail, duration, name = File(path).name)
            }
        }
    }

    private class Session(val store: LiveChatStore, val api: UploadApi, val scope: CoroutineScope) {
        /** What the store told the person, in order. */
        val problems = java.util.concurrent.CopyOnWriteArrayList<SendProblem>()
    }

    private suspend fun fixture(): Fixture {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        return Fixture(rig, rig.bob1, MediaDirs(File(tmp("media"), "blobs"), tmp("open"), tmp("temp")))
    }

    /** One app session over the fixture's database; a second one models the app started again (with the start-up sweep). */
    private fun session(f: Fixture, stage: suspend (String, File) -> StageResult = { _, _ -> StageResult.Unreadable }): Session {
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
            scope = scope, mediaPreparer = f.preparer, stageSource = stage,
            // What `MediaCache.sweep` does at app start: the outgoing (temp) folder is emptied.
            mediaSweep = { f.dirs.tempDir.listFiles()?.forEach { it.deleteRecursively() } },
        )
        return Session(store, api, scope).also { s ->
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.sendProblem("acc-ana").collect { r -> if (r != null) s.problems += SendProblem("acc-ana", r) } }
        }
    }

    private fun sentId(f: Fixture) = f.d.repo.messages("acc-ana", null, 50).last { it.senderAccount == f.d.me.accountId }.id
    private fun statusOf(f: Fixture, id: String) = f.d.repo.message(id)!!.status
    private fun pendingDir(f: Fixture, id: String) = File(f.pendingRoot, id)
    private fun pendingKey(f: Fixture, id: String) = f.d.repo.get("pending.upload.$id")?.isNotEmpty() == true

    private fun source(dir: File, text: String = "hello there") = File(dir, "notes.txt").also { it.writeBytes(text.encodeToByteArray()) }
    private fun staged(f: Fixture, text: String = "hello there") = source(File(f.dirs.tempDir, "files").also { it.mkdirs() }, text)

    private fun waitUntil(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    /** What the other person's phone received for [id]: waits until the outbox has delivered it. */
    private suspend fun peerGot(f: Fixture, id: String): ContentEnvelope {
        waitUntil("the message to be sent") { statusOf(f, id) != "sending" }
        f.rig.ana1.processor.sync()
        return ContentCodec.decode(assertNotNull(f.rig.ana1.repo.message(id), "the peer has the message").envelope)
    }

    @Test
    fun aFileISentIsLocalOnMyDeviceOpensWithoutANetworkCallAndReachesThePeer() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        s.store.sendAttachment(source(tmp("src")).path, MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        val record = assertNotNull(f.d.repo.attachmentForMessage(id))
        assertEquals(AttachmentState.LOCAL, record.state)
        assertEquals("file", record.kind)
        assertEquals(11L, record.size)
        assertTrue(record.cipherSize > record.size)
        val blob = File(assertNotNull(record.localPath))
        assertTrue(blob.isFile, "the ciphertext stays")
        assertEquals(f.dirs.blobDir, blob.parentFile)
        val opened = assertNotNull(s.store.openAttachment(id))
        assertEquals("hello there", opened.file.readText())
        opened.close()
        assertEquals(0, s.api.downloads.get())
        // What is queued and sent is the real envelope, not the placeholder.
        val sent = assertIs<ContentEnvelope.File>(peerGot(f, id))
        assertEquals("up1", sent.a.id)
        assertEquals("notes.txt", sent.name)
        assertEquals(11L, sent.a.sz)
    }

    @Test
    fun aPhotoAndAVoiceNoteReachThePeerWithTheirSizeAndLength() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        f.width = 640
        f.height = 480
        f.thumbnail = byteArrayOf(1, 2, 3)
        s.store.sendAttachment(source(tmp("src")).path, MediaKind.Photo, "acc-ana")
        s.store.settle()
        val photo = assertIs<ContentEnvelope.Photo>(peerGot(f, sentId(f)))
        assertEquals(640, photo.w)
        assertEquals(480, photo.h)
        assertEquals(listOf<Byte>(1, 2, 3), photo.th!!.toList())
        assertEquals("photo", f.d.repo.attachmentForMessage(sentId(f))!!.kind)

        f.duration = 7.5
        f.d.nowMs += 1_000 // the test clock is fixed; a later send must sort after the photo
        s.store.sendAttachment(source(tmp("src2")).path, MediaKind.Voice, "acc-ana")
        s.store.settle()
        val voiceId = sentId(f)
        val voice = assertIs<ContentEnvelope.Voice>(peerGot(f, voiceId))
        assertEquals(7.5, voice.dur)
        assertTrue(voice.a.id.isNotEmpty())
        assertEquals("voice", f.d.repo.attachmentForMessage(voiceId)!!.kind)
        assertEquals(MessageContent.Voice(7.5), s.store.chat("acc-ana")!!.messages.last { it.id == voiceId }.content)
    }

    @Test
    fun theRowShowsAsSendingWithItsRealNameAndSizeBeforeTheUploadFinishes() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        val gate = CompletableDeferred<Unit>()
        s.api.gate = gate
        val file = staged(f)
        s.store.sendAttachment(file.path, MediaKind.File, "acc-ana")
        waitUntil("the upload to start") { s.api.attempts.get() == 1 }
        val id = sentId(f)
        assertEquals("sending", statusOf(f, id))
        val line = s.store.chat("acc-ana")!!.messages.last { it.id == id }
        assertEquals(DeliveryStatus.Sending, line.status)
        assertEquals(MessageContent.File("notes.txt", 11), line.content)
        // The staged copy now lives in the media folder, where a restart's sweep does not reach it.
        assertFalse(file.exists())
        assertTrue(pendingDir(f, id).listFiles()!!.isNotEmpty())
        gate.complete(Unit)
        s.store.settle()
        assertEquals(AttachmentState.LOCAL, f.d.repo.attachmentForMessage(id)!!.state)
        assertEquals(1, f.d.repo.messages("acc-ana", null, 50).count { it.senderAccount == f.d.me.accountId })
        assertFalse(pendingDir(f, id).exists(), "nothing is kept after the send")
        assertFalse(pendingKey(f, id))
    }

    @Test
    fun aFailedUploadShowsAsFailedKeepsTheFileAndTryAgainSendsOnce() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        s.api.failUploads = true
        s.store.sendAttachment(staged(f).path, MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        assertEquals("failed", statusOf(f, id))
        assertTrue(id in s.store.failedMessageIds("acc-ana"))
        assertTrue(pendingDir(f, id).listFiles()!!.isNotEmpty(), "the file is kept for Try again")
        assertNull(f.d.repo.attachmentForMessage(id), "nothing was uploaded")

        // The retry is still uploading when it is tapped again: the second tap does nothing.
        s.api.failUploads = false
        val gate = CompletableDeferred<Unit>()
        s.api.gate = gate
        s.store.retrySend(id)
        s.store.awaitWrites()
        waitUntil("the retry to start uploading") { s.api.attempts.get() == 2 }
        s.store.retrySend(id)
        s.store.awaitWrites()
        assertEquals(2, s.api.attempts.get(), "no second upload while the first is running")
        gate.complete(Unit)
        s.store.settle()
        assertEquals(1, s.api.uploads.get(), "one upload, however many taps")
        assertEquals(2, s.api.attempts.get())
        assertEquals(1, f.prepares.get(), "the kept file is uploaded again, not prepared again")
        assertEquals(AttachmentState.LOCAL, f.d.repo.attachmentForMessage(id)!!.state)
        assertFalse(statusOf(f, id) == "failed")
        assertFalse(pendingDir(f, id).exists(), "the kept copy goes after the send")
        assertEquals(1, f.d.repo.messages("acc-ana", null, 50).count { it.senderAccount == f.d.me.accountId })
        assertEquals("up1", assertIs<ContentEnvelope.File>(peerGot(f, id)).a.id)
    }

    @Test
    fun aFileThatCanNeverBeSentIsRemovedNotLeftWithADeadTryAgain() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        f.prepareFails = true
        s.store.sendAttachment(staged(f).path, MediaKind.File, "acc-ana")
        s.store.settle()
        assertEquals(emptyList(), f.d.repo.messages("acc-ana", null, 50).filter { it.senderAccount == f.d.me.accountId })
        assertEquals(0, s.api.attempts.get())
        assertEquals(emptyList(), f.d.repo.entries("pending.upload.").filterValues { it.isNotEmpty() }.keys.toList())
        assertEquals(emptyList(), f.pendingRoot.list().orEmpty().toList())
        assertEquals(emptyList(), f.d.repo.dueOutbox(Long.MAX_VALUE))
    }

    @Test
    fun aSentFileLeavesNoStagedCopyInsideTheTempFolderAndNeverTouchesOtherFiles() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        val inside = staged(f)
        val outside = source(tmp("elsewhere"), "mine")
        s.store.sendAttachment(inside.path, MediaKind.File, "acc-ana")
        s.store.sendAttachment(outside.path, MediaKind.File, "acc-ana")
        s.store.settle()
        assertFalse(inside.exists(), "a copy staged in the temp folder is removed")
        assertTrue(outside.exists(), "a file anywhere else is left alone")
        assertEquals(emptyList(), f.preparedDir.listFiles()!!.toList())
        assertEquals(emptyList(), f.pendingRoot.list().orEmpty().toList())
        // A path that only looks like it is inside the folder (`..`) is not inside it.
        val sneaky = File(f.dirs.tempDir, "../${outside.parentFile.name}-x").also { it.writeText("x") }
        val sneakyPath = File(f.dirs.tempDir, "files/../../${sneaky.name}").path
        s.store.sendAttachment(sneakyPath, MediaKind.File, "acc-ana")
        s.store.settle()
        assertTrue(sneaky.exists())
        sneaky.delete()
    }

    @Test
    fun twoSendsPreparedAtTheSameMomentEachKeepTheirOwnCopyAndLeaveNothingBehind() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        val both = java.util.concurrent.CyclicBarrier(2)
        f.afterCopy = { both.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        s.store.sendAttachment(source(tmp("one")).path, MediaKind.File, "acc-ana")
        s.store.sendAttachment(source(tmp("two")).path, MediaKind.File, "acc-ana")
        s.store.settle()
        assertEquals(2, f.prepares.get())
        assertEquals(emptyList(), f.d.repo.messages("acc-ana", null, 50).filter { it.status != "sent" && it.status != "sending" })
        assertEquals(emptyList(), f.preparedDir.listFiles()!!.toList())
        assertEquals(emptyList(), f.pendingRoot.list().orEmpty().toList())
    }

    @Test
    fun aRowLeftSendingByARestartIsFailedAndTryAgainReallySendsIt() = runBlocking<Unit> {
        val f = fixture()
        val first = session(f)
        first.api.gate = CompletableDeferred() // never completes: the app is killed mid-upload
        first.store.sendAttachment(staged(f).path, MediaKind.File, "acc-ana")
        waitUntil("the upload to start") { first.api.attempts.get() == 1 }
        val id = sentId(f)
        first.scope.cancel()
        assertEquals("sending", statusOf(f, id))

        // The next start: the sweep empties the temp folder, yet what the retry needs is still there.
        val second = session(f)
        second.store.sweepStartup()
        assertEquals(emptyList(), f.dirs.tempDir.list().orEmpty().toList())
        assertEquals("failed", statusOf(f, id))
        second.store.retrySend(id)
        second.store.awaitWrites()
        second.store.settle()
        assertEquals(1, second.api.uploads.get())
        assertEquals(AttachmentState.LOCAL, f.d.repo.attachmentForMessage(id)!!.state)
        val sent = assertIs<ContentEnvelope.File>(peerGot(f, id))
        assertEquals("up1", sent.a.id)
        assertEquals("notes.txt", sent.name)
        val opened = assertNotNull(second.store.openAttachment(id))
        assertEquals("hello there", opened.file.readText())
        opened.close()
        assertFalse(pendingDir(f, id).exists())
    }

    @Test
    fun aFailedAttachmentThatIsDeletedLeavesNothingBehind() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        s.api.failUploads = true
        s.store.sendAttachment(staged(f).path, MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        assertTrue(pendingDir(f, id).exists())
        s.store.delete(id, "acc-ana")
        s.store.awaitWrites()
        assertFalse(pendingDir(f, id).exists())
        assertFalse(pendingKey(f, id))
        s.store.retrySend(id)
        s.store.awaitWrites()
        s.store.settle()
        assertEquals(1, s.api.attempts.get(), "a deleted message is not uploaded again")
    }

    @Test
    fun aRowWithThePlaceholderEnvelopeIsNeverQueued() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        val placeholder = ContentEnvelope.File("m-ph", NOW, Att("", ByteArray(0), ByteArray(0), 5, 5), "a.txt", "application/octet-stream")
        f.d.repo.insertMessage(
            MessageRecord(
                id = "m-ph", chatId = "acc-ana", conversationId = null, senderAccount = f.d.me.accountId, senderDevice = f.d.me.deviceId,
                sentAt = NOW, receivedAt = NOW, envelope = ContentCodec.encode(placeholder), status = "failed", replyTo = null, folded = "",
            ),
        )
        s.store.retrySend("m-ph")
        s.store.awaitWrites()
        assertEquals("failed", statusOf(f, "m-ph"))
        assertEquals(emptyList(), f.d.repo.dueOutbox(Long.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { f.d.outbox.enqueue(placeholder, "acc-ana") }
        assertEquals(emptyList(), f.d.repo.dueOutbox(Long.MAX_VALUE))
    }

    @Test
    fun anAttachmentIdThatCouldBecomeAPathFailsTheUploadAndWritesNothing() = runBlocking<Unit> {
        for (bad in listOf("../x", "a/b", "x".repeat(200), "")) {
            val f = fixture()
            val s = session(f)
            s.api.answerId = bad
            s.store.sendAttachment(source(tmp("src")).path, MediaKind.File, "acc-ana")
            s.store.settle()
            val id = sentId(f)
            assertEquals("failed", statusOf(f, id), "id '$bad'")
            assertNull(f.d.repo.attachmentForMessage(id))
            assertEquals(emptyList(), f.dirs.blobDir.list().orEmpty().toList())
            assertEquals(emptyList(), f.dirs.blobDir.parentFile.list()!!.filter { it != "blobs" && it != "pending" })
            assertEquals(emptyList(), f.dirs.tempDir.list().orEmpty().filter { it.endsWith(".enc") }, "no ciphertext left in the temp folder")
        }
    }

    @Test
    fun aMessageDeletedWhileItUploadsIsNeverSent() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        val gate = CompletableDeferred<Unit>()
        s.api.gate = gate
        val file = staged(f)
        s.store.sendAttachment(file.path, MediaKind.File, "acc-ana")
        waitUntil("the upload to start") { s.api.attempts.get() == 1 }
        val id = sentId(f)
        s.store.delete(id, "acc-ana")
        s.store.awaitWrites()
        gate.complete(Unit)
        s.store.settle()
        f.rig.ana1.processor.sync()
        assertNull(f.rig.ana1.repo.message(id), "the peer never gets the attachment")
        assertEquals(emptyList(), f.d.repo.dueOutbox(Long.MAX_VALUE), "no Delete control is queued for a message that never left")
        assertEquals(0, s.api.posts.get(), "nothing was sent")
        assertNull(f.d.repo.attachmentForMessage(id))
        assertEquals(emptyList(), f.dirs.blobDir.list().orEmpty().toList())
        assertFalse(file.exists(), "the staged copy is removed")
        assertEquals(emptyList(), f.preparedDir.listFiles()!!.toList())
        assertFalse(pendingDir(f, id).exists())
        assertFalse(pendingKey(f, id))
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

    // ---- Follow-ups ----

    private fun writePending(f: Fixture, id: String, staged: File, prepared: File) {
        val json = """{"kind":"File","chatId":"acc-ana","path":"${staged.path}","prepared":"${prepared.path}"}"""
        f.d.repo.set("pending.upload.$id", json.encodeToByteArray())
        File(f.pendingRoot, id).also { it.mkdirs() }.resolve("kept.bin").writeText("kept")
    }

    private fun insertRow(f: Fixture, id: String, status: String, deleted: Boolean) {
        val placeholder = ContentEnvelope.File(id, NOW, Att("", ByteArray(0), ByteArray(0), 5, 5), "a.txt", "application/octet-stream")
        f.d.repo.insertMessage(
            MessageRecord(
                id = id, chatId = "acc-ana", conversationId = null, senderAccount = f.d.me.accountId, senderDevice = f.d.me.deviceId,
                sentAt = NOW, receivedAt = NOW, envelope = ContentCodec.encode(placeholder), status = status, replyTo = null, folded = "",
            ),
        )
        if (deleted) f.d.repo.markDeleted(id, f.d.me.accountId)
    }

    @Test
    fun atStartAPendingFolderWithNoRowOrADeletedRowIsDiscardedAndALiveOneIsKeptAndFailed() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        val files = File(f.dirs.tempDir, "files").also { it.mkdirs() }
        f.preparedDir.mkdirs()
        val ids = listOf("ghost", "gone", "live")
        val stagedFiles = ids.associateWith { File(files, "$it.txt").also { file -> file.writeText("plain") } }
        val preparedFiles = ids.associateWith { File(f.preparedDir, "$it.bin").also { file -> file.writeText("prep") } }
        for (id in ids) writePending(f, id, stagedFiles.getValue(id), preparedFiles.getValue(id))
        insertRow(f, "gone", "failed", deleted = true)
        insertRow(f, "live", "sending", deleted = false)

        s.store.sweepStartup()

        for (id in listOf("ghost", "gone")) {
            assertFalse(pendingDir(f, id).exists(), "$id: the kept folder is removed")
            assertFalse(pendingKey(f, id), "$id: the key is removed")
            assertFalse(stagedFiles.getValue(id).exists(), "$id: the staged plaintext is removed")
            assertFalse(preparedFiles.getValue(id).exists(), "$id: the prepared copy is removed")
        }
        assertTrue(pendingDir(f, "live").exists())
        assertTrue(pendingKey(f, "live"))
        assertEquals("failed", statusOf(f, "live"))
    }

    @Test
    fun aMessageDeletedBeforeItWasQueuedSendsNoDeleteControl() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        s.api.failUploads = true
        val file = staged(f)
        s.store.sendAttachment(file.path, MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        assertEquals("failed", statusOf(f, id))
        s.store.delete(id, "acc-ana")
        s.store.awaitWrites()
        assertTrue(f.d.repo.message(id)!!.deleted)
        assertEquals(emptyList(), f.d.repo.dueOutbox(Long.MAX_VALUE), "nothing about the message left the device, so nothing is queued")
        s.store.settle()
        assertEquals(0, s.api.posts.get(), "nothing about the message left the device, so no Delete control is sent")
        assertFalse(pendingDir(f, id).exists())
        assertFalse(file.exists())
        f.rig.ana1.processor.sync()
        assertNull(f.rig.ana1.repo.message(id))
    }

    @Test
    fun aMessageDeletedAfterItWasQueuedSendsTheMessageThenTheDeleteControl() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        s.store.sendAttachment(staged(f).path, MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        s.store.delete(id, "acc-ana")
        s.store.awaitWrites()
        waitUntil("the outbox to empty") { f.d.repo.dueOutbox(Long.MAX_VALUE).isEmpty() }
        waitUntil("both to be sent") { s.api.posts.get() >= 2 }
        assertEquals(2, s.api.posts.get(), "the message, then the Delete control")
        f.rig.ana1.processor.sync()
        val got = assertNotNull(f.rig.ana1.repo.message(id), "the peer got the message, never only the Delete")
        assertTrue(got.deleted, "and then the Delete control")
    }

    @Test
    fun aPrepareThatFailsWhileTheSourceStillExistsKeepsTheMessageFailedAndTryAgainSendsIt() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        f.prepareThrows = { throw IOException("briefly unreadable") }
        s.store.sendAttachment(staged(f).path, MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        assertEquals("failed", statusOf(f, id))
        assertTrue(pendingDir(f, id).listFiles()!!.isNotEmpty() || f.d.repo.get("pending.upload.$id")!!.isNotEmpty(), "the staged file is kept")
        assertTrue(pendingKey(f, id))
        assertEquals(emptyList(), s.problems, "a kept message shows as failed, not as a notice")
        f.prepareThrows = null
        s.store.retrySend(id)
        s.store.awaitWrites()
        s.store.settle()
        assertEquals(AttachmentState.LOCAL, f.d.repo.attachmentForMessage(id)!!.state)
        assertEquals("up1", assertIs<ContentEnvelope.File>(peerGot(f, id)).a.id)
    }

    @Test
    fun aPrepareThatFailsBecauseTheSourceIsGoneRemovesTheMessageAndTellsThePerson() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        val file = staged(f)
        f.prepareThrows = {
            f.pendingRoot.listFiles()!!.forEach { it.deleteRecursively() } // the kept source disappears
            file.delete()
            throw IOException("gone")
        }
        s.store.sendAttachment(file.path, MediaKind.File, "acc-ana")
        s.store.settle()
        assertEquals(emptyList(), f.d.repo.messages("acc-ana", null, 50).filter { it.senderAccount == f.d.me.accountId })
        assertEquals(listOf(SendProblem("acc-ana", SendProblem.Reason.Unreadable)), s.problems)
        assertEquals(emptyList(), f.pendingRoot.list().orEmpty().toList())
    }

    @Test
    fun aFileThatCannotBeDecodedRemovesTheMessageAndTellsThePerson() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        f.prepareFails = true
        s.store.sendAttachment(staged(f).path, MediaKind.File, "acc-ana")
        s.store.settle()
        assertEquals(emptyList(), f.d.repo.messages("acc-ana", null, 50).filter { it.senderAccount == f.d.me.accountId })
        assertEquals(listOf(SendProblem("acc-ana", SendProblem.Reason.Unreadable)), s.problems)
    }

    @Test
    fun aContentSourceIsCopiedIntoThePendingFolderAndSent() = runBlocking<Unit> {
        val f = fixture()
        var asked: String? = null
        val s = session(f) { source, into ->
            asked = source
            StageResult.Copied(File(into, "photo").also { it.writeText("from a provider") })
        }
        s.store.sendAttachment("content://provider/42", MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        assertEquals("content://provider/42", asked)
        assertEquals(AttachmentState.LOCAL, f.d.repo.attachmentForMessage(id)!!.state)
        assertEquals("up1", assertIs<ContentEnvelope.File>(peerGot(f, id)).a.id)
        assertEquals(emptyList(), f.pendingRoot.list().orEmpty().toList())
        assertEquals(emptyList(), s.problems)
    }

    @Test
    fun aContentSourceThatIsTooBigOrUnreadableCreatesNoRowAndTellsThePerson() = runBlocking<Unit> {
        val f = fixture()
        var answer: StageResult = StageResult.TooBig
        val s = session(f) { _, into -> into.resolve("half").writeText("partial"); answer }
        s.store.sendAttachment("content://provider/1", MediaKind.Photo, "acc-ana")
        s.store.settle()
        answer = StageResult.Unreadable
        s.store.sendAttachment("content://provider/2", MediaKind.Photo, "acc-ana")
        s.store.settle()
        assertEquals(emptyList(), f.d.repo.messages("acc-ana", null, 50).filter { it.senderAccount == f.d.me.accountId })
        assertEquals(
            listOf(SendProblem("acc-ana", SendProblem.Reason.TooBig), SendProblem("acc-ana", SendProblem.Reason.Unreadable)),
            s.problems,
        )
        assertEquals(emptyList(), f.pendingRoot.list().orEmpty().toList())
    }

    @Test
    fun ifTheCiphertextCannotBeMovedIntoTheBlobFolderTheSendFailsAndKeepsItsFiles() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        f.dirs.blobDir.parentFile.mkdirs()
        f.dirs.blobDir.writeText("not a folder") // moving the ciphertext in cannot work
        s.store.sendAttachment(staged(f).path, MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        assertEquals("failed", statusOf(f, id))
        assertTrue(pendingDir(f, id).listFiles()!!.isNotEmpty(), "the files are kept for Try again")
        assertTrue(pendingKey(f, id))
        assertEquals(emptyList(), f.d.repo.dueOutbox(Long.MAX_VALUE), "nothing was queued")
        assertNull(f.d.repo.attachmentForMessage(id), "no record of a blob that is not there")
        f.rig.ana1.processor.sync()
        assertNull(f.rig.ana1.repo.message(id), "the peer never got a message whose file the sender lacks")
        assertEquals(emptyList(), f.dirs.tempDir.list().orEmpty().filter { it.endsWith(".enc") })

        f.dirs.blobDir.delete()
        s.store.retrySend(id)
        s.store.awaitWrites()
        s.store.settle()
        assertTrue(File(f.d.repo.attachmentForMessage(id)!!.localPath!!).isFile)
        assertIs<ContentEnvelope.File>(peerGot(f, id))
    }

    @Test
    fun aDroppedAttachmentIsKeptUntilTheChatShowsItThenForgottenSeenByNoOtherChatAndClearedOnSignOut() = runBlocking<Unit> {
        val f = fixture()
        // Nothing has collected anything yet: the chat is not open.
        val s = session(f) { _, _ -> StageResult.TooBig }
        s.store.sendAttachment("content://provider/1", MediaKind.Photo, "acc-ana")
        s.store.settle()
        assertEquals(SendProblem.Reason.TooBig, s.store.sendProblem("acc-ana").first(), "shown when the chat is opened later")
        assertNull(s.store.sendProblem("acc-other").first(), "another chat does not see it")
        s.store.takeSendProblem("acc-ana")
        assertNull(s.store.sendProblem("acc-ana").first(), "and only once")
        s.store.sendAttachment("content://provider/2", MediaKind.Photo, "acc-ana")
        s.store.settle()
        assertEquals(SendProblem.Reason.TooBig, s.store.sendProblem("acc-ana").first())
        s.store.shutdown()
        assertNull(s.store.sendProblem("acc-ana").first(), "sign-out clears it")
    }

    private fun aged(file: File, ageMs: Long) = file.also { it.writeText("blob"); it.setLastModified(System.currentTimeMillis() - ageMs) }

    @Test
    fun atStartOnlyOldUnreferencedBlobsAreSwept() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        val hex = "ab".repeat(32)
        val hour = 3_600_000L
        f.dirs.blobDir.mkdirs()
        val orphan = aged(File(f.dirs.blobDir, "orphan-$hex.enc"), hour)
        val referenced = aged(File(f.dirs.blobDir, "kept-$hex.enc"), hour)
        val pendingRef = aged(File(f.dirs.blobDir, "waiting-$hex.enc"), hour)
        val fresh = aged(File(f.dirs.blobDir, "fresh-$hex.enc"), 1_000)
        val partial = aged(File(f.dirs.blobDir, "part.partial"), hour)
        f.d.repo.upsertAttachment(
            org.example.app.messaging.ports.AttachmentRecord("kept", "m-x", "file", ByteArray(32), ByteArray(32), 4, 4, AttachmentState.LOCAL, 4, referenced.path),
        )
        f.d.repo.set("pending.upload.p1", """{"kind":"File","chatId":"acc-ana","path":"${pendingRef.path}"}""".encodeToByteArray())
        insertRow(f, "p1", "failed", deleted = false)

        s.store.sweepStartup()

        assertFalse(orphan.exists(), "an old blob nothing points to is removed")
        assertTrue(referenced.exists(), "a blob an attachment record points to stays")
        assertTrue(pendingRef.exists(), "a blob a pending upload refers to stays")
        assertTrue(fresh.exists(), "a recent file may belong to a send in progress")
        assertTrue(partial.exists(), "only finished blobs are judged")
    }

    @Test
    fun aRetriedUploadKeepsTheOriginalSentAt() = runBlocking<Unit> {
        val f = fixture()
        val s = session(f)
        s.api.failUploads = true
        s.store.sendAttachment(staged(f).path, MediaKind.File, "acc-ana")
        s.store.settle()
        val id = sentId(f)
        val original = f.d.repo.message(id)!!.sentAt
        s.api.failUploads = false
        f.d.nowMs += 60_000
        s.store.retrySend(id)
        s.store.awaitWrites()
        s.store.settle()
        assertEquals(original, f.d.repo.message(id)!!.sentAt, "the message keeps its place in the chat")
        val sent = assertIs<ContentEnvelope.File>(peerGot(f, id))
        assertEquals(original, sent.at)
        assertEquals(original, f.rig.ana1.repo.message(id)!!.sentAt)
    }
}
