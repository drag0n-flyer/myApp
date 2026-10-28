package org.example.app.calls.android.media

import kotlin.test.Test
import kotlin.test.assertEquals

class StatsMapperTest {
    @Test
    fun bytesUsedAddsHeaders() {
        val stats = TransportStats(bytesSent = 1_000, bytesReceived = 2_000, packetsSent = 10, packetsReceived = 20)
        assertEquals(1_000L + 2_000L + 28L * (10L + 20L), StatsMapper.bytesUsed(stats))
    }

    @Test
    fun bytesUsedOfNoTrafficIsZero() {
        assertEquals(0L, StatsMapper.bytesUsed(TransportStats(0, 0, 0, 0)))
    }

    // TODO(E5 part 2): codecOrderPrefersH264Baseline, once the WebRTC AAR (E2) is on main.
}
