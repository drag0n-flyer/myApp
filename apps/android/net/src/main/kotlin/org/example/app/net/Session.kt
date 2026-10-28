package org.example.app.net

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import org.example.app.contracts.SecretNames
import org.example.app.contracts.SecretStore
import org.example.app.contracts.SecretStoreUnavailable

/**
 * [scheme] is `https` or `http`: the scheme of the client the session was made over. It matters only for a
 * loopback host (a development server); any other host is always https. Null for a session saved before it
 * was recorded.
 */
@Serializable
data class Session(
    val host: String,
    val accountId: String,
    val deviceId: String,
    val token: String,
    val scheme: String? = null,
) {
    /** The token is never printed. */
    override fun toString() = "Session(host=$host, accountId=$accountId, deviceId=$deviceId, scheme=$scheme)"
}

/**
 * What the store knows. [Unavailable] means the secret store was busy: there may be a session we could not
 * read. It is not "signed out": the app shows a retry screen, not onboarding.
 */
sealed interface SessionState {
    data object Absent : SessionState
    data class Present(val session: Session) : SessionState
    data object Unavailable : SessionState
}

/**
 * Caches the stored session. `generation` increases on every save. A load that fails with
 * `SecretStoreUnavailable` leaves the state [SessionState.Unavailable] and is retried on the next read;
 * [save] never overwrites a session that could not be read.
 */
class SessionStore(private val store: SecretStore) {
    private val lock = Any()
    private var cached: SessionState = load()
    private val saves = AtomicInteger(0)

    /** Retries the load first while [SessionState.Unavailable]. */
    val state: SessionState get() = synchronized(lock) {
        if (cached == SessionState.Unavailable) cached = load()
        cached
    }

    val isUnavailable: Boolean get() = state == SessionState.Unavailable

    /** The session, or null when absent or unavailable (see [state]). */
    val current: Session? get() = (state as? SessionState.Present)?.session

    internal val generation: Int get() = saves.get()

    private fun load(): SessionState = try {
        store.load(SecretNames.SESSION)
            ?.let { SessionState.Present(ServerJson.json.decodeFromString(Session.serializer(), it.decodeToString())) }
            ?: SessionState.Absent
    } catch (_: SecretStoreUnavailable) {
        SessionState.Unavailable
    } catch (_: Exception) {
        SessionState.Absent
    }

    /** @throws SecretStoreUnavailable when the stored session still cannot be read: it is not overwritten. */
    fun save(s: Session) {
        synchronized(lock) {
            if (state == SessionState.Unavailable) throw SecretStoreUnavailable("the stored session could not be read")
            store.save(SecretNames.SESSION, ServerJson.json.encodeToString(Session.serializer(), s).encodeToByteArray())
            cached = SessionState.Present(s)
            saves.incrementAndGet()
        }
    }

    /** Deletes from storage first; the cache is dropped only once that succeeded. */
    fun clear() {
        synchronized(lock) {
            store.delete(SecretNames.SESSION)
            cached = SessionState.Absent
        }
    }
}
