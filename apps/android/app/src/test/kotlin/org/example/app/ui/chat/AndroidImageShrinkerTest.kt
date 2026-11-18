package org.example.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidImageShrinkerTest {
    private fun picture(w: Int, h: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (i in 0 until w step 7) { paint.color = Color.rgb(i % 255, (i * 3) % 255, (i * 7) % 255); canvas.drawRect(i.toFloat(), 0f, (i + 4).toFloat(), h.toFloat(), paint) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun aPictureBecomesAJpegOfAtMost8KiB() {
        val jpeg = assertNotNull(AndroidImageShrinker().toJpeg(picture(1200, 800)))
        assertTrue(jpeg.size in 2..8_192, "size ${jpeg.size}")
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
    }
}
