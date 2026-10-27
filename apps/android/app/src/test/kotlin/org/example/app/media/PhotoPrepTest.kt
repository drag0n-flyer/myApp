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
}
