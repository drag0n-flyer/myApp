package org.example.app.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    override val incoming: Flow<IncomingSignal> = ephemeral.events.map { event ->
        when (val body = event.body) {
            is ContentEnvelope.Typing -> IncomingSignal(event.chatId, Signal.Typing(body.on))
            is ContentEnvelope.Presence -> IncomingSignal(event.chatId, Signal.Presence(body.state == "online"))
            else -> error("only typing and presence qualify") // EphemeralChannel never emits another kind
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
