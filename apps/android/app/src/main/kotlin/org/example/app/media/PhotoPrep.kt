package org.example.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import org.example.app.messaging.media.MediaPlan

/** A photo ready to encrypt: [file] is the JPEG, [thumbnail] the inline preview (at most 1 536 bytes). */
class Prepared(val file: File, val width: Int, val height: Int, val thumbnail: ByteArray)

class PhotoPrepException(message: String) : Exception(message)

/**
 * Re-encodes a photo for sending (Design 9): JPEG, longest side at most 1 600 px, quality 70, orientation baked
 * into the pixels, and no metadata (`Bitmap.compress` writes no EXIF, so GPS and camera data are dropped).
 * Sizes and qualities come from [MediaPlan]. Output goes to [outputDir]; the caller deletes it after sending.
 */
class PhotoPrep(private val context: Context, private val outputDir: File) {
    fun prepare(uri: Uri): Prepared {
        val main = decode(uri, MediaPlan.PHOTO_MAX_SIDE)
        try {
            outputDir.mkdirs()
            val out = File.createTempFile("photo", ".jpg", outputDir)
            try {
                val flat = flattened(main)
                try {
                    FileOutputStream(out).use { if (!flat.compress(Bitmap.CompressFormat.JPEG, MediaPlan.PHOTO_QUALITY, it)) throw PhotoPrepException("encoding failed") }
                } finally {
                    if (flat !== main) flat.recycle()
                }
                val thumb = thumbnail(main)
                return Prepared(out, main.width, main.height, thumb)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        } finally {
            main.recycle()
        }
    }

    /**
     * Decodes with the longest side capped (a sub-sampled decode first, so a 50 MP photo is never fully in memory)
     * and the EXIF orientation applied to the pixels.
     */
    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw PhotoPrepException("unreadable image")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PhotoPrepException("unreadable image")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw PhotoPrepException("unreadable image")
        val oriented = orient(decoded, orientation(uri))
        if (oriented !== decoded) decoded.recycle()
        val (w, h) = MediaPlan.targetSize(oriented.width, oriented.height, maxSide)
        if (w == oriented.width && h == oriented.height) return oriented
        val scaled = Bitmap.createScaledBitmap(oriented, w, h, true)
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    private fun orientation(uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        // A malformed EXIF block must not stop a photo from being sent.
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

    /** JPEG has no alpha: transparent pixels become white instead of black. */
    private fun flattened(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return flat
    }

    private fun thumbnail(main: Bitmap): ByteArray {
        val (w, h) = MediaPlan.targetSize(main.width, main.height, MediaPlan.THUMBNAIL_SIDE)
        val scaled = Bitmap.createScaledBitmap(main, max(w, 1), max(h, 1), true)
        val small = flattened(scaled)
        try {
            for (q in MediaPlan.thumbnailQualities()) {
                val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
                if (bytes.size <= MediaPlan.THUMBNAIL_MAX_BYTES) return bytes
            }
            throw PhotoPrepException("thumbnail too large")
        } finally {
            if (small !== scaled) small.recycle()
            if (scaled !== main) scaled.recycle()
        }
    }
}
