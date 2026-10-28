package org.example.app.contracts

/**
 * Push payload padding (docs/protocol/push.md): a 2-byte big-endian length, the payload, then
 * zero bytes up to a bucket size. Unpadding is strict.
 */
object PushPadding {
    val BUCKETS = intArrayOf(256, 512, 1024, 2048, 3072)

    /** Returns the payload, or null when [padded] is malformed. */
    fun unpad(padded: ByteArray): ByteArray? {
        if (padded.size !in BUCKETS) return null
        val length = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        if (2 + length > padded.size) return null
        for (i in 2 + length until padded.size) {
            if (padded[i] != 0.toByte()) return null
        }
        return padded.copyOfRange(2, 2 + length)
    }
}
