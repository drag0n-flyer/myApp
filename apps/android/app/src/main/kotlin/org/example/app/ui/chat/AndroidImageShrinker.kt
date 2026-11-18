package org.example.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import org.example.app.net.preview.ImageShrinker

/**
 * Re-encodes a downloaded preview picture with the system decoder and encoder: decoded at reduced size (never the full
 * picture), scaled down in steps and compressed at falling quality until it is a JPEG of at most [MAX_BYTES]. A picture the
 * system cannot read, or one that stays too big, gives null and the preview has no picture.
 */
class AndroidImageShrinker : ImageShrinker {
    companion object {
        const val MAX_BYTES = 8_192
        /** A picture with more pixels than this is not decoded at all. */
        private const val MAX_PIXELS = 25_000_000L
        private const val DECODE_SIDE = 512
        private val SIDES = listOf(128, 96, 64, 48)
        private val QUALITIES = listOf(70, 55, 40, 25)
    }

    override fun toJpeg(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return null
        var sample = 1
        while (maxOf(w, h) / sample > DECODE_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            for (side in SIDES) {
                val scale = minOf(1f, side.toFloat() / maxOf(decoded.width, decoded.height))
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true)
                } else {
                    decoded
                }
                try {
                    for (quality in QUALITIES) {
                        val out = ByteArrayOutputStream()
                        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                        if (out.size() <= MAX_BYTES) return out.toByteArray()
                    }
                } finally {
                    if (scaled !== decoded) scaled.recycle()
                }
            }
            return null
        } finally {
            decoded.recycle()
        }
    }
}
