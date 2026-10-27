package org.example.app.net.api

import java.io.ByteArrayInputStream
import java.io.InputStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
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
            override fun writeTo(sink: BufferedSink) {
                bytes.source().use { sink.writeAll(it) }
            }
        }
        val res = client.requestRaw("POST", "/v1/attachments", body, emptyMap(), Auth.Session)
        val u = decodeBody(res.body, UploadedAttachment.serializer())
        return AttachmentRef(u.id, u.expiresAt)
    }

    /** Reads from [fromByte] to the end (`Range: bytes=N-`); the whole file when it is 0. */
    suspend fun download(id: String, fromByte: Long): InputStream {
        val headers = if (fromByte > 0) mapOf("Range" to "bytes=$fromByte-") else emptyMap()
        val res = client.requestRaw("GET", "/v1/attachments/${seg(id)}", null, headers, Auth.Session)
        return ByteArrayInputStream(res.body)
    }
}
