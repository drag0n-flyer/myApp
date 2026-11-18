package org.example.app.profile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import org.example.app.core.people.ProfilePhotoRule

/** A drawn picture for sample data: a blue-to-green diagonal with a pale circle. A real JPEG that passes the photo rule. */
object SamplePhoto {
    fun make(): ByteArray? = try {
        val side = ProfilePhotoRule.SIDE_PIXELS
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until side) {
            paint.color = Color.rgb(30, 90 + i * 100 / side, 200 - i * 90 / side)
            canvas.drawRect(0f, i.toFloat(), side.toFloat(), i + 1f, paint)
        }
        paint.color = Color.argb(200, 255, 255, 255)
        canvas.drawCircle(side / 2f, side * 0.4f, side * 0.18f, paint)
        canvas.drawCircle(side / 2f, side * 0.95f, side * 0.34f, paint)
        ProfilePhotoRule.fit { q -> ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray() }
    } catch (e: Exception) {
        null
    }
}
