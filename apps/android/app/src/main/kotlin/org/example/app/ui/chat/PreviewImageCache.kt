package org.example.app.ui.chat

/**
 * A small least-recently-used cache for decoded preview pictures, keyed by the picture's bytes (not the array object), so a
 * picture is decoded once however often its bubble is composed or recycled. A picture that cannot be decoded is
 * remembered as none. Safe to call from any thread.
 */
class PreviewImageCache<T : Any>(private val max: Int) {
    private class Key(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Key && bytes.contentEquals(other.bytes)
    }

    private class Entry<T>(val value: T?)

    private val map = object : LinkedHashMap<Key, Entry<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry<T>>) = size > max
    }

    fun get(bytes: ByteArray, decode: (ByteArray) -> T?): T? {
        val key = Key(bytes)
        synchronized(map) { map[key]?.let { return it.value } }
        val value = decode(bytes)
        synchronized(map) { map[key] = Entry(value) }
        return value
    }
}
