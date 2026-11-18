package org.example.app.core.people

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.app.core.chats.Chat

/** A typing or presence signal. */
sealed interface Signal {
    data class Typing(val on: Boolean) : Signal
    /** [online] false is `away`. */
    data class Presence(val online: Boolean) : Signal
}

class IncomingSignal(val chatId: String, val signal: Signal)

/** Where signals go and come from: `:app` wires it to the `conv.ephemeral` channel; sample data and tests use [ScriptedSignals]. */
interface SignalChannel {
    /** True when the frame went out. Nothing is retried. */
    suspend fun send(chatId: String, signal: Signal): Boolean
    /** Signals that qualified (right conversation, right sender). Not replayed. */
    val incoming: Flow<IncomingSignal>
}

/** Per-contact "last witnessed" times (ms), kept across restarts. No database migration: `:app` keeps them in the key-value store. */
interface WitnessStore {
    suspend fun load(): Map<String, Long>
    suspend fun save(chatId: String, atMs: Long)
}

object NoWitnessStore : WitnessStore {
    override suspend fun load(): Map<String, Long> = emptyMap()
    override suspend fun save(chatId: String, atMs: Long) {}
}

/** What the screens show for one chat. */
data class PeerView(val typing: Boolean, val presence: PresenceLine.Result)

/**
 * Typing and presence for the chat screens (design 6.1). Sends and shows nothing for a switched-off signal or a blocked
 * contact. Only one-to-one chats take part: [presenceTargets] names the chats that get `online` and `away`.
 *
 * Time is read from [nowMs], and every delay runs on [scope], so tests drive it with a virtual clock. No loop runs
 * forever but the 60 s presence heartbeat, and that one only while [setForeground] is true.
 */
