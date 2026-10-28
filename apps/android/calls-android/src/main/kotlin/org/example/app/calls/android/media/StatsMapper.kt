package org.example.app.calls.android.media

import org.example.app.calls.policy.AudioProfile
import org.example.app.calls.policy.IceState
import org.example.app.calls.policy.MediaStats

/** Transport totals for one call, from the WebRTC `transport` stats. */
data class TransportStats(
    val bytesSent: Long,
    val bytesReceived: Long,
    val packetsSent: Long,
    val packetsReceived: Long,
)

/** One entry of a WebRTC stats report, flattened to numbers and strings (as `RawStat` on iPhone). */
data class RawStat(
    val id: String,
    val type: String,
    val numbers: Map<String, Double> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
)

/** Data used by a call, and the flattening of WebRTC stats, as on iPhone (design §8.2). */
object StatsMapper {
    /** Bytes of IPv4 and UDP headers added to each packet. */
    const val HEADER_BYTES_PER_PACKET = 28L

    fun bytesUsed(stats: TransportStats): Long =
        stats.bytesSent + stats.bytesReceived +
            HEADER_BYTES_PER_PACKET * (stats.packetsSent + stats.packetsReceived)

    fun transport(stats: MediaStats): TransportStats =
        TransportStats(stats.bytesSent, stats.bytesReceived, stats.packetsSent, stats.packetsReceived)

    /** One `RTCStats` entry's members: numbers and booleans (as 0 or 1) to [RawStat.numbers], text to [RawStat.strings]. */
    fun flatten(id: String, type: String, members: Map<String, Any?>): RawStat {
        val numbers = HashMap<String, Double>()
        val strings = HashMap<String, String>()
        for ((k, v) in members) {
            when (v) {
                is Number -> numbers[k] = v.toDouble()
                is Boolean -> numbers[k] = if (v) 1.0 else 0.0
                is String -> strings[k] = v
                else -> Unit
            }
        }
        return RawStat(id, type, numbers, strings)
    }
}

/**
 * Turns a flattened stats report into [MediaStats]. Keeps the previous inbound counters so loss is since the
 * last sample. The same rules as the iPhone's `MediaStatsAggregator` (CallCore).
 */
class MediaStatsAggregator {
    private var lastReceived = 0.0
    private var lastLost = 0.0

    fun aggregate(stats: List<RawStat>): MediaStats {
        val byId = stats.associateBy { it.id }
        val transport = stats.firstOrNull { it.type == "transport" }

        // Selected pair: transport.selectedCandidatePairId, else a nominated, succeeded pair.
        val pair = transport?.strings?.get("selectedCandidatePairId")?.let { byId[it] }
            ?: stats.firstOrNull { it.type == "candidate-pair" && it.numbers["nominated"] == 1.0 && it.strings["state"] == "succeeded" }

        var out = MediaStats(
            availableOutgoingBitrate = pair?.numbers?.get("availableOutgoingBitrate"),
            roundTripTime = pair?.numbers?.get("currentRoundTripTime"),
            usingRelay = pair?.strings?.get("localCandidateId")?.let { byId[it] }?.strings?.get("candidateType") == "relay",
        )

        var received = 0.0
        var lost = 0.0
        for (s in stats) {
            val kind = s.strings["kind"] ?: s.strings["mediaType"]
            if (kind != "audio") continue
            val codec = s.strings["codecId"]?.let { byId[it] }?.strings?.get("mimeType")
            when (s.type) {
                "inbound-rtp" -> {
                    received += s.numbers["packetsReceived"] ?: 0.0
                    lost += s.numbers["packetsLost"] ?: 0.0
                    out = out.copy(
                        jitter = s.numbers["jitter"],
                        packetsReceived = out.packetsReceived + (s.numbers["packetsReceived"] ?: 0.0).toLong(),
                        bytesReceived = out.bytesReceived + (s.numbers["bytesReceived"] ?: 0.0).toLong(),
                        audioCodec = codec ?: out.audioCodec,
                    )
                }
                "outbound-rtp" -> out = out.copy(
                    bytesSent = out.bytesSent + (s.numbers["bytesSent"] ?: 0.0).toLong(),
                    packetsSent = out.packetsSent + (s.numbers["packetsSent"] ?: 0.0).toLong(),
                    audioCodec = out.audioCodec ?: codec,
                )
            }
        }
        // Transport totals (the whole connection) win over the audio-only sums when present.
        if (transport != null) {
            out = out.copy(
                bytesSent = transport.numbers["bytesSent"]?.toLong() ?: out.bytesSent,
                bytesReceived = transport.numbers["bytesReceived"]?.toLong() ?: out.bytesReceived,
                packetsSent = transport.numbers["packetsSent"]?.toLong() ?: out.packetsSent,
                packetsReceived = transport.numbers["packetsReceived"]?.toLong() ?: out.packetsReceived,
            )
        }
        val dRecv = received - lastReceived
        val dLost = maxOf(0.0, lost - lastLost)
        if (dRecv >= 0 && dRecv + dLost > 0) out = out.copy(inboundLossFraction = dLost / (dRecv + dLost))
        lastReceived = received
        lastLost = lost
        return out
    }
}

