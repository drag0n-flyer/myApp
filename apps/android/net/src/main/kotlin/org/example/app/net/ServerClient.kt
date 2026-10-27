package org.example.app.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
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
) {
    constructor(host: String, session: SessionStore, http: OkHttpClient = OkHttpClient()) :
        this(HttpUrl.Builder().scheme("https").host(host).build(), session, http)

    private val http: OkHttpClient = http.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

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
        val res = perform(method, path, jsonBody(body), auth, emptyMap())
        try {
            return ServerJson.json.decodeFromString(deserializer, res.body.decodeToString())
        } catch (e: SerializationException) {
            throw ServerException(0, "decoding", null, null)
        } catch (e: IllegalArgumentException) {
            throw ServerException(0, "decoding", null, null)
        }
    }

    /** For endpoints that answer 204 or whose body is ignored. */
    suspend fun requestNoContent(method: String, path: String, body: JsonElement?, auth: Auth) {
        perform(method, path, jsonBody(body), auth, emptyMap())
    }

    /** Raw bytes in and out (attachments). */
    suspend fun requestRaw(method: String, path: String, body: RequestBody?, headers: Map<String, String>, auth: Auth): RawResponse =
        perform(method, path, body, auth, headers)

    class RawResponse(val status: Int, val body: ByteArray, val headers: Map<String, String>)

    private fun jsonBody(body: JsonElement?): RequestBody? =
        body?.let { ServerJson.json.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) }

    private suspend fun perform(method: String, path: String, body: RequestBody?, auth: Auth, headers: Map<String, String>): RawResponse {
        val url = baseUrl.resolve(path) ?: throw ServerException(0, "network", null, null)
        val needsBody = method == "POST" || method == "PUT" || method == "PATCH"
        val rb = Request.Builder().url(url).method(method, body ?: if (needsBody) ByteArray(0).toRequestBody() else null)
        rb.header("Accept", "application/json")
        headers.forEach { (k, v) -> rb.header(k, v) }
        if (auth == Auth.Session) {
            val s = session.current ?: throw ServerException(401, "unauthorized", null, null)
            rb.header("Authorization", "Bearer ${s.token}")
        }
        val sentAt = session.generation
        val response = try {
            http.newCall(rb.build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ServerException(0, "network", null, null)
        }
        response.use { r ->
            val data = try {
                r.body.bytes()
            } catch (e: IOException) {
                throw ServerException(0, "network", null, null)
            }
            if (r.code in 200..299) {
                return RawResponse(r.code, data, r.headers.toMultimap().mapValues { it.value.first() })
            }
            val err = mapError(r.code, r.header("Retry-After"), data)
            if (r.code == 401 && auth == Auth.Session) handleUnauthorized(sentAt)
            throw err
        }
    }

    private suspend fun handleUnauthorized(sentAt: Int) {
        val first = unauthorizedLock.withLock {
            // A login (save) since the request was sent means the 401 is about an old token.
            if (session.generation != sentAt || signedOutAtGeneration == sentAt) return@withLock false
            signedOutAtGeneration = sentAt
            try {
                session.clear()
            } catch (_: Exception) {
                // The app is still told to sign out and may retry.
            }
            true
        }
        if (first) onUnauthorized?.invoke()
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
