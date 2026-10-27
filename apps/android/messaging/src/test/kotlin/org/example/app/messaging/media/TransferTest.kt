package org.example.app.messaging.media

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.example.app.contracts.AttachmentRef
import org.example.app.contracts.ConversationInfo
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.DeviceRecord
import org.example.app.contracts.InboxPage
import org.example.app.messaging.crypto.AttachmentCipher
import org.example.app.messaging.crypto.AttachmentException

private class FakeServer : DeliveryApi {
    val blobs = mutableMapOf<String, ByteArray>()
    /** When set, a download delivers at most this many bytes, then ends. */
    var cutAfter: Int? = null
    val fromBytes = mutableListOf<Long>()
    /** When true, a ranged request fails like a server that ignores Range (the API layer's `bad_range`). */
    var ignoresRange = false
    /** Delay in ms before a download returns, to overlap concurrent callers. */
    var delayMs = 0L
    val downloads = java.util.concurrent.atomic.AtomicInteger()
    override suspend fun uploadAttachment(bytes: InputStream, length: Long): AttachmentRef {
        val data = bytes.readBytes()
        assertEquals(length, data.size.toLong())
        blobs["up1"] = data
        return AttachmentRef("up1", Instant.EPOCH)
    }
    override suspend fun downloadAttachment(id: String, fromByte: Long): InputStream {
        downloads.incrementAndGet()
        synchronized(fromBytes) { fromBytes += fromByte }
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        if (ignoresRange && fromByte > 0) throw org.example.app.contracts.ServerException(200, "bad_range", null, null)
        var tail = blobs.getValue(id).copyOfRange(fromByte.toInt(), blobs.getValue(id).size)
        cutAfter?.let { tail = tail.copyOf(minOf(it, tail.size)) }
        return ByteArrayInputStream(tail)
    }
    override suspend fun uploadKeyPackages(keyPackages: List<ByteArray>): Int = TODO()
    override suspend fun keyPackageCount(): Int = TODO()
    override suspend fun claimKeyPackages(accountId: String, deviceIds: List<String>?): Map<String, ByteArray> = TODO()
    override suspend fun createConversation(peerAccountId: String): ConversationInfo = TODO()
    override suspend fun conversation(id: String): ConversationInfo = TODO()
    override suspend fun postCommit(id: String, epoch: Long, commit: ByteArray, welcome: ByteArray?, addDevices: List<String>, removeDevices: List<String>): Long = TODO()
    override suspend fun postMessage(id: String, epoch: Long, ciphertext: ByteArray, escrow: ByteArray?, clientMsgId: String): Long = TODO()
    override suspend fun inbox(after: Long?, limit: Int): InboxPage = TODO()
    override suspend fun ack(upTo: Long) = TODO()
    override suspend fun devices(): List<DeviceRecord> = TODO()
}

class TransferTest {
    private fun blobName(att: org.example.app.messaging.content.Att) =
        "${att.id}-${att.dg.joinToString("") { "%02x".format(it) }}.enc"
    private fun tmp() = File.createTempFile("xfer", "").also { it.delete(); it.mkdirs() }
    private fun downloader(server: DeliveryApi, dir: File, max: Long = Downloader.DEFAULT_MAX_BYTES) =
        Downloader(server, File(dir, "dl"), File(dir, "open"), max)
    private suspend fun upload(server: FakeServer, dir: File, data: ByteArray): Pair<File, org.example.app.messaging.content.Att> {
        val src = File(dir, "src.bin").also { it.writeBytes(data) }
        return src to Uploader(server, File(dir, "tmp")).upload(src)
    }

