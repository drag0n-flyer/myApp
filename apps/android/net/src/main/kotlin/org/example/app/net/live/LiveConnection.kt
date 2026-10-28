package org.example.app.net.live

import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.example.app.contracts.ConnectionState
import org.example.app.contracts.LiveSocket
import org.example.app.contracts.ServerEvent
import org.example.app.contracts.ServerException

/** Time source for the connection's own timers (backoff, stability, send timeout). Tests replace it. */
interface LiveClock {
    suspend fun delay(millis: Long)
}

object SystemLiveClock : LiveClock {
    override suspend fun delay(millis: Long) = kotlinx.coroutines.delay(millis)
}

/**
 * The live WebSocket (`GET /v1/ws`). It is only open between [start] and [stop]: the app calls [start] while it is
 * visible or a call is active and [stop] otherwise. Push wakes the app in the background; there is no background socket.
 * Same host as the [ServerClient], no redirects, and nothing here logs the token or any frame.
 *
 * Dead sockets are found by an app-level probe, not only by OkHttp's ping (which needs up to two ticks): every
 * [PROBE_INTERVAL_MS] while connected the client sends `{"type":"ping","id":"cN"}`. The server does not know the type
 * and answers `error bad_request` (realtime/module.go) without logging, so no server change is needed; an ack or an
 * error with that id, or any other inbound frame, counts as alive. If nothing arrives within [PROBE_TIMEOUT_MS] the
 * socket is cancelled and the normal reconnect and backoff runs. The probe timer lives only while a socket is open:
 * it stops on [stop] and on disconnect, so nothing wakes the device when there is no connection. OkHttp's ping
 * ([pingIntervalMillis]) stays as a backstop.
 */
