package org.example.app.ui.media

object PhotoDecoder {
    /**
     * The `inSampleSize` for a [width] x [height] image shown at [targetWidth] x [targetHeight]: the largest power of two
     * that leaves the decoded image no smaller than the target in either direction, and at least 1. Sizes of zero or
     * less give 1.
     */
    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        while (sample < MAX_SAMPLE && width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    private const val MAX_SAMPLE = 1 shl 20
}
