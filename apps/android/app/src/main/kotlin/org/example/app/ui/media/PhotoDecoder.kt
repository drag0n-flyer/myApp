package org.example.app.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

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

    /** A decoded photo never holds more pixels than this (8 megapixels, 32 MB), whatever the box asks for. */
    const val DEFAULT_MAX_PIXELS = 8_000_000L

    /**
     * Decodes [file] sampled for a box of [boxWidth] x [boxHeight] pixels it will be fitted into ([oversample] times as
     * much detail, for zooming). The full-resolution image is never loaded. Null when the file is missing, empty or not an
     * image the platform can read, or when memory ran out: the caller shows a neutral placeholder. Blocking: call it off
     * the main thread.
     */
    fun decodeFit(file: File, boxWidth: Int, boxHeight: Int, oversample: Int = 1, maxPixels: Long = DEFAULT_MAX_PIXELS): Bitmap? {
        if (boxWidth <= 0 || boxHeight <= 0 || oversample < 1 || maxPixels <= 0) return null
        if (!file.isFile || file.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null
            val scale = minOf(1.0, boxWidth.toDouble() / width, boxHeight.toDouble() / height)
            val targetWidth = max(1, ceil(width * scale * oversample).toInt())
            val targetHeight = max(1, ceil(height * scale * oversample).toInt())
            var sample = sampleSize(width, height, targetWidth, targetHeight)
            while (sample < MAX_SAMPLE && (width / sample).toLong() * (height / sample) > maxPixels) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

/** Decoded bubble photos, held for the life of one chat screen. An eighth of the heap, counted in bytes. */
class PhotoCache(maxBytes: Int = (Runtime.getRuntime().maxMemory() / 8).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    operator fun get(key: String): Bitmap? = cache.get(key)
    operator fun set(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }

    /** Frees the pictures: they are the contents of a conversation. */
    fun clear() = cache.evictAll()
}
