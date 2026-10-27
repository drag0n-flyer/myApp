package org.example.app.net

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import org.example.app.contracts.SecretNames
import org.example.app.contracts.SecretStore

@Serializable
data class Session(val host: String, val accountId: String, val deviceId: String, val token: String)

/** Caches the stored session. `generation` increases on every save. */
class SessionStore(private val store: SecretStore) {
    private val lock = Any()
    private var cached: Session? = try {
        store.load(SecretNames.SESSION)?.let { ServerJson.json.decodeFromString(Session.serializer(), it.decodeToString()) }
    } catch (_: Exception) {
        null
    }
    private val saves = AtomicInteger(0)

    val current: Session? get() = synchronized(lock) { cached }

    internal val generation: Int get() = saves.get()

    fun save(s: Session) {
        synchronized(lock) {
            store.save(SecretNames.SESSION, ServerJson.json.encodeToString(Session.serializer(), s).encodeToByteArray())
            cached = s
            saves.incrementAndGet()
        }
    }

    /** Deletes from storage first; the cache is dropped only once that succeeded. */
    fun clear() {
        synchronized(lock) {
            store.delete(SecretNames.SESSION)
            cached = null
        }
    }
}
