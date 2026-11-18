package org.example.app.core.people

/** The shape of a profile photo on the wire and in the store: a JPEG of at most 24,576 bytes declaring at most 1,024 x 1,024 pixels. */
object ProfilePhotoRule {
    const val MAX_BYTES = 24_576
    const val SIDE_PIXELS = 256

    /** JPEG qualities tried in turn, best first. */
    val QUALITIES: List<Int> = listOf(90, 80, 70, 60, 50, 40, 30)

    /** Largest declared width or height. */
    const val MAX_SIDE = 1024
    /** Fewest bytes a photo can have; checked before looking at the header. */
    const val MIN_BYTES = 4

    /**
     * True for a JPEG of [MIN_BYTES]..[MAX_BYTES] bytes whose frame header (see [JpegFrame]) declares a size of 1..[MAX_SIDE]
     * on both sides. Run on receive before anything is stored, and again before a stored photo is decoded.
     */
    fun isAcceptable(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < MIN_BYTES || bytes.size > MAX_BYTES) return false
        val (w, h) = JpegFrame.dimensions(bytes) ?: return false
        return w in 1..MAX_SIDE && h in 1..MAX_SIDE
    }

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
