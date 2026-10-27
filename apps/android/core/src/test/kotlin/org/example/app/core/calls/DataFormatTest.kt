package org.example.app.core.calls

import kotlin.test.Test
import kotlin.test.assertEquals

class DataFormatTest {
    @Test fun sizes() {
        assertEquals("240 KB", DataFormat.size(240_000))
        assertEquals("1.2 MB", DataFormat.size(1_200_000))
        assertEquals("1 KB", DataFormat.size(999))
        assertEquals("≈ 1.2 MB used", DataFormat.used(1_200_000))
        assertEquals("≈ 640 KB used", DataFormat.used(640_000))
        assertEquals("≈ 0 KB used", DataFormat.used(0))
    }
}
