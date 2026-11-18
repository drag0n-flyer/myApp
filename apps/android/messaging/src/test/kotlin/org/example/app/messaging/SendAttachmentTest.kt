package org.example.app.messaging

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.example.app.contracts.AttachmentRef
import org.example.app.contracts.DeliveryApi
import org.example.app.core.settings.MediaKind
import org.example.app.messaging.ports.AttachmentState
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.sync.NOW
import org.example.app.messaging.sync.Rig
import org.example.app.messaging.sync.TestDevice

/** A server that takes uploads and counts every call a sender should not need to make for its own files. */
private class UploadApi(private val inner: DeliveryApi) : DeliveryApi by inner {
    val uploads = AtomicInteger()
    val downloads = AtomicInteger()
    override suspend fun uploadAttachment(bytes: InputStream, length: Long): AttachmentRef {
        bytes.readBytes()
        return AttachmentRef("up${uploads.incrementAndGet()}", Instant.EPOCH)
    }
    override suspend fun downloadAttachment(id: String, fromByte: Long): InputStream {
        downloads.incrementAndGet()
        throw IllegalStateException("no download expected")
    }
}

class SendAttachmentTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private fun tmp(n: String): File = Files.createTempDirectory(n).toFile().also { it.deleteOnExit() }

    private class Fixture(val d: TestDevice, val api: UploadApi, val dirs: MediaDirs, val store: LiveChatStore)

    private suspend fun fixture(): Fixture {
        val rig = Rig().start()
        rig.bob1.repo.upsertContact(ContactRecord("acc-ana", "ana", "Ana", true, NOW))
        rig.ana1.manager.create("acc-bob")
        rig.bob1.processor.sync()
        val d = rig.bob1
        val api = UploadApi(d.api)
        val dirs = MediaDirs(tmp("blob"), tmp("open"), tmp("temp"))
        val store = LiveChatStore(
            device = d.me, mls = d.mls, server = api, repo = d.repo, lock = d.lock, clock = d.clock, conversations = d.manager,
            inbox = d.processor, membership = d.membership, sealer = d.sealer(), escrow = d.escrow,
            readReceiptsEnabled = { true }, maxAttachmentBytes = 10_000_000L, dirs = dirs,
            social = object : SocialPort {
                override suspend fun lookUpUser(username: String) = null
                override suspend fun block(accountId: String) = Unit
                override suspend fun unblock(accountId: String) = Unit
                override suspend fun report(accountId: String, conversationId: String?, messages: List<ReportedLine>) = Unit
            },
            scope = scope, mediaSweep = {},
        )
        return Fixture(d, api, dirs, store)
    }

    private fun sentId(f: Fixture) = f.d.repo.messages("acc-ana", null, 50).last { it.senderAccount == f.d.me.accountId }.id

    @Test
    fun aFileISentIsLocalOnMyDeviceAndOpensWithoutANetworkCall() = runTest {
        val f = fixture()
        val src = File(tmp("src"), "notes.txt").also { it.writeBytes("hello there".encodeToByteArray()) }
        f.store.sendAttachment(src.path, MediaKind.File, "acc-ana")
        f.store.settle()
        val id = sentId(f)
        val record = assertNotNull(f.d.repo.attachmentForMessage(id))
        assertEquals(AttachmentState.LOCAL, record.state)
        val blob = File(assertNotNull(record.localPath))
        assertTrue(blob.isFile, "the ciphertext stays")
        assertEquals(f.dirs.blobDir, blob.parentFile)
        val opened = assertNotNull(f.store.openAttachment(id))
        assertEquals("hello there", opened.file.readText())
        opened.close()
        assertEquals(0, f.api.downloads.get())
    }
}
