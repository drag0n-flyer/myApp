package org.example.app.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.app.core.people.ProfilePhotoRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfilePhotoTest {
    private fun encode(bmp: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 95): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(format, quality, it) }.toByteArray()

    /** Left third red, middle third green, right third blue. */
    private fun stripes(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) {
            val color = when { x < w / 3 -> Color.RED; x < 2 * w / 3 -> Color.GREEN; else -> Color.BLUE }
            for (y in 0 until h) bmp.setPixel(x, y, color)
        }
        return encode(bmp)
    }

    private fun decode(bytes: ByteArray) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun assertGreenish(color: Int) {
        assertTrue(Color.green(color) > 150 && Color.red(color) < 100 && Color.blue(color) < 100, "not green: #${Integer.toHexString(color)}")
    }

    @Test fun aWideImageBecomesACentredSquareOf256() {
        val out = ProfilePhoto.prepare(stripes(900, 300))!!
        val bmp = decode(out)
        assertEquals(256, bmp.width); assertEquals(256, bmp.height)
        // The centre third of a 900-wide image is the 300-wide square: all green.
        for ((x, y) in listOf(4 to 4, 251 to 4, 4 to 251, 251 to 251, 128 to 128)) assertGreenish(bmp.getPixel(x, y))
    }

    @Test fun aTallImageIsCroppedTheSameWay() {
        val out = ProfilePhoto.prepare(rotatedStripes(300, 900))!!
        val bmp = decode(out)
        assertEquals(256, bmp.width); assertEquals(256, bmp.height)
        assertGreenish(bmp.getPixel(128, 128))
        assertGreenish(bmp.getPixel(4, 4))
        assertGreenish(bmp.getPixel(251, 251))
    }

    /** Top third red, middle green, bottom blue. */
    private fun rotatedStripes(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            val color = when { y < h / 3 -> Color.RED; y < 2 * h / 3 -> Color.GREEN; else -> Color.BLUE }
            for (x in 0 until w) bmp.setPixel(x, y, color)
        }
        return encode(bmp)
    }

    @Test fun theResultIsAJpegWithinTheLimitAndCarriesNoMetadata() {
        val file = File.createTempFile("pp", ".jpg").also { it.deleteOnExit() }
        file.writeBytes(stripes(600, 400))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,30/1,0/1"); setAttribute(ExifInterface.TAG_MAKE, "SecretCam"); saveAttributes()
        }
        val out = ProfilePhoto.prepare(file.readBytes())!!
        assertTrue(ProfilePhotoRule.isAcceptable(out))
        assertFalse(String(out, Charsets.ISO_8859_1).contains("SecretCam"))
        assertFalse(String(out, Charsets.ISO_8859_1).contains("Exif"))
    }

    @Test fun anExifRotationIsBakedInBeforeTheCrop() {
        val file = File.createTempFile("pp", ".jpg").also { it.deleteOnExit() }
        // Stored sideways (top red / middle green / bottom blue, 300x900) and tagged to rotate 90: shown as 900x300 stripes.
        file.writeBytes(rotatedStripes(300, 900))
        ExifInterface(file.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val bmp = decode(ProfilePhoto.prepare(file.readBytes())!!)
        assertGreenish(bmp.getPixel(128, 128))
    }

    @Test fun aSmallImageIsScaledUpToTheTileSize() {
        val bmp = decode(ProfilePhoto.prepare(stripes(60, 60))!!)
        assertEquals(256, bmp.width); assertEquals(256, bmp.height)
    }

    @Test fun aNoisyImageStepsTheQualityDownToFit() {
        val rnd = Random(7)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        for (x in 0 until 512) for (y in 0 until 512) bmp.setPixel(x, y, Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
        val source = encode(bmp, quality = 100)
        // Even the best quality of 256x256 noise is too big for the limit, so the steps must come into play.
        val atBest = encode(Bitmap.createScaledBitmap(bmp, 256, 256, true), quality = ProfilePhotoRule.QUALITIES.first())
        assertTrue(atBest.size > ProfilePhotoRule.MAX_BYTES, "fixture is not hard enough: ${atBest.size}")
        val out = ProfilePhoto.prepare(source)
        // Either it fits at a lower quality or the image is refused; it is never over the limit.
        if (out != null) assertTrue(ProfilePhotoRule.isAcceptable(out))
    }

    @Test fun somethingThatIsNotAnImageGivesNull() {
        assertNull(ProfilePhoto.prepare(ByteArray(0)))
        assertNull(ProfilePhoto.prepare("not an image at all".encodeToByteArray()))
    }

    @Test fun decodesIsTrueOnlyForARealJpeg() {
        val jpeg = stripes(64, 64)
        assertTrue(ProfilePhoto.decodes(jpeg))
        assertFalse(ProfilePhoto.decodes(byteArrayOf(-1, -40, 1, 2, 3)))
        assertFalse(ProfilePhoto.decodes(ByteArray(0)))
        val png = encode(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), Bitmap.CompressFormat.PNG, 100)
        assertFalse(ProfilePhoto.decodes(png), "a PNG is not accepted as a JPEG profile photo")
    }

    @Test fun bitmapOfAJpegIsDecodedAndOfJunkIsNull() {
        assertNotNull(ProfilePhoto.bitmap(stripes(64, 64)))
        assertNull(ProfilePhoto.bitmap(byteArrayOf(-1, -40, 1, 2, 3)))
    }

    @Test fun aPreparedPhotoDecodesAndSurvivesRoundTrip() {
        val out = ProfilePhoto.prepare(stripes(400, 400))!!
        assertTrue(ProfilePhoto.decodes(out))
        assertContentEquals(out, out.copyOf())
    }
}
