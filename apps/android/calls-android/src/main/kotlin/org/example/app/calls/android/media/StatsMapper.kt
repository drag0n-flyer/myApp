package org.example.app.calls.android.media

/**
 * Transport totals for one call. The WebRTC `transport` stats (or the selected candidate pair)
 * map into this in E5 part 2.
 */
data class TransportStats(
    val bytesSent: Long,
    val bytesReceived: Long,
    val packetsSent: Long,
    val packetsReceived: Long,
)

/** Data used by a call, as on iPhone (design §8.2). */
object StatsMapper {
    /** Bytes of IPv4 and UDP headers added to each packet. */
    const val HEADER_BYTES_PER_PACKET = 28L

    fun bytesUsed(stats: TransportStats): Long =
        stats.bytesSent + stats.bytesReceived +
            HEADER_BYTES_PER_PACKET * (stats.packetsSent + stats.packetsReceived)
}
