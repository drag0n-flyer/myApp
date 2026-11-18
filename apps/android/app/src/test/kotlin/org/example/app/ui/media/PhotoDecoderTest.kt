package org.example.app.ui.media

import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoDecoderTest {
    @Test fun aLargePhotoIsSampledDownToTheTarget() = assertEquals(4, PhotoDecoder.sampleSize(4000, 3000, 1000, 750))

    @Test fun aSmallPhotoIsNotSampled() = assertEquals(1, PhotoDecoder.sampleSize(800, 600, 1000, 750))

    @Test fun theResultIsNeverSmallerThanTheTarget() {
        // 4000 / 8 = 500 < 1000, so 4.
        assertEquals(4, PhotoDecoder.sampleSize(4000, 3000, 1000, 100))
        // The short side limits: 3000 / 2 = 1500 >= 1400, 3000 / 4 = 750 < 1400.
        assertEquals(2, PhotoDecoder.sampleSize(4000, 3000, 100, 1400))
    }

    @Test fun anExactPowerOfTwoFitIsUsed() = assertEquals(2, PhotoDecoder.sampleSize(2000, 1500, 1000, 750))

    @Test fun aFortyMegapixelPhotoForAThumbnailIsSampledHard() = assertEquals(16, PhotoDecoder.sampleSize(8000, 5000, 500, 300))

    @Test fun zeroOrNegativeSizesGiveOne() {
        assertEquals(1, PhotoDecoder.sampleSize(0, 3000, 1000, 750))
        assertEquals(1, PhotoDecoder.sampleSize(4000, 0, 1000, 750))
        assertEquals(1, PhotoDecoder.sampleSize(-4000, 3000, 1000, 750))
        assertEquals(1, PhotoDecoder.sampleSize(4000, 3000, 0, 750))
        assertEquals(1, PhotoDecoder.sampleSize(4000, 3000, 1000, -5))
        assertEquals(1, PhotoDecoder.sampleSize(0, 0, 0, 0))
    }
}
