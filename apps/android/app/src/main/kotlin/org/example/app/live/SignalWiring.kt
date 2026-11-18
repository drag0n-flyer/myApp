package org.example.app.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import org.example.app.core.chats.ChatStore
import org.example.app.core.people.PeopleSignals
import org.example.app.core.people.PresenceTargets
import org.example.app.core.settings.UserSettings
import kotlinx.coroutines.withContext
import org.example.app.core.people.IncomingSignal
import org.example.app.core.people.Signal
import org.example.app.core.people.SignalChannel
import org.example.app.core.people.WitnessStore
import org.example.app.messaging.EphemeralChannel
import org.example.app.messaging.content.ContentEnvelope
import org.example.app.store.RoomRepository

/** The session's `conv.ephemeral` channel as the screens' [SignalChannel]. Nothing is stored and no MLS call is made. */
class EphemeralSignalChannel(private val ephemeral: EphemeralChannel, private val nowMs: () -> Long = System::currentTimeMillis) : SignalChannel {
    override suspend fun send(chatId: String, signal: Signal): Boolean {
        val id = java.util.UUID.randomUUID().toString()
        val body = when (signal) {
            is Signal.Typing -> ContentEnvelope.Typing(id, nowMs(), signal.on)
            is Signal.Presence -> ContentEnvelope.Presence(id, nowMs(), if (signal.online) "online" else "away")
        }
        return ephemeral.send(chatId, body)
    }

    override val incoming: Flow<IncomingSignal> = ephemeral.events.mapNotNull { event ->
        when (val body = event.body) {
            is ContentEnvelope.Typing -> IncomingSignal(event.chatId, Signal.Typing(body.on))
            is ContentEnvelope.Presence -> IncomingSignal(event.chatId, Signal.Presence(body.state == "online"))
            else -> null // EphemeralChannel emits only these two kinds
        }
    }
}

/** "Last witnessed" times in the database's existing key-value table (no migration): one 8-byte big-endian value per chat. */
class KvWitnessStore(private val repo: RoomRepository) : WitnessStore {
    override suspend fun load(): Map<String, Long> = withContext(Dispatchers.IO) {
        repo.entries(PREFIX).mapNotNull { (key, value) ->
            if (value.size != 8) null else key.removePrefix(PREFIX) to java.nio.ByteBuffer.wrap(value).long
        }.toMap()
    }

    override suspend fun save(chatId: String, atMs: Long) = withContext(Dispatchers.IO) {
        repo.set(PREFIX + chatId, java.nio.ByteBuffer.allocate(8).putLong(atMs).array())
    }

    private companion object {
        const val PREFIX = "witness."
    }
}

/**
 * The screens' [PeopleSignals] over [channel] and [store]. Nothing is sent to or shown for a blocked contact, a chat that
 * is not known, or a message request. Turning a Privacy switch off or blocking a contact clears a line already shown.
 */
fun buildSignals(
    into: CoroutineScope, channel: SignalChannel, store: ChatStore, settings: StateFlow<UserSettings>, witnesses: WitnessStore,
    nowMs: () -> Long = System::currentTimeMillis,
): PeopleSignals {
    val signals = PeopleSignals(
        scope = into, channel = channel,
        typingEnabled = { settings.value.typingIndicator },
        presenceEnabled = { settings.value.onlineAndLastSeen },
        blocked = { id -> store.chat(id).let { it == null || it.isRequest || it.contactId in store.blockedIds.value } },
        presenceTargets = { PresenceTargets.pick(store.chats.value, nowMs()) },
        witnesses = witnesses, nowMs = nowMs,
    )
    signals.watchMessages(store.chats, store.me.id)
    into.launch {
        combine(settings.map { it.typingIndicator to it.onlineAndLastSeen }.distinctUntilChanged(), store.blockedIds) { _, _ -> }
            .collect { signals.refresh() }
    }
    return signals
}
