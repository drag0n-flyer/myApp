package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageHeaderTest {
    private fun be32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun le16(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte())
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

    private fun png(w: Int, h: Int) = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + "IHDR".encodeToByteArray() + be32(w) + be32(h) + ByteArray(9)
    private fun gif(w: Int, h: Int) = "GIF89a".encodeToByteArray() + le16(w) + le16(h) + ByteArray(3)
    private fun jpeg(w: Int, h: Int) = byteArrayOf(-1, -40, -1, -32, 0, 4, 0, 0, -1, -64, 0, 11, 8) +
        byteArrayOf((h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte()) + byteArrayOf(1, 1, 0x11, 0)
    private fun riff(chunk: ByteArray) = "RIFF".encodeToByteArray() + le32(4 + chunk.size) + "WEBP".encodeToByteArray() + chunk
    private fun webpLossy(w: Int, h: Int) = riff("VP8 ".encodeToByteArray() + le32(10) + byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A) + le16(w) + le16(h))
    private fun webpLossless(w: Int, h: Int): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14)
        return riff("VP8L".encodeToByteArray() + le32(5) + byteArrayOf(0x2F) + le32(bits))
    }
    private fun webpExtended(w: Int, h: Int) = riff("VP8X".encodeToByteArray() + le32(10) + ByteArray(4) + le32(w - 1).copyOf(3) + le32(h - 1).copyOf(3))

    @Test fun eachFormatIsRecognisedFromItsBytesWithItsDeclaredSize() {
        assertEquals(ImageHeader.Info(ImageHeader.Format.Png, 640, 480), ImageHeader.read(png(640, 480)))
        assertEquals(ImageHeader.Info(ImageHeader.Format.Gif, 300, 200), ImageHeader.read(gif(300, 200)))
        assertEquals(ImageHeader.Info(ImageHeader.Format.Jpeg, 1024, 768), ImageHeader.read(jpeg(1024, 768)))
        assertEquals(ImageHeader.Info(ImageHeader.Format.WebP, 800, 600), ImageHeader.read(webpLossy(800, 600)))
        assertEquals(ImageHeader.Info(ImageHeader.Format.WebP, 123, 456), ImageHeader.read(webpLossless(123, 456)))
        assertEquals(ImageHeader.Info(ImageHeader.Format.WebP, 5000, 4000), ImageHeader.read(webpExtended(5000, 4000)))
    }

    @Test fun jpegSizeIsFoundAfterOtherSegments() {
        val withComment = byteArrayOf(-1, -40, -1, -2, 0, 5, 1, 2, 3) + byteArrayOf(-1, -62, 0, 11, 8, 0x07, 0xD0.toByte(), 0x0B, 0xB8.toByte(), 1, 1, 0x11, 0)
        assertEquals(ImageHeader.Info(ImageHeader.Format.Jpeg, 3000, 2000), ImageHeader.read(withComment))
    }

    @Test fun anythingElseIsNull() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'/>".encodeToByteArray()
        val others = listOf(
            ByteArray(0), byteArrayOf(1), byteArrayOf(1, 2, 3, 4, 5), svg, "<html></html>".encodeToByteArray(), "GIF".encodeToByteArray(),
            "BM".encodeToByteArray() + ByteArray(40), png(1, 1).copyOf(10), jpeg(10, 10).copyOf(5), riff("ABCD".encodeToByteArray() + le32(0)),
            webpLossy(10, 10).copyOf(20), byteArrayOf(-1, -40, -1),
        )
        for (b in others) assertNull(ImageHeader.read(b), b.take(12).toString())
    }

    @Test fun aZeroSizeIsNull() {
        assertNull(ImageHeader.read(png(0, 10)))
        assertNull(ImageHeader.read(gif(10, 0)))
        assertNull(ImageHeader.read(jpeg(0, 10)))
    }

    @Test fun hugeDeclaredSizesAreReadAsDeclared() {
        assertEquals(ImageHeader.Info(ImageHeader.Format.Png, Int.MAX_VALUE, Int.MAX_VALUE), ImageHeader.read(png(Int.MAX_VALUE, Int.MAX_VALUE)))
        assertEquals(65535L * 65535L, ImageHeader.read(jpeg(65535, 65535))!!.let { it.width.toLong() * it.height })
    }
}