/** A video codec as WebRTC lists it (`RtpCapabilities.CodecCapability`): its name and fmtp parameters. */
data class VideoCodec(val name: String, val parameters: Map<String, String>)

/**
 * Video codec preference (design §8.2): H.264 constrained baseline first, for iPhone's VideoToolbox, then other
 * H.264, then VP8 (the software fallback both sides have), then anything else, with the repair formats last.
 */
object VideoCodecOrder {
    private val repair = setOf("rtx", "red", "ulpfec", "flexfec-03")

    fun <T> order(codecs: List<T>, codec: (T) -> VideoCodec): List<T> = codecs.sortedBy { rank(codec(it)) }

    fun order(codecs: List<VideoCodec>): List<VideoCodec> = order(codecs) { it }

    private fun rank(c: VideoCodec): Int {
        val name = c.name.lowercase()
        return when {
            name == "h264" && isConstrainedBaseline(c) -> if (c.parameters["packetization-mode"] == "1") 0 else 1
            name == "h264" -> 2
            name == "vp8" -> 3
            name in repair -> 5
            else -> 4
        }
    }

    /** profile-level-id `42e0xx` (constrained baseline) or `4200xx`/`4240xx`-style baseline with the constraint bit. */
    private fun isConstrainedBaseline(c: VideoCodec): Boolean {
        val id = c.parameters["profile-level-id"]?.lowercase() ?: return false
        if (id.length != 6 || !id.startsWith("42")) return false
        val constraints = id.substring(2, 4).toIntOrNull(16) ?: return false
        return constraints and 0x40 != 0 // constraint_set1_flag: constrained baseline
    }
}

/** WebRTC-free rules of [WebRtcMediaSession], kept here so they run in JVM tests (the iPhone's `MediaLogic`). */
object MediaLogic {
    /** Maps `PeerConnection.IceConnectionState.name`; null for a state this build does not know. */
    fun iceState(name: String): IceState? = when (name) {
        "NEW" -> IceState.New
        "CHECKING" -> IceState.Checking
        "CONNECTED" -> IceState.Connected
        "COMPLETED" -> IceState.Completed
        "FAILED" -> IceState.Failed
        "DISCONNECTED" -> IceState.Disconnected
        "CLOSED" -> IceState.Closed
        else -> null
    }

    /** Opus bitrate cap for the RTP sender: at most the send profile's `maxAverageBitrate`, once it is known. */
    fun senderBitrateCap(requested: Int, profile: AudioProfile?): Int =
        maxOf(0, profile?.let { minOf(requested, it.maxAverageBitrate) } ?: requested)
}

/**
 * Decides when the peer's video starts or stops, from the inbound video frame counter sampled about once a
 * second. A sender that turns video off sends no frames, so the counter stops growing.
 */
class RemoteVideoDetector(private val stallLimit: Int = 2) {
    var active = false
        private set
    private var last = 0.0
    private var stalls = 0

    /** The new state when it changed, null otherwise. */
    fun sample(frames: Double?): Boolean? {
        if (frames != null && frames > last) {
            last = frames
            stalls = 0
            if (!active) {
                active = true
                return true
            }
            return null
        }
        if (frames != null && frames < last) last = frames // the counter reset after a restart
        stalls++
        if (active && stalls >= stallLimit) {
            active = false
            return false
        }
        return null
    }
}
