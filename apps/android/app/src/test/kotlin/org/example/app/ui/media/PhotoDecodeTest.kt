package org.example.app.ui.media

import android.graphics.Bitmap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoDecodeTest {
    private val dir = java.nio.file.Files.createTempDirectory("decode").toFile()

    private fun png(width: Int, height: Int): File {
        val file = File(dir, "p$width-$height.png")
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    @Test fun aLargePhotoIsDecodedNearTheBoxNotAtFullSize() {
        val bmp = assertNotNull(PhotoDecoder.decodeFit(png(2000, 1500), 500, 500))
        // Fit: 500 x 375 is displayed; the decode is the largest power-of-two sample that is not smaller.
        assertEquals(500, bmp.width)
        assertEquals(375, bmp.height)
    }

    @Test fun aSmallPhotoIsNotEnlargedOrSampled() {
        val bmp = assertNotNull(PhotoDecoder.decodeFit(png(100, 80), 500, 500))
        assertEquals(100, bmp.width)
    }

    @Test fun oversamplingKeepsMoreDetail() {
        val bmp = assertNotNull(PhotoDecoder.decodeFit(png(2000, 1500), 500, 500, oversample = 2))
        assertEquals(1000, bmp.width)
    }

    @Test fun theDecodedPixelCountIsBounded() {
        // A box far bigger than the picture would otherwise ask for full size; the cap stops a 40-megapixel decode.
        val bmp = assertNotNull(PhotoDecoder.decodeFit(png(2000, 1500), 100_000, 100_000, maxPixels = 1_000_000))
        assertTrue(bmp.width.toLong() * bmp.height <= 1_000_000, "${bmp.width}x${bmp.height}")
    }

    @Test fun aMissingEmptyOrBrokenFileIsNull() {
        assertNull(PhotoDecoder.decodeFit(File(dir, "missing.png"), 500, 500))
        assertNull(PhotoDecoder.decodeFit(File(dir, "empty.png").also { it.writeBytes(ByteArray(0)) }, 500, 500))
        assertNull(PhotoDecoder.decodeFit(File(dir, "junk.png").also { it.writeBytes(ByteArray(2000) { 7 }) }, 500, 500))
    }

    @Test fun aCutOffFileNeverThrows() {
        // The platform may give nothing or what it could read (grey for the rest); either is fine, a crash is not.
        val cut = File(dir, "cut.png").also { it.writeBytes(png(2000, 1500).readBytes().copyOf(40)) }
        PhotoDecoder.decodeFit(cut, 500, 500)
    }

    @Test fun aBoxWithNoSizeIsNull() {
        assertNull(PhotoDecoder.decodeFit(png(100, 80), 0, 500))
    }
}
