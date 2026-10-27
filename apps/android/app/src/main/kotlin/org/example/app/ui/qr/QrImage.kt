package org.example.app.ui.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A square picture of a QR code as ARGB pixels, for tests and sharing. */
class QrPixels(val size: Int, val pixels: IntArray)

/** ZXing encoding and decoding; the only place the app touches the QR library. */
object QrCodes {
    private const val QUIET_ZONE_MODULES = 4

    fun matrix(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to QUIET_ZONE_MODULES),
    )

    /** Black modules on white, [scale] pixels per module. */
    fun render(text: String, scale: Int = 6): QrPixels {
        val m = matrix(text)
        val size = m.width * scale
        val pixels = IntArray(size * size) { i ->
            if (m[(i % size) / scale, (i / size) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return QrPixels(size, pixels)
    }

    fun decode(pixels: IntArray, width: Int, height: Int): String? =
        decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))))

    /** Decodes a camera frame's brightness plane (one byte per pixel, no row padding). */
    fun decodeLuma(luma: ByteArray, width: Int, height: Int): String? =
        decode(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false))))

    private fun decode(bitmap: BinaryBitmap): String? =
        try { QRCodeReader().decode(bitmap).text } catch (_: ReaderException) { null }
}

/** Draws [text] as a QR code on a white tile, so it scans in dark theme too. */
@Composable
fun QrImage(text: String, description: String, modifier: Modifier = Modifier, size: Dp = 240.dp) {
    val matrix = remember(text) { QrCodes.matrix(text) }
    Canvas(
        modifier.size(size).aspectRatio(1f).semantics { contentDescription = description },
    ) {
        drawRect(Color.White)
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
