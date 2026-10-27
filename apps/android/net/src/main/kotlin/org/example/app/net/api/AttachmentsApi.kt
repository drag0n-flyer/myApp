package org.example.app.net.api

import java.io.InputStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.example.app.contracts.ServerException
import org.example.app.net.Auth
import org.example.app.net.ServerClient
import org.example.app.net.models.UploadedAttachment
import org.example.app.contracts.AttachmentRef

class AttachmentsApi(private val client: ServerClient) {
    /** Sends the bytes as they are (already encrypted) with an exact `Content-Length`. */
    suspend fun upload(bytes: InputStream, length: Long): AttachmentRef {
        val body = object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = length
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                bytes.source().use { sink.writeAll(it) }
            }
        }
        val res = client.requestRaw("POST", "/v1/attachments", body, emptyMap(), Auth.Session)
        val u = decodeBody(res.body, UploadedAttachment.serializer())
        return AttachmentRef(u.id, u.expiresAt)
    }

    /**
     * Streams the file from [fromByte] to the end (`Range: bytes=N-`); the whole file when it is 0. Nothing is
     * buffered; the caller closes the stream. A resume (`fromByte > 0`) needs a 206 whose `Content-Range` starts at
     * [fromByte]; anything else fails with `bad_range` so the caller never appends the wrong bytes.
     */
    suspend fun download(id: String, fromByte: Long): InputStream {
        val headers = if (fromByte > 0) mapOf("Range" to "bytes=$fromByte-") else emptyMap()
        val res = client.openStream("/v1/attachments/${seg(id)}", headers, Auth.Session)
        if (fromByte > 0 && !startsAt(res, fromByte)) {
            res.body.close()
            throw ServerException(res.status, "bad_range", null, null)
        }
        return res.body
    }

    private fun startsAt(res: ServerClient.StreamResponse, from: Long): Boolean {
        if (res.status != 206) return false
        val m = Regex("""^bytes (\d+)-(\d+)/(\d+|\*)$""").matchEntire(res.headers["content-range"]?.trim() ?: return false) ?: return false
        return m.groupValues[1].toLongOrNull() == from
    }
}
