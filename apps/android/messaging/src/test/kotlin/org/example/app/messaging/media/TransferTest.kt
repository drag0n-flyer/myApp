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
import kotlinx.coroutines.runBlocking
import org.example.app.contracts.AttachmentRef
import org.example.app.contracts.ConversationInfo
import org.example.app.contracts.DeliveryApi
import org.example.app.contracts.DeviceRecord
import org.example.app.contracts.InboxPage
import org.example.app.messaging.crypto.AttachmentException

private class FakeServer : DeliveryApi {
    val blobs = mutableMapOf<String, ByteArray>()
    /** When set, a download delivers at most this many bytes, then ends. */
    var cutAfter: Int? = null
    val fromBytes = mutableListOf<Long>()
    override suspend fun uploadAttachment(bytes: InputStream, length: Long): AttachmentRef {
        val data = bytes.readBytes()
        assertEquals(length, data.size.toLong())
        blobs["up1"] = data
        return AttachmentRef("up1", Instant.EPOCH)
    }
    override suspend fun downloadAttachment(id: String, fromByte: Long): InputStream {
        fromBytes += fromByte
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
    private fun tmp() = File.createTempFile("xfer", "").also { it.delete(); it.mkdirs() }

    @Test fun uploadThenDownloadRoundTripLeavesNoTempFiles() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val src = File(dir, "photo.jpg").also { it.writeBytes(ByteArray(150_000) { i -> (i * 7).toByte() }) }
            val att = Uploader(server, File(dir, "tmp")).upload(src)
            assertEquals(150_000L, att.sz)
            assertEquals(server.blobs["up1"]!!.size.toLong(), att.csz)
            assertEquals(emptyList(), File(dir, "tmp").list()!!.toList(), "upload temp removed")
            val to = File(dir, "out.jpg")
            Downloader(server, File(dir, "dl")).download(att, to)
            assertContentEquals(src.readBytes(), to.readBytes())
            assertEquals(emptyList(), File(dir, "dl").list()!!.toList(), "no ciphertext or plaintext temp left")
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptedDownloadResumesFromWhatIsThere() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val src = File(dir, "f.bin").also { it.writeBytes(ByteArray(200_000) { i -> i.toByte() }) }
            val att = Uploader(server, File(dir, "tmp")).upload(src)
            val dl = Downloader(server, File(dir, "dl"))
            val to = File(dir, "out.bin")
            server.cutAfter = 70_000
            assertFailsWith<IncompleteDownloadException> { dl.download(att, to) }
            assertFalse(to.exists())
            server.cutAfter = null
            dl.download(att, to)
            assertEquals(listOf(0L, 70_000L), server.fromBytes)
            assertContentEquals(src.readBytes(), to.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptDownloadIsRejectedAndDiscarded() = runBlocking {
        val dir = tmp()
        try {
            val server = FakeServer()
            val src = File(dir, "f.bin").also { it.writeBytes(ByteArray(1000) { 1 }) }
            val att = Uploader(server, File(dir, "tmp")).upload(src)
            server.blobs["up1"]!![5] = (server.blobs["up1"]!![5].toInt() xor 1).toByte()
            val to = File(dir, "out.bin")
            assertFailsWith<AttachmentException.Digest> { Downloader(server, File(dir, "dl")).download(att, to) }
            assertFalse(to.exists())
            assertEquals(emptyList(), File(dir, "dl").list()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun hostileIdCannotEscapeTheDirectory() = runBlocking {
        val dir = tmp()
        try {
            val att = org.example.app.messaging.content.Att("../evil", ByteArray(32), ByteArray(32), 0, 16)
            assertFailsWith<IllegalArgumentException> { Downloader(FakeServer(), dir).download(att, File(dir, "x")) }
            assertTrue(dir.list()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
