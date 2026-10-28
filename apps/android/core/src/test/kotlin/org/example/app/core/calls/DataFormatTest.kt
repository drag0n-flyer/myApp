package org.example.app.core.calls

import kotlin.test.Test
import kotlin.test.assertEquals

/** `:core` returns amounts and units; the app turns them into words. */
class DataFormatTest {
    @Test fun sizes() {
        assertEquals(DataSize(DataUnit.KB, 240.0), DataFormat.size(240_000))
        assertEquals(DataSize(DataUnit.MB, 1.2), DataFormat.size(1_200_000))
        assertEquals(DataSize(DataUnit.KB, 1.0), DataFormat.size(999))
        assertEquals(DataSize(DataUnit.KB, 640.0), DataFormat.size(640_000))
        assertEquals(DataSize(DataUnit.KB, 0.0), DataFormat.size(0))
    }
}
