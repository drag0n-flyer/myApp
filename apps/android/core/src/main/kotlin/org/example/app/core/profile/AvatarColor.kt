package org.example.app.core.profile

/** Which of the 8 tile colours a person gets: FNV-1a 32-bit over the account ID's UTF-8 bytes, modulo 8. The iPhone app computes the same. */
object AvatarColor {
    const val COUNT = 8

    private const val OFFSET_BASIS = 2166136261L
    private const val PRIME = 16777619L

    fun index(accountId: String): Int {
        var hash = OFFSET_BASIS
        for (b in accountId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xFF)
            hash = (hash * PRIME) and 0xFFFFFFFFL
        }
        return (hash % COUNT).toInt()
    }
}
