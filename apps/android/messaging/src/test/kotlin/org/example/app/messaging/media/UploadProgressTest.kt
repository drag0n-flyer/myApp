package org.example.app.messaging.media

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class UploadProgressTest {
    @Test fun reportsTheFractionReadSoFar() {
        val seen = mutableListOf<Double>()
        val stream = ProgressInputStream(ByteArrayInputStream(ByteArray(10)), 10) { seen += it }
        stream.readNBytes(5)
        assertEquals(0.5, seen.last())
        stream.readAllBytes()
        assertEquals(1.0, seen.last())
    }

    @Test fun anUnknownOrZeroLengthNeverDividesByZero() {
        val seen = mutableListOf<Double>()
        ProgressInputStream(ByteArrayInputStream(ByteArray(3)), 0) { seen += it }.readAllBytes()
        assertEquals(true, seen.all { it in 0.0..1.0 })
    }
}
