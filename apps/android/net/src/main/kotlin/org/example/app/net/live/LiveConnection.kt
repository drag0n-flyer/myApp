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
 * Pings are OkHttp's: one every [pingIntervalMillis]; a ping with no pong by the next tick drops the connection.
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
            j to ws
        }
        j?.cancel()
        ws?.close(1000, null)
        failAllPending()
        _states.value = ConnectionState.Disconnected
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
            if (outcome.httpStatus == 401 || outcome.code == 4003) {
                finish(id, ConnectionState.Revoked)
                client.handleRevoked(endpoint.generation)
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
        var stableTimer: Job? = null
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
                stableTimer = launch {
                    clock.delay(30_000)
                    synchronized(lock) { if (id == runId) stable = true }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
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
            stableTimer?.cancel()
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
