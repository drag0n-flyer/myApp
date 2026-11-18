package org.example.app.core.people

/** The shape of a profile photo on the wire and in the store: a square JPEG of at most 24,576 bytes. */
object ProfilePhotoRule {
    const val MAX_BYTES = 24_576
    const val SIDE_PIXELS = 256

    /** JPEG qualities tried in turn, best first. */
    val QUALITIES: List<Int> = listOf(90, 80, 70, 60, 50, 40, 30)

    /** True for bytes that start like a JPEG and fit the limit. */
    fun isAcceptable(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size in 2..MAX_BYTES && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    /** The first [QUALITIES] step whose [encode] result is acceptable, or null when none fits. [encode] may return null for a failure. */
    fun fit(encode: (quality: Int) -> ByteArray?): ByteArray? {
        for (quality in QUALITIES) {
            val bytes = encode(quality)
            if (isAcceptable(bytes)) return bytes
        }
        return null
    }

    /** Edge of the centred square cut from a [width] by [height] image. */
    fun cropSide(width: Int, height: Int): Int = minOf(width, height)
}
