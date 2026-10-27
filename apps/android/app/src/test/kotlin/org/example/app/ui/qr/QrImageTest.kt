package org.example.app.ui.qr

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class QrImageTest {
    @get:Rule val rule = createComposeRule()

    private val url = "https://home.example.org/link/0123456789abcdef#7Q2K9ZpL3w.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    @Test
    fun encodedUrlDecodesBackUnchanged() {
        val image = QrCodes.render(url)
        assertEquals(url, QrCodes.decode(image.pixels, image.size, image.size))
    }

    @Test
    fun decodeReturnsNullWhenThereIsNoCode() {
        assertNull(QrCodes.decode(IntArray(100 * 100) { 0xFFFFFF }, 100, 100))
    }

    @Test
    fun cameraLumaFramesDecode() {
        val image = QrCodes.render(url)
        val luma = ByteArray(image.pixels.size) { (image.pixels[it] and 0xFF).toByte() }
        assertEquals(url, QrCodes.decodeLuma(luma, image.size, image.size))
    }

    @Test
    fun imageHasTalkBackDescription() {
        rule.setContent { AppTheme { QrImage(url, "Linking QR code") } }
        rule.onNodeWithContentDescription("Linking QR code").assertExists()
    }
}
