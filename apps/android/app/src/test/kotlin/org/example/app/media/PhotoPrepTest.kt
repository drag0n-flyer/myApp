package org.example.app.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoPrepTest {
    private fun source(w: Int, h: Int): File {
        val dir = File.createTempFile("photoprep", "").also { it.delete(); it.mkdirs() }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.rgb(200, 30, 90)) }
        return File(dir, "in.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun thumbnailUnder1536Bytes() {
        val src = source(3000, 2000)
        val out = File(src.parentFile, "out")
        val p = PhotoPrep(ApplicationProvider.getApplicationContext<Application>(), out).prepare(src.toUri())
        assertTrue(p.thumbnail.isNotEmpty())
        assertTrue(p.thumbnail.size <= 1536, "thumbnail is ${p.thumbnail.size} bytes")
        assertTrue(p.file.length() > 0)
        assertEquals(listOf(p.file.name), out.list()!!.toList())
        src.parentFile.deleteRecursively()
    }

    @Test fun mainImageLongestSideIsCapped() {
        val src = source(3200, 1600)
        val p = PhotoPrep(ApplicationProvider.getApplicationContext<Application>(), File(src.parentFile, "out")).prepare(src.toUri())
        assertEquals(1600, p.width)
        assertEquals(800, p.height)
        src.parentFile.deleteRecursively()
    }

    /** A JPEG of w x h, painted so the left half is red and the right half blue, with the given EXIF tags. */
    private fun jpeg(w: Int, h: Int, tags: Map<String, String>): File {
        val dir = File.createTempFile("photoprep", "").also { it.delete(); it.mkdirs() }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.rgb(10, 120, 200)) }
        val f = File(dir, "in.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        android.media.ExifInterface(f.absolutePath).apply { tags.forEach { (k, v) -> setAttribute(k, v) }; saveAttributes() }
        return f
    }

    private fun bounds(f: File) = android.graphics.BitmapFactory.Options().also {
        it.inJustDecodeBounds = true
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, it)
    }

    @Test fun exifRotate90SwapsWidthAndHeight() {
        val src = jpeg(400, 200, mapOf(android.media.ExifInterface.TAG_ORIENTATION to android.media.ExifInterface.ORIENTATION_ROTATE_90.toString()))
        val p = PhotoPrep(ApplicationProvider.getApplicationContext<Application>(), File(src.parentFile, "out")).prepare(src.toUri())
        assertEquals(200, p.width)
        assertEquals(400, p.height)
        val b = bounds(p.file)
        assertEquals(200, b.outWidth)
        assertEquals(400, b.outHeight)
        src.parentFile.deleteRecursively()
    }

    @Test fun allEightOrientationsKeepOrSwapTheSides() {
        val swapped = setOf(5, 6, 7, 8)
        for (o in 1..8) {
            val src = jpeg(300, 100, mapOf(android.media.ExifInterface.TAG_ORIENTATION to o.toString()))
            val p = PhotoPrep(ApplicationProvider.getApplicationContext<Application>(), File(src.parentFile, "out")).prepare(src.toUri())
            assertEquals(if (o in swapped) 100 to 300 else 300 to 100, p.width to p.height, "orientation $o")
            src.parentFile.deleteRecursively()
        }
    }

    @Test fun outputHasNoExifOrGpsAndCorruptExifDoesNotCrash() {
        val src = jpeg(
            300, 200,
            mapOf(
                android.media.ExifInterface.TAG_GPS_LATITUDE to "51/1,30/1,0/1",
                android.media.ExifInterface.TAG_GPS_LATITUDE_REF to "N",
                android.media.ExifInterface.TAG_MAKE to "SecretCameraMaker",
            ),
        )
        val before = src.readBytes()
        assertTrue(String(before, Charsets.ISO_8859_1).contains("Exif"), "the source carries metadata")
        val p = PhotoPrep(ApplicationProvider.getApplicationContext<Application>(), File(src.parentFile, "out")).prepare(src.toUri())
        val text = String(p.file.readBytes(), Charsets.ISO_8859_1)
        assertTrue(!text.contains("Exif"), "no Exif marker")
        assertTrue(!text.contains("SecretCameraMaker"), "no camera data")
        assertTrue(!String(p.thumbnail, Charsets.ISO_8859_1).contains("Exif"))
        src.parentFile.deleteRecursively()
    }
}
