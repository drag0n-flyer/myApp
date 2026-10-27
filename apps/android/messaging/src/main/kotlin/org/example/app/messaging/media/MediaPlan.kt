package org.example.app.messaging.media

/** Sizing, quality and recording decisions (Design 9) as pure values and functions, the same as the iPhone's. */
object MediaPlan {
    /** Photos are re-encoded to JPEG with a longest side of at most this many pixels. */
    const val PHOTO_MAX_SIDE = 1_600
    const val PHOTO_QUALITY = 70

    /** Inline thumbnail: longest side, starting JPEG quality, step, floor and byte cap. */
    const val THUMBNAIL_SIDE = 32
    const val THUMBNAIL_QUALITY = 40
    const val THUMBNAIL_QUALITY_STEP = 10
    const val THUMBNAIL_MIN_QUALITY = 10
    const val THUMBNAIL_MAX_BYTES = 1_536

    /** The longest side is capped at [maxSide], the aspect ratio is kept, a smaller image is never enlarged. */
    fun targetSize(width: Int, height: Int, maxSide: Int = PHOTO_MAX_SIDE): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxSide <= 0) return maxOf(width, 0) to maxOf(height, 0)
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** JPEG qualities to try for the thumbnail, best first: 40, 30, 20, 10. */
    fun thumbnailQualities(): List<Int> = generateSequence(THUMBNAIL_QUALITY) { it - THUMBNAIL_QUALITY_STEP }
        .takeWhile { it >= THUMBNAIL_MIN_QUALITY }.toList()

    /** Voice notes: Ogg Opus (RFC 7845), 16 kHz mono, 16 kbps, on every platform. */
    object Voice {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BIT_RATE = 16_000
        const val FILE_EXTENSION = "ogg"
        const val MIME = "audio/ogg; codecs=opus"
        /** A recording shorter than this is treated as an accidental tap and discarded. */
        const val MIN_DURATION = 0.5
        /** Recording stops by itself at this length (30 minutes). */
        const val MAX_DURATION = 1_800.0

        fun tooShort(duration: Double) = duration < MIN_DURATION
        fun clamped(duration: Double) = duration.coerceIn(0.0, MAX_DURATION)
    }
}