class LiveConnection internal constructor(
    private val client: org.example.app.net.ServerClient,
    http: OkHttpClient,
    private val clock: LiveClock = SystemLiveClock,
    random: Random = Random.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    pingIntervalMillis: Long = 25_000,
) : LiveSocket {
    constructor(client: org.example.app.net.ServerClient, http: OkHttpClient, clock: LiveClock, random: Random) :
        this(client, http, clock, random, Dispatchers.IO, 25_000)

    private val socketHttp: OkHttpClient = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .pingInterval(pingIntervalMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val eventChannel = Channel<ServerEvent>(Channel.UNLIMITED)
    private val backoff = Backoff { bound -> random.nextDouble() * bound }
    private val _states = MutableStateFlow(ConnectionState.Disconnected)

    private val lock = Any()
    private var runId = 0
    private var job: Job? = null
    private var socket: WebSocket? = null
    private var nextId = 0
    private val pending = HashMap<String, CompletableDeferred<JsonObject?>>()
    private var stable = false

    private companion object {
        const val PROBE_INTERVAL_MS = 25_000L
        const val PROBE_TIMEOUT_MS = 10_000L
    }

    /** One collector at a time; events that arrive with no collector wait in the buffer. */
    override val events: Flow<ServerEvent> = eventChannel.receiveAsFlow()
    override val states: StateFlow<ConnectionState> = _states

    fun start() {
        synchronized(lock) {
            if (job != null) return
            runId += 1
            val id = runId
            job = scope.launch { run(id) }
        }
    }

    fun stop() {
        val (j, ws) = synchronized(lock) {
            runId += 1
            val j = job
            job = null
            val ws = socket
            socket = null
            while (eventChannel.tryReceive().isSuccess) Unit // old events must not reach a later start()
            _states.value = ConnectionState.Disconnected
            j to ws
        }
        j?.cancel()
        ws?.close(1000, null)
        failAllPending()
    }

    override suspend fun send(type: String, data: JsonObject): JsonObject? {
        val deferred = CompletableDeferred<JsonObject?>()
        val id: String
        val ws: WebSocket
        synchronized(lock) {
            ws = socket?.takeIf { _states.value == ConnectionState.Connected } ?: throw ServerException(0, "network", null, null)
            nextId += 1
            id = "c$nextId"
            pending[id] = deferred
        }
        if (!ws.send(encodeFrame(type, id, data))) {
            synchronized(lock) { pending.remove(id) }
            throw ServerException(0, "network", null, null)
        }
        return coroutineScope {
            val timer = launch {
                clock.delay(10_000)
                resolve(id, null, ServerException(0, "timeout", null, null))
            }
            try {
                deferred.await()
            } finally {
                timer.cancel()
                synchronized(lock) { pending.remove(id) }
            }
        }
    }

    private fun resolve(id: String, data: JsonObject?, error: ServerException?) {
        val d = synchronized(lock) { pending.remove(id) } ?: return
        if (error != null) d.completeExceptionally(error) else d.complete(data)
    }

    private fun failAllPending() {
        val all = synchronized(lock) { pending.values.toList().also { pending.clear() } }
        all.forEach { it.completeExceptionally(ServerException(0, "network", null, null)) }
    }

    private fun current(id: Int) = synchronized(lock) { id == runId }

    private fun setState(id: Int, s: ConnectionState) {
        synchronized(lock) { if (id == runId) _states.value = s }
    }

    private fun finish(id: Int, s: ConnectionState) {
        synchronized(lock) {
            if (id != runId) return
            job = null
            _states.value = s
        }
    }

    private class Outcome(val code: Int?, val httpStatus: Int?)

    private suspend fun run(id: Int) {
        while (current(id)) {
            val endpoint = client.liveEndpoint()
            if (endpoint == null) return finish(id, ConnectionState.Disconnected)
            setState(id, ConnectionState.Connecting)
            val outcome = connectOnce(id, endpoint.request)
            if (!current(id)) return
            if (outcome.code == 4003) {
                finish(id, ConnectionState.Revoked)
                client.handleRevoked(endpoint.generation)
                return
            }
            if (outcome.httpStatus == 401) {
                // Revoked only when a second call confirms it (SignOutReason); otherwise signed out, data kept.
                val reason = client.handleUpgradeUnauthorized(endpoint.generation)
                finish(id, if (reason == org.example.app.net.SignOutReason.Revoked) ConnectionState.Revoked else ConnectionState.Disconnected)
                return
            }
            if (outcome.code == 4001) return finish(id, ConnectionState.Replaced)
            setState(id, ConnectionState.Disconnected)
            val wait = synchronized(lock) {
                if (stable) {
                    backoff.reset()
                    stable = false
                }
                backoff.next()
            }
            clock.delay((wait * 1000).toLong())
        }
    }

    /** Opens one socket and suspends until it is closed or fails. */
    private suspend fun connectOnce(id: Int, request: okhttp3.Request): Outcome = coroutineScope {
        val done = CompletableDeferred<Outcome>()
        val frames = Channel<String>(Channel.UNLIMITED)
        val processor = launch {
            for (text in frames) handle(id, text)
        }
        val openJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
        val inbound = java.util.concurrent.atomic.AtomicLong()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ok = synchronized(lock) {
                    if (id != runId) false else {
                        socket = webSocket
                        stable = false
                        _states.value = ConnectionState.Connected
                        true
                    }
                }
                if (!ok) {
                    webSocket.close(1000, null)
                    return
                }
                openJobs += launch {
                    clock.delay(30_000)
                    synchronized(lock) { if (id == runId) stable = true }
                }
                openJobs += launch {
                    while (true) {
                        clock.delay(PROBE_INTERVAL_MS)
                        val seen = inbound.get()
                        val probeId = synchronized(lock) { nextId += 1; "c$nextId" }
                        val sent = webSocket.send("""{"type":"ping","id":"$probeId"}""")
                        if (sent) clock.delay(PROBE_TIMEOUT_MS)
                        if (!sent || inbound.get() == seen) {
                            webSocket.cancel()
                            return@launch
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                inbound.incrementAndGet()
                frames.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                done.complete(Outcome(code, null))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(Outcome(code, null))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.complete(Outcome(null, response?.code))
            }
        }
        val ws = socketHttp.newWebSocket(request, listener)
        try {
            done.await()
        } finally {
            openJobs.forEach { it.cancel() }
            if (!done.isCompleted) ws.cancel()
            synchronized(lock) { if (socket === ws) socket = null }
            frames.close()
            // Finish queued frames first, so an ack that raced the close is not turned into a failure.
            processor.join()
            failAllPending()
        }
    }

    private fun handle(id: Int, text: String) {
        if (!current(id)) return
        when (val f = decodeFrame(text)) {
            is IncomingFrame.Event -> eventChannel.trySend(f.event)
            is IncomingFrame.Ack -> resolve(f.id, f.data, null)
            is IncomingFrame.Error -> resolve(f.id, null, ServerException(0, f.code, null, null))
            is IncomingFrame.Malformed, null -> Unit // dropped; nothing is logged
        }
    }
}
