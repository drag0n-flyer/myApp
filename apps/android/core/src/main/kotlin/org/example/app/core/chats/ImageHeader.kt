package org.example.app.core.chats

/**
 * The format and declared size of an image, read from its first bytes without decoding anything. Used to decide what an
 * image is from its content (never from a header a server sent) and to refuse an oversized picture before any decoder
 * allocates for it. Recognises JPEG, PNG, WebP and GIF only; anything else is null.
 */
object ImageHeader {
    enum class Format { Jpeg, Png, WebP, Gif }

    data class Info(val format: Format, val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height.toLong()
    }

    fun read(b: ByteArray): Info? {
        val info = when {
            isPng(b) -> png(b)
            isGif(b) -> gif(b)
            isJpeg(b) -> jpeg(b)
            isWebP(b) -> webp(b)
            else -> null
        } ?: return null
        return if (info.width > 0 && info.height > 0) info else null
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
    private fun be32(b: ByteArray, i: Int): Int = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun ascii(b: ByteArray, i: Int, s: String) = i + s.length <= b.size && s.indices.all { b[i + it].toInt() == s[it].code }

    private fun isPng(b: ByteArray) = b.size >= 8 && u8(b, 0) == 0x89 && ascii(b, 1, "PNG") && u8(b, 4) == 0x0D && u8(b, 5) == 0x0A && u8(b, 6) == 0x1A && u8(b, 7) == 0x0A
    private fun isGif(b: ByteArray) = ascii(b, 0, "GIF87a") || ascii(b, 0, "GIF89a")
    private fun isJpeg(b: ByteArray) = b.size >= 3 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 && u8(b, 2) == 0xFF
    private fun isWebP(b: ByteArray) = ascii(b, 0, "RIFF") && ascii(b, 8, "WEBP")

    private fun png(b: ByteArray): Info? {
        if (b.size < 24 || !ascii(b, 12, "IHDR")) return null
        return Info(Format.Png, be32(b, 16), be32(b, 20))
    }

    private fun gif(b: ByteArray): Info? = if (b.size < 10) null else Info(Format.Gif, le16(b, 6), le16(b, 8))

    private fun jpeg(b: ByteArray): Info? {
        var i = 2
        while (i + 4 <= b.size) {
            if (u8(b, i) != 0xFF) return null
            while (i < b.size && u8(b, i) == 0xFF) i++
            if (i >= b.size) return null
            val marker = u8(b, i++)
            // Markers without a length: standalone ones.
            if (marker == 0x01 || marker in 0xD0..0xD9) continue
            if (i + 2 > b.size) return null
            val len = be16(b, i)
            if (len < 2) return null
            // Start of frame (not DHT 0xC4, JPG 0xC8, DAC 0xCC): precision, height, width.
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (i + 7 > b.size) return null
                return Info(Format.Jpeg, be16(b, i + 5), be16(b, i + 3))
            }
            i += len
        }
        return null
    }

    private fun webp(b: ByteArray): Info? {
        if (b.size < 16) return null
        return when {
            ascii(b, 12, "VP8 ") -> {
                // Chunk data starts at 20: frame tag (3 bytes), start code 9D 01 2A, then 14-bit width and height.
                if (b.size < 30 || u8(b, 23) != 0x9D || u8(b, 24) != 0x01 || u8(b, 25) != 0x2A) null
                else Info(Format.WebP, le16(b, 26) and 0x3FFF, le16(b, 28) and 0x3FFF)
            }
            ascii(b, 12, "VP8L") -> {
                if (b.size < 25 || u8(b, 20) != 0x2F) null
                else {
                    val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
                    Info(Format.WebP, (bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
                }
            }
            ascii(b, 12, "VP8X") -> {
                if (b.size < 30) null else Info(Format.WebP, le24(b, 24) + 1, le24(b, 27) + 1)
            }
            else -> null
        }
    }
}