    @Test fun uploadThenOpenRoundTripKeepsOnlyEncryptedBlobOnDisk() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (src, att) = upload(server, dir, ByteArray(150_000) { i -> (i * 7).toByte() })
            assertEquals(150_000L, att.sz)
            assertEquals(server.blobs["up1"]!!.size.toLong(), att.csz)
            assertEquals(emptyList(), File(dir, "tmp").list()!!.toList(), "upload temp removed")
            val dl = downloader(server, dir)
            val blob = dl.download(att)
            assertEquals(blobName(att), blob.name)
            assertContentEquals(server.blobs["up1"]!!, blob.readBytes(), "the stored file is the ciphertext")
            assertEquals(listOf(blobName(att)), File(dir, "dl").list()!!.toList(), "no part file, no plaintext")
            assertFalse(File(dir, "open").exists() && File(dir, "open").list()!!.isNotEmpty())
            val opened = dl.open(att)
            assertTrue(opened.file.absolutePath.startsWith(File(dir, "open").absolutePath))
            assertContentEquals(src.readBytes(), opened.file.readBytes())
            opened.close()
            assertFalse(opened.file.exists(), "plaintext deleted on close")
            assertEquals(emptyList(), File(dir, "open").list()!!.toList())
            assertEquals(listOf(blobName(att)), File(dir, "dl").list()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun openedPlaintextSurvivesUntilLastCloseAndSweepRemovesLeftovers() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (_, att) = upload(server, dir, ByteArray(1000) { 3 })
            val dl = downloader(server, dir)
            val a = dl.open(att)
            val b = dl.open(att)
            a.close()
            assertTrue(b.file.exists(), "still shown by the second viewer")
            b.close()
            assertFalse(b.file.exists())
            val c = dl.open(att)
            File(dir, "open").mkdirs()
            File(dir, "open/orphan.part").writeBytes(byteArrayOf(1))
            // A new process starts: nothing from the old one may stay.
            downloader(server, dir).sweepOpen()
            assertEquals(emptyList(), File(dir, "open").list()!!.toList())
            assertTrue(File(dir, "dl/${blobName(att)}").exists(), "the encrypted blob is not swept")
            c.close()
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptedDownloadResumesFromWhatIsThere() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (src, att) = upload(server, dir, ByteArray(200_000) { i -> i.toByte() })
            val dl = downloader(server, dir)
            server.cutAfter = 70_000
            assertFailsWith<IncompleteDownloadException> { dl.download(att) }
            assertFalse(File(dir, "dl/${blobName(att)}").exists())
            server.cutAfter = null
            dl.download(att)
            assertEquals(listOf(0L, 70_000L), server.fromBytes)
            dl.open(att).use { assertContentEquals(src.readBytes(), it.file.readBytes()) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun serverThatIgnoresRangeRestartsFromZeroOnce() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (src, att) = upload(server, dir, ByteArray(200_000) { i -> i.toByte() })
            val dl = downloader(server, dir)
            server.cutAfter = 70_000
            assertFailsWith<IncompleteDownloadException> { dl.download(att) }
            server.cutAfter = null
            server.ignoresRange = true
            dl.download(att)
            assertEquals(listOf(0L, 70_000L, 0L), server.fromBytes)
            dl.open(att).use { assertContentEquals(src.readBytes(), it.file.readBytes()) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun badRangeOnAFreshStartIsNotRetriedForever() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (_, att) = upload(server, dir, ByteArray(200_000) { i -> i.toByte() })
            val dl = downloader(server, dir)
            server.cutAfter = 70_000
            assertFailsWith<IncompleteDownloadException> { dl.download(att) }
            server.cutAfter = 50_000
            server.ignoresRange = true
            // Restart from zero delivers 50 000 bytes only, so it is incomplete again; no further restart in this call.
            assertFailsWith<IncompleteDownloadException> { dl.download(att) }
            assertEquals(listOf(0L, 70_000L, 0L), server.fromBytes)
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptDownloadIsRejectedAndDiscarded() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (_, att) = upload(server, dir, ByteArray(1000) { 1 })
            server.blobs["up1"]!![5] = (server.blobs["up1"]!![5].toInt() xor 1).toByte()
            assertFailsWith<AttachmentException.Digest> { downloader(server, dir).download(att) }
            assertEquals(emptyList(), File(dir, "dl").list()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun wrongKeyIsRejectedAtOpenAndLeavesNoPlaintext() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (_, att) = upload(server, dir, ByteArray(1000) { 1 })
            val bad = org.example.app.messaging.content.Att(att.id, ByteArray(32) { 9 }, att.dg, att.sz, att.csz)
            val dl = downloader(server, dir)
            assertFailsWith<AttachmentException.Authentication> { dl.open(bad) }
            assertEquals(emptyList(), File(dir, "open").list()?.toList() ?: emptyList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun hostileIdCannotEscapeTheDirectory() = runBlocking {
        val dir = tmp()
        try {
            val att = org.example.app.messaging.content.Att("../evil", ByteArray(32), ByteArray(32), 0, 16)
            assertFailsWith<IllegalArgumentException> { downloader(FakeServer(), dir).download(att) }
            assertTrue(dir.list()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun cszOverThePolicyLimitIsRejectedBeforeFetching() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val sz = 200_000L
            val att = org.example.app.messaging.content.Att("big", ByteArray(32), ByteArray(32), sz, AttachmentCipher.sealedSize(sz))
            val dl = downloader(server, dir, max = att.csz - 1)
            assertFailsWith<AttachmentSizeException> { dl.download(att) }
            assertEquals(0, server.downloads.get())
            // The default limit is the server's 100 MiB.
            assertEquals(104_857_600L, Downloader.DEFAULT_MAX_BYTES)
            val huge = org.example.app.messaging.content.Att("huge", ByteArray(32), ByteArray(32), 104_857_600L, AttachmentCipher.sealedSize(104_857_600L))
            assertFailsWith<AttachmentSizeException> { downloader(server, dir).download(huge) }
            assertEquals(0, server.downloads.get())
        } finally { dir.deleteRecursively() }
    }

    @Test fun cszInconsistentWithSzIsRejectedBeforeFetching() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val good = AttachmentCipher.sealedSize(1000)
            for ((sz, csz) in listOf(1000L to good + 1, 1000L to good - 1, 1000L to 2 * good, -1L to 16L, 0L to 0L, 1000L to -5L)) {
                val att = org.example.app.messaging.content.Att("x", ByteArray(32), ByteArray(32), sz, csz)
                assertFailsWith<AttachmentSizeException>("sz=$sz csz=$csz") { downloader(server, dir).download(att) }
            }
            assertEquals(0, server.downloads.get())
        } finally { dir.deleteRecursively() }
    }

    @Test fun concurrentDownloadsOfOneAttachmentFetchOnceAndBothSucceed() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (src, att) = upload(server, dir, ByteArray(300_000) { i -> (i * 3).toByte() })
            server.delayMs = 100
            val dl = downloader(server, dir)
            val results = (1..8).map { async(Dispatchers.Default) { dl.open(att) } }.awaitAll()
            assertEquals(1, server.downloads.get(), "one transfer for eight callers")
            for (r in results) assertContentEquals(src.readBytes(), r.file.readBytes())
            assertEquals(1, results.map { it.file }.toSet().size, "one shared plaintext file")
            assertEquals(listOf(blobName(att)), File(dir, "dl").list()!!.toList())
            results.forEach { it.close() }
            assertEquals(emptyList(), File(dir, "open").list()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun forgedMessageWithSameIdAndWrongKeyCannotDeleteTheVerifiedBlob() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (src, att) = upload(server, dir, ByteArray(1000) { 1 })
            val dl = downloader(server, dir)
            dl.open(att).close()
            val forged = org.example.app.messaging.content.Att(att.id, ByteArray(32) { 9 }, att.dg, att.sz, att.csz)
            assertFailsWith<AttachmentException.Authentication> { dl.open(forged) }
            assertTrue(File(dir, "dl/${blobName(att)}").exists(), "the verified blob stays")
            dl.open(att).use { assertContentEquals(src.readBytes(), it.file.readBytes()) }
            assertEquals(1, server.downloads.get(), "no second transfer")
        } finally { dir.deleteRecursively() }
    }

    @Test fun forgedMessageWithSameIdAndOtherDigestOrSizeNeitherReusesNorDeletesTheBlob() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (src, att) = upload(server, dir, ByteArray(1000) { 1 })
            val dl = downloader(server, dir)
            dl.download(att)
            val badDigest = org.example.app.messaging.content.Att(att.id, att.key, ByteArray(32) { 5 }, att.sz, att.csz)
            assertFailsWith<AttachmentException.Digest> { dl.open(badDigest) }
            val otherSize = org.example.app.messaging.content.Att(
                att.id, att.key, att.dg, att.sz + 16, AttachmentCipher.sealedSize(att.sz + 16))
            runCatching { dl.open(otherSize) }
            assertTrue(File(dir, "dl/${blobName(att)}").exists(), "the verified blob stays")
            dl.open(att).use { assertContentEquals(src.readBytes(), it.file.readBytes()) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun clearedCacheWhileShownDoesNotLetAStaleCloseDeleteTheOtherViewersFile() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (src, att) = upload(server, dir, ByteArray(1000) { 2 })
            val dl = downloader(server, dir)
            val a = dl.open(att)
            a.file.delete()                      // the OS cleared the cache under a shown item
            val b = dl.open(att)
            assertContentEquals(src.readBytes(), b.file.readBytes())
            a.close()
            assertTrue(b.file.exists(), "the second viewer still shows its file")
            b.close()
            assertFalse(b.file.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun sweepSkipsFilesThatAreCurrentlyOpen() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val (_, att) = upload(server, dir, ByteArray(1000) { 2 })
            val dl = downloader(server, dir)
            val a = dl.open(att)
            File(dir, "open/orphan.part").writeBytes(byteArrayOf(1))
            dl.sweepOpen()
            assertTrue(a.file.exists(), "an open file is not swept")
            assertFalse(File(dir, "open/orphan.part").exists())
            val b = dl.open(att)
            a.close()
            assertTrue(b.file.exists())
            b.close()
            assertFalse(b.file.exists())
        } finally { dir.deleteRecursively() }
    }
}
