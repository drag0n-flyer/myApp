package org.example.app.profile

import android.graphics.Bitmap
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import org.example.app.core.people.Profile

/**
 * The profiles the signed-in store knows, by account ID: the one place tiles read a contact's (or the person's own)
 * photo from. Null where nothing provides it (the tile then shows the coloured letter).
 */
val LocalProfilePhotos = staticCompositionLocalOf<StateFlow<Map<String, Profile>>?> { null }

/** Decoded profile photos, so a list does not decode the same bytes on every scroll. Cleared at sign-out. */
object ProfilePhotoCache {
    private const val MAX_ENTRIES = 64
    private val entries = object : LinkedHashMap<Int, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap?>?) = size > MAX_ENTRIES
    }

    private fun key(bytes: ByteArray) = bytes.contentHashCode() * 31 + bytes.size

    /** The decoded photo or null when the bytes are refused or do not decode. Call off the main thread. */
    fun get(bytes: ByteArray): Bitmap? {
        val k = key(bytes)
        synchronized(entries) { if (entries.containsKey(k)) return entries[k] }
        val decoded = ProfilePhoto.bitmap(bytes)
        synchronized(entries) { entries[k] = decoded }
        return decoded
    }

    /** Forgets every decoded photo (sign-out: nothing of one account stays in memory for the next). */
    fun clear() = synchronized(entries) { entries.clear() }

    val size: Int get() = synchronized(entries) { entries.size }
}
