package org.example.app.core.people

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilePhotoRuleTest {
    /** A JPEG start with a 256x256 baseline frame header, padded with zeros to [size] bytes (at least 15). */
    private fun jpeg(size: Int, side: Int = 256): ByteArray {
        val head = byteArrayOf(
            -1, -40, -1, -64, 0, 11, 8, (side shr 8).toByte(), side.toByte(), (side shr 8).toByte(), side.toByte(), 1, 1, 0x11, 0,
        )
        return head.copyOf(maxOf(size, head.size))
    }

    @Test fun acceptableIsAJpegWithAFrameHeaderWithinTheLimit() {
        assertTrue(ProfilePhotoRule.isAcceptable(jpeg(15)))
        assertTrue(ProfilePhotoRule.isAcceptable(jpeg(24_576)))
        assertFalse(ProfilePhotoRule.isAcceptable(jpeg(24_577)))
        assertFalse(ProfilePhotoRule.isAcceptable(ByteArray(100)))
        assertFalse(ProfilePhotoRule.isAcceptable(byteArrayOf(0xFF.toByte())))
        assertFalse(ProfilePhotoRule.isAcceptable(null))
        assertTrue(ProfilePhotoRule.isAcceptable(jpeg(100, side = 1024)))
        assertFalse(ProfilePhotoRule.isAcceptable(jpeg(100, side = 1025)))
    }

    @Test fun fitStepsTheQualityDownUntilItFits() {
        val tried = mutableListOf<Int>()
        val result = ProfilePhotoRule.fit { q -> tried += q; jpeg(if (q > 60) 30_000 else 20_000) }
        assertEquals(listOf(90, 80, 70, 60), tried)
        assertEquals(20_000, result!!.size)
    }

    @Test fun fitGivesNullWhenNothingFitsOrEncodingFails() {
        assertNull(ProfilePhotoRule.fit { jpeg(30_000) })
        assertNull(ProfilePhotoRule.fit { null })
    }

    @Test fun fitKeepsTheFirstWhenItAlreadyFits() {
        val first = jpeg(100)
        assertContentEquals(first, ProfilePhotoRule.fit { first })
    }

    @Test fun cropIsTheShorterSide() {
        assertEquals(300, ProfilePhotoRule.cropSide(300, 900))
        assertEquals(300, ProfilePhotoRule.cropSide(900, 300))
    }
}