class PeopleSignals(
    private val scope: CoroutineScope,
    private val channel: SignalChannel,
    private val typingEnabled: () -> Boolean,
    private val presenceEnabled: () -> Boolean,
    /** True for a chat whose contact is blocked (or that is not known): nothing is sent to it or shown for it. */
    private val blocked: (chatId: String) -> Boolean,
    private val presenceTargets: () -> List<String>,
    private val witnesses: WitnessStore = NoWitnessStore,
    private val nowMs: () -> Long,
) {
    private val lock = Any()
    private val typingOn = HashMap<String, Long>()
    private val lastOnline = HashMap<String, Long>()
    private val witnessed = HashMap<String, Long>()
    private val persisted = HashMap<String, Long>()
    private val senders = HashMap<String, TypingSender.State>()
    private var foreground = false
    private var heartbeat: Job? = null
    private var expiryJob: Job? = null
    private var tickJob: Job? = null
    // Typing goes first and is never dropped; presence is bounded and drops its oldest frames when the queue is full.
    private val typingQueue = ArrayDeque<Pair<String, Signal>>()
    private val presenceQueue = ArrayDeque<Pair<String, Signal>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _views = MutableStateFlow<Map<String, PeerView>>(emptyMap())

    /** The state per chat; chats with nothing to show are absent. */
    val views: StateFlow<Map<String, PeerView>> = _views.asStateFlow()

    init {
        scope.launch {
            for (ignored in wake) {
                while (true) {
                    val next = synchronized(lock) { typingQueue.removeFirstOrNull() ?: presenceQueue.removeFirstOrNull() } ?: break
                    try { channel.send(next.first, next.second) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
            }
        }
        scope.launch {
            try {
                val saved = witnesses.load()
                synchronized(lock) { for ((k, v) in saved) { witnessed.putIfAbsent(k, v); persisted[k] = v } }
                refresh()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        scope.launch { channel.incoming.collect { onIncoming(it) } }
    }

    // ---- Showing

    /** Recomputes [views] from what is known and the current settings and blocks. */
    fun refresh() {
        val now = nowMs()
        val typingOk = typingEnabled()
        val presenceOk = presenceEnabled()
        val next = HashMap<String, PeerView>()
        var deadline: Long? = null
        synchronized(lock) {
            val chats = typingOn.keys + lastOnline.keys + witnessed.keys
            for (chat in chats) {
                if (blocked(chat)) continue
                val typing = typingOk && TypingShown.isTyping(typingOn[chat], now)
                val line = if (presenceOk) PresenceLine.text(lastOnline[chat], witnessed[chat], now) else PresenceLine.Result.None
                if (typing || line != PresenceLine.Result.None) next[chat] = PeerView(typing, line)
                if (typing) typingOn[chat]?.let { deadline = minOf(deadline ?: Long.MAX_VALUE, it + TypingShown.SHOWN_MS) }
                if (line == PresenceLine.Result.Online) lastOnline[chat]?.let { deadline = minOf(deadline ?: Long.MAX_VALUE, it + PresenceLine.ONLINE_MS) }
            }
        }
        _views.value = next
        scheduleExpiry(deadline, now)
    }

    private fun scheduleExpiry(deadline: Long?, now: Long) {
        synchronized(lock) {
            expiryJob?.cancel()
            expiryJob = null
            if (deadline == null) return
            expiryJob = scope.launch {
                delay(maxOf(0L, deadline - now))
                refresh()
            }
        }
    }

    private fun onIncoming(event: IncomingSignal) {
        val chat = event.chatId
        if (blocked(chat)) return
        val now = nowMs()
        when (val s = event.signal) {
            is Signal.Typing -> {
                if (!typingEnabled()) return
                synchronized(lock) { if (s.on) typingOn[chat] = now else typingOn.remove(chat) }
                witness(chat, now, force = false)
            }
            is Signal.Presence -> {
                if (!presenceEnabled()) return
                synchronized(lock) { if (s.online) lastOnline[chat] = now else lastOnline.remove(chat) }
                witness(chat, now, force = !s.online)
            }
        }
        refresh()
    }

    /** A message from the contact arrived: the typing line goes at once and the contact counts as seen now. */
    fun messageArrived(chatId: String) {
        if (blocked(chatId)) return
        val now = nowMs()
        synchronized(lock) { typingOn.remove(chatId) }
        if (presenceEnabled()) witness(chatId, now, force = false)
        refresh()
    }

    private fun witness(chat: String, now: Long, force: Boolean) {
        if (!presenceEnabled()) return
        val save: Boolean
        synchronized(lock) {
            witnessed[chat] = now
            save = force || now - (persisted[chat] ?: Long.MIN_VALUE / 2) >= SAVE_EVERY_MS
            if (save) persisted[chat] = now
        }
        if (save) scope.launch { try { witnesses.save(chat, now) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    }

    /**
     * Watches the chat list for new messages from others ([myId] is this account). What is there when it starts is not
     * counted: only a newer incoming message makes [messageArrived] happen.
     */
    fun watchMessages(chats: StateFlow<List<Chat>>, myId: String): Job = scope.launch {
        val newest = HashMap<String, Long>()
        var first = true
        chats.collect { list ->
            for (chat in list) {
                val incoming = chat.messages.lastOrNull { it.senderId != myId }?.sentAt?.toEpochMilli()
                val seen = newest[chat.id]
                if (incoming != null && (seen == null || incoming > seen)) {
                    newest[chat.id] = incoming
                    if (!first) messageArrived(chat.id)
                }
            }
            first = false
        }
    }

    // ---- Sending

    /** The composer's text changed ([isEmpty]: nothing typed). Starts, repeats or ends the typing signal. */
    fun composerChanged(chatId: String, isEmpty: Boolean) = drive(chatId, TypingSender.Event.TextChanged(isEmpty))

    /** The message was sent: `on: false`. */
    fun messageSent(chatId: String) = drive(chatId, TypingSender.Event.Sent)

    private fun drive(chatId: String, event: TypingSender.Event) {
        if (!typingEnabled() || blocked(chatId)) {
            synchronized(lock) { senders.remove(chatId) }
            return
        }
        val step = synchronized(lock) {
            TypingSender.step(senders[chatId] ?: TypingSender.State(), event, nowMs()).also { senders[chatId] = it.state }
        }
        step.send?.let { enqueue(chatId to Signal.Typing(it)) }
        scheduleTick()
    }

    private fun scheduleTick() {
        val now = nowMs()
        synchronized(lock) {
            tickJob?.cancel()
            tickJob = null
            val due = senders.values.filter { it.typing }.minOfOrNull { minOf(it.lastKeyMs + TypingSender.IDLE_MS, it.lastSentMs + TypingSender.REPEAT_MS) } ?: return
            tickJob = scope.launch {
                delay(maxOf(0L, due - now))
                tick()
            }
        }
    }

    private fun tick() {
        val sends = ArrayList<Pair<String, Signal>>()
        val enabled = typingEnabled()
        val now = nowMs()
        synchronized(lock) {
            for (chat in senders.keys.toList()) {
                if (!enabled || blocked(chat)) { senders.remove(chat); continue }
                val step = TypingSender.step(senders.getValue(chat), TypingSender.Event.Tick, now)
                senders[chat] = step.state
                step.send?.let { sends += chat to Signal.Typing(it) }
            }
        }
        sends.forEach { enqueue(it) }
        scheduleTick()
    }

    /** The app came to the foreground (`online` now and every 60 s) or left it (`away`). */
    fun setForeground(value: Boolean) {
        synchronized(lock) {
            if (foreground == value) return
            foreground = value
            heartbeat?.cancel()
            heartbeat = null
        }
        if (value) {
            heartbeat = scope.launch {
                while (true) {
                    sendPresence(online = true)
                    delay(HEARTBEAT_MS)
                }
            }
        } else {
            sendPresence(online = false)
        }
    }

    private fun enqueue(item: Pair<String, Signal>) {
        synchronized(lock) {
            if (item.second is Signal.Typing) typingQueue.addLast(item)
            else {
                if (presenceQueue.size >= PRESENCE_QUEUE_MAX) presenceQueue.removeFirst()
                presenceQueue.addLast(item)
            }
        }
        wake.trySend(Unit)
    }

    private fun sendPresence(online: Boolean) {
        if (!presenceEnabled()) return
        for (chat in presenceTargets()) {
            if (!blocked(chat)) enqueue(chat to Signal.Presence(online))
        }
    }

    companion object {
        const val HEARTBEAT_MS = 60_000L
        private const val SAVE_EVERY_MS = 30_000L
        private const val PRESENCE_QUEUE_MAX = 64
    }
}

/** Who gets `online` and `away`: one-to-one chats with a message in the last 7 days, the 30 most recent first. */
object PresenceTargets {
    const val MAX_CHATS = 30
    const val WINDOW_MS = 7 * 86_400_000L

    fun pick(chats: List<Chat>, nowMs: Long): List<String> = chats
        .filter { !it.isRequest }
        .mapNotNull { chat -> chat.lastMessage?.sentAt?.toEpochMilli()?.takeIf { nowMs - it < WINDOW_MS }?.let { chat.id to it } }
        .sortedByDescending { it.second }
        .take(MAX_CHATS)
        .map { it.first }
}

/**
 * A channel that does what it is told: for sample data and UI tests. Signals pushed with [emit] show as incoming;
 * what the app sends is kept in [sent].
 */
class ScriptedSignals : SignalChannel {
    private val flow = MutableSharedFlow<IncomingSignal>(extraBufferCapacity = 64)
    private val log = ArrayList<Pair<String, Signal>>()

    override val incoming: Flow<IncomingSignal> = flow

    /** What the app sent, in order. */
    val sent: List<Pair<String, Signal>> get() = synchronized(log) { log.toList() }

    override suspend fun send(chatId: String, signal: Signal): Boolean {
        synchronized(log) { log += chatId to signal }
        return true
    }

    fun emit(chatId: String, signal: Signal) { flow.tryEmit(IncomingSignal(chatId, signal)) }

    /** Plays [steps] in order on [scope]: wait [Step.afterMs], then emit. */
    fun play(scope: CoroutineScope, steps: List<Step>): Job = scope.launch {
        for (step in steps) {
            delay(step.afterMs)
            step.action()
        }
    }

    class Step(val afterMs: Long, val action: () -> Unit)
}
