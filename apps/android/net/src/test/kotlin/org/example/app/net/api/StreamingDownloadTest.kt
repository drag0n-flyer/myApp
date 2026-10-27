package org.example.app.net.api

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.example.app.contracts.MemorySecretStore
import org.example.app.net.ServerClient
import org.example.app.net.Session
import org.example.app.net.SessionStore
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A fake network that serves a 100 MB body lazily and counts the bytes pulled from it. */
class StreamingDownloadTest {
    private val total = 100L * 1024 * 1024
    private val pulled = AtomicLong(0)

    private val fakeNetwork = Interceptor { chain ->
        val src = object : Source {
            private var left = total
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (left == 0L) return -1
                val n = minOf(byteCount, left, 8192L)
                sink.write(ByteArray(n.toInt()))
                left -= n
                pulled.addAndGet(n)
                return n
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create("application/octet-stream".toMediaType(), total, src.buffer()))
            .build()
    }

    @Test
    fun downloadDoesNotBufferTheFile() = runBlocking {
        val store = SessionStore(MemorySecretStore())
        store.save(Session("example.org", "a1", "d1", "tok"))
        val client = ServerClient(okhttp3.HttpUrl.Builder().scheme("https").host("example.org").build(), store, OkHttpClient.Builder().addInterceptor(fakeNetwork).build())
        val stream = AttachmentsApi(client).download("att1", 0)
        assertTrue(pulled.get() < 1024 * 1024, "pulled ${pulled.get()} before any read")
        val buf = ByteArray(10)
        assertEquals(10, stream.read(buf))
        assertTrue(pulled.get() < 1024 * 1024, "pulled ${pulled.get()} after 10 bytes")
        stream.close()
        assertTrue(pulled.get() < total)
    }
}
