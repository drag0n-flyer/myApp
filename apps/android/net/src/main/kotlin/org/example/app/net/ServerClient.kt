package org.example.app.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.example.app.contracts.ServerException

class ServerClient internal constructor(
    internal val baseUrl: HttpUrl,
    private val session: SessionStore,
    http: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(host: String, session: SessionStore, http: OkHttpClient = OkHttpClient()) :
        this(HttpUrl.Builder().scheme("https").host(host).build(), session, http)

    // The API never redirects; following one could carry the Bearer token to another scheme or host.
    private val base: OkHttpClient = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** JSON calls are capped as a whole. */
    internal val jsonHttp: OkHttpClient = base.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    /** Attachment transfers have no whole-call cap; connect, read and write timeouts still apply. */
    internal val rawHttp: OkHttpClient = base.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()

    private var onUnauthorized: (suspend () -> Unit)? = null
    private val unauthorizedLock = Mutex()

    /** Generation at which a 401 was already handled; stays until the next `SessionStore.save`. */
    private var signedOutAtGeneration: Int? = null

    fun setOnUnauthorized(f: suspend () -> Unit) {
        onUnauthorized = f
    }

    suspend fun <T> request(
        method: String,
        path: String,
        body: JsonElement?,
        auth: Auth,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val res = perform(jsonHttp, method, path, jsonBody(body), auth, emptyMap())
        return withContext(io) {
            try {
                ServerJson.json.decodeFromString(deserializer, res.body.decodeToString())
            } catch (e: SerializationException) {
                throw ServerException(0, "decoding", null, null)
            } catch (e: IllegalArgumentException) {
                throw ServerException(0, "decoding", null, null)
            }
        }
    }

    /** For endpoints that answer 204 or whose body is ignored. */
    suspend fun requestNoContent(method: String, path: String, body: JsonElement?, auth: Auth) {
        perform(jsonHttp, method, path, jsonBody(body), auth, emptyMap())
    }

    /** Raw bytes in and out (attachments). No whole-call timeout. */
    suspend fun requestRaw(method: String, path: String, body: RequestBody?, headers: Map<String, String>, auth: Auth): RawResponse =
        perform(rawHttp, method, path, body, auth, headers)

    /**
     * Attachment download: the open response body, not buffered. The caller must close [StreamResponse.body].
     * Same host, no redirects and 401 handling as every other call; no whole-call timeout.
     */
    suspend fun openStream(path: String, headers: Map<String, String>, auth: Auth): StreamResponse {
        val (request, sentAt) = buildRequest("GET", path, null, auth, headers)
        val response = try {
            rawHttp.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ServerException(0, "network", null, null)
        }
        val outcome = withContext(io) {
            if (response.code in 200..299) {
                StreamResponse(response.code, response.headers.toMultimap().mapKeys { it.key.lowercase() }.mapValues { it.value.first() }, response.body.byteStream())
            } else {
                response.use { r ->
                    val data = try {
                        r.body.bytes()
                    } catch (e: IOException) {
                        throw ServerException(0, "network", null, null)
                    }
                    if (r.code in 300..399) ServerException(r.code, "redirect", null, null)
                    else mapError(r.code, r.header("Retry-After"), data)
                }
            }
        }
        if (outcome is StreamResponse) return outcome
        outcome as ServerException
        if (outcome.status == 401 && auth == Auth.Session) handleUnauthorized(sentAt)
        throw outcome
    }

    /** Header names are lower case. */
    class StreamResponse(val status: Int, val headers: Map<String, String>, val body: java.io.InputStream)

    class RawResponse(val status: Int, val body: ByteArray, val headers: Map<String, String>)

    private fun jsonBody(body: JsonElement?): RequestBody? =
        body?.let { ServerJson.json.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) }

    /** Only paths on the base URL's own host and scheme: a single leading `/`, never `//host` or a full URL. */
    private fun resolveSameHost(path: String): HttpUrl {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains('\\')) throw ServerException(0, "invalid_path", null, null)
        val url = baseUrl.resolve(path) ?: throw ServerException(0, "invalid_path", null, null)
        if (url.host != baseUrl.host || url.scheme != baseUrl.scheme || url.port != baseUrl.port) {
            throw ServerException(0, "invalid_path", null, null)
        }
        return url
    }

    private fun buildRequest(method: String, path: String, body: RequestBody?, auth: Auth, headers: Map<String, String>): Pair<Request, Int> {
        val url = resolveSameHost(path)
        val needsBody = method == "POST" || method == "PUT" || method == "PATCH"
        val rb = Request.Builder().url(url).method(method, body ?: if (needsBody) ByteArray(0).toRequestBody() else null)
        rb.header("Accept", "application/json")
        headers.forEach { (k, v) -> rb.header(k, v) }
        // Generation first: a save between the two reads then only makes this request look older, never newer.
        val sentAt = session.generation
        if (auth == Auth.Session) {
            val s = session.current ?: throw ServerException(401, "unauthorized", null, null)
            rb.header("Authorization", "Bearer ${s.token}")
        }
        return rb.build() to sentAt
    }

    private suspend fun perform(
        client: OkHttpClient, method: String, path: String, body: RequestBody?, auth: Auth, headers: Map<String, String>,
    ): RawResponse {
        val (request, sentAt) = buildRequest(method, path, body, auth, headers)
        val response = try {
            client.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ServerException(0, "network", null, null)
        }
        val outcome = withContext(io) {
            response.use { r ->
                val data = try {
                    r.body.bytes()
                } catch (e: IOException) {
                    throw ServerException(0, "network", null, null)
                }
                when {
                    r.code in 200..299 -> RawResponse(r.code, data, r.headers.toMultimap().mapValues { it.value.first() })
                    r.code in 300..399 -> ServerException(r.code, "redirect", null, null)
                    else -> mapError(r.code, r.header("Retry-After"), data)
                }
            }
        }
        if (outcome is RawResponse) return outcome
        outcome as ServerException
        if (outcome.status == 401 && auth == Auth.Session) handleUnauthorized(sentAt)
        throw outcome
    }

    private suspend fun handleUnauthorized(sentAt: Int) {
        val first = unauthorizedLock.withLock {
            // A login (save) since the request was sent means the 401 is about an old token.
            if (session.generation != sentAt || signedOutAtGeneration == sentAt) return@withLock false
            val cleared = withContext(io) {
                try {
                    session.clear()
                    true
                } catch (e: Exception) {
                    false
                }
            }
            // If the storage delete failed, the next 401 tries again; the app is still told to sign out.
            if (cleared) signedOutAtGeneration = sentAt
            true
        }
        if (first) {
            try {
                onUnauthorized?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The caller still gets the 401.
            }
        }
    }

    private fun mapError(status: Int, retryAfter: String?, data: ByteArray): ServerException {
        val retry = retryAfter?.trim()?.toIntOrNull()
        try {
            val root = ServerJson.json.parseToJsonElement(data.decodeToString()) as? JsonObject
            val error = root?.get("error") as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (error != null && code != null) {
                val details = (error["details"] as? JsonObject)
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }
                    ?.toMap()
                return ServerException(status, code, retry, details)
            }
        } catch (_: Exception) {
        }
        return ServerException(status, "unknown", retry, null)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
}
