package org.example.app.profile

import android.graphics.Bitmap
import java.security.MessageDigest
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import org.example.app.core.people.Profile

/**
 * The profiles the signed-in store knows, by account ID: the one place tiles read a contact's (or the person's own)
 * photo from. Null where nothing provides it (the tile then shows the coloured letter).
 */
val LocalProfilePhotos = staticCompositionLocalOf<StateFlow<Map<String, Profile>>?> { null }

/**
 * Decoded profile photos, so a list does not decode the same bytes on every scroll. Keyed by the SHA-256 of the bytes
 * (a crafted photo cannot take another's entry). Cleared at sign-out; a decode that started before the clear never
 * puts its result back.
 */
object ProfilePhotoCache {
    private const val MAX_ENTRIES = 64
    private val entries = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) = size > MAX_ENTRIES
    }
    private var generation = 0

    private fun key(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The decoded photo or null when the bytes are refused or do not decode. Call off the main thread. */
    fun get(bytes: ByteArray, decode: (ByteArray) -> Bitmap? = ProfilePhoto::bitmap): Bitmap? {
        val k = key(bytes)
        val started = synchronized(entries) {
            if (entries.containsKey(k)) return entries[k]
            generation
        }
        val decoded = decode(bytes)
        synchronized(entries) { if (generation == started) entries[k] = decoded }
        return decoded
    }

    /** Forgets every decoded photo (sign-out: nothing of one account stays in memory for the next). */
    fun clear() = synchronized(entries) { generation++; entries.clear() }

    val size: Int get() = synchronized(entries) { entries.size }
}
