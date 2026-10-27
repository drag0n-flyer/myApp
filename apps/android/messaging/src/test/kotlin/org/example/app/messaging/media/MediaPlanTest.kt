package org.example.app.messaging.media

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaPlanTest {
    @Test fun targetSizeCapsLongestSideAndNeverEnlarges() {
        assertEquals(1600 to 1200, MediaPlan.targetSize(4000, 3000))
        assertEquals(1200 to 1600, MediaPlan.targetSize(3000, 4000))
        assertEquals(800 to 600, MediaPlan.targetSize(800, 600))
        assertEquals(1 to 1600, MediaPlan.targetSize(1, 100_000))
    }

    @Test fun constantsAreTheBriefsValues() {
        assertEquals(1600, MediaPlan.PHOTO_MAX_SIDE)
        assertEquals(70, MediaPlan.PHOTO_QUALITY)
        assertEquals(32, MediaPlan.THUMBNAIL_SIDE)
        assertEquals(1536, MediaPlan.THUMBNAIL_MAX_BYTES)
        assertEquals(listOf(40, 30, 20, 10), MediaPlan.thumbnailQualities())
        assertEquals(16_000, MediaPlan.Voice.SAMPLE_RATE)
        assertEquals(16_000, MediaPlan.Voice.BIT_RATE)
        assertEquals(1, MediaPlan.Voice.CHANNELS)
    }
}
