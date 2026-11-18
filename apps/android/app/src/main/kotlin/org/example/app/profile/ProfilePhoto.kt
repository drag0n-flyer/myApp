package org.example.app.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import org.example.app.core.people.ProfilePhotoRule

/**
 * The person's profile photo, made with the platform's own image APIs: centre-cropped to a square, scaled to 256 px,
 * the camera's rotation baked in, and re-encoded as a JPEG that carries no metadata (`Bitmap.compress` writes none, so
 * GPS and camera data are gone). The quality steps down until it is at most 24,576 bytes; if it never fits the answer is null.
 */
object ProfilePhoto {
    /** Refuse a source whose pixel count would be unreasonable to decode (a decompression bomb). */
    private const val MAX_SOURCE_PIXELS = 100_000_000L

    /** A JPEG for the profile from the bytes of any picture the platform can decode, or null. */
    fun prepare(image: ByteArray): ByteArray? {
        if (image.isEmpty()) return null
        return try {
            val decoded = decodeSampled(image) ?: return null
            val oriented = orient(decoded, orientation(image))
            if (oriented !== decoded) decoded.recycle()
            val tile = squareTile(oriented)
            if (tile !== oriented) oriented.recycle()
            try {
                ProfilePhotoRule.fit { quality ->
                    ByteArrayOutputStream().also { if (!tile.compress(Bitmap.CompressFormat.JPEG, quality, it)) return@fit null }.toByteArray()
                }
            } finally {
                tile.recycle()
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * True when [jpeg] passes [ProfilePhotoRule] (size, and a declared size of at most 1024 x 1024 read from the frame
     * header without decoding) and the platform reads it as a JPEG of that size.
     */
    fun decodes(jpeg: ByteArray): Boolean {
        if (!ProfilePhotoRule.isAcceptable(jpeg)) return false
        return try {
            val bounds = boundsOf(jpeg)
            bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..ProfilePhotoRule.MAX_SIDE && bounds.outHeight in 1..ProfilePhotoRule.MAX_SIDE &&
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.also { it.recycle() } != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The picture to draw, or null (the tile then shows the coloured letter). The rule is checked again here (a row
     * stored by an earlier build), the bounds are read first, and the decode is sub-sampled to about [targetSide] px,
     * so unchecked data is never decoded at full size. Call it off the main thread.
     */
    fun bitmap(jpeg: ByteArray, targetSide: Int = ProfilePhotoRule.SIDE_PIXELS): Bitmap? = try {
        if (!ProfilePhotoRule.isAcceptable(jpeg)) {
            null
        } else {
            val bounds = boundsOf(jpeg)
            if (bounds.outWidth !in 1..ProfilePhotoRule.MAX_SIDE || bounds.outHeight !in 1..ProfilePhotoRule.MAX_SIDE) {
                null
            } else {
                var sample = 1
                while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetSide) sample *= 2
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun boundsOf(bytes: ByteArray) = BitmapFactory.Options().also {
        it.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it)
    }

    /** Decodes with a power-of-two sample so the short side stays at least [ProfilePhotoRule.SIDE_PIXELS]. */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = boundsOf(bytes)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_SOURCE_PIXELS) return null
        var sample = 1
        while (minOf(w, h) / (sample * 2) >= ProfilePhotoRule.SIDE_PIXELS) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun orientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun orient(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** The centred square, scaled to the tile size, on white where the picture is transparent. */
    private fun squareTile(src: Bitmap): Bitmap {
        val side = max(1, ProfilePhotoRule.cropSide(src.width, src.height))
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val square = Bitmap.createBitmap(src, left, top, side, side)
        val scaled = if (side == ProfilePhotoRule.SIDE_PIXELS) square else Bitmap.createScaledBitmap(square, ProfilePhotoRule.SIDE_PIXELS, ProfilePhotoRule.SIDE_PIXELS, true)
        if (square !== scaled && square !== src) square.recycle()
        if (!scaled.hasAlpha()) return scaled
        val flat = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
        if (scaled !== src) scaled.recycle()
        return flat
    }
}
