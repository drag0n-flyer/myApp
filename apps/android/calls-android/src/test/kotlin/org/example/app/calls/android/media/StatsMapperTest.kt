package org.example.app.calls.android.media

import kotlin.test.Test
import org.example.app.calls.policy.AudioProfile
import org.example.app.calls.policy.IceState
import org.example.app.contracts.DataMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun transportTotalsFeedBytesUsed() {
        val report = listOf(
            RawStat("T", "transport", numbers = mapOf("bytesSent" to 1000.0, "bytesReceived" to 2000.0, "packetsSent" to 10.0, "packetsReceived" to 20.0)),
            RawStat("O", "outbound-rtp", numbers = mapOf("bytesSent" to 400.0, "packetsSent" to 4.0), strings = mapOf("kind" to "audio")),
        )
        val s = MediaStatsAggregator().aggregate(report)
        assertEquals(1000L, s.bytesSent)
        assertEquals(2000L, s.bytesReceived)
        assertEquals(10L, s.packetsSent)
        assertEquals(20L, s.packetsReceived)
        assertEquals(1000L + 2000L + 28L * 30L, StatsMapper.bytesUsed(StatsMapper.transport(s)))
    }

    @Test
    fun aggregatesSelectedPairCodecAndLoss() {
        val agg = MediaStatsAggregator()
        val base = listOf(
            RawStat("T", "transport", strings = mapOf("selectedCandidatePairId" to "P")),
            RawStat("P", "candidate-pair", numbers = mapOf("availableOutgoingBitrate" to 30000.0, "currentRoundTripTime" to 0.05), strings = mapOf("localCandidateId" to "L")),
            RawStat("L", "local-candidate", strings = mapOf("candidateType" to "relay")),
            RawStat("C", "codec", strings = mapOf("mimeType" to "audio/opus")),
            RawStat("I", "inbound-rtp", numbers = mapOf("packetsReceived" to 90.0, "packetsLost" to 10.0, "jitter" to 0.01, "bytesReceived" to 900.0), strings = mapOf("kind" to "audio", "codecId" to "C")),
            RawStat("O", "outbound-rtp", numbers = mapOf("bytesSent" to 500.0, "packetsSent" to 50.0), strings = mapOf("kind" to "audio")),
        )
        val s = agg.aggregate(base)
        assertEquals(30000.0, s.availableOutgoingBitrate)
        assertEquals(0.05, s.roundTripTime)
        assertTrue(s.usingRelay)
        assertEquals("audio/opus", s.audioCodec)
        assertEquals(500L, s.bytesSent)
        assertEquals(900L, s.bytesReceived)
        assertEquals(0.01, s.jitter)
        assertEquals(0.1, s.inboundLossFraction, 1e-9)
        // Second sample: 100 more received, no new loss.
        val next = base.map { if (it.id == "I") it.copy(numbers = it.numbers + ("packetsReceived" to 190.0)) else it }
        assertEquals(0.0, agg.aggregate(next).inboundLossFraction, 1e-9)
    }

    @Test
    fun lossFractionIsSinceTheLastSample() {
        val agg = MediaStatsAggregator()
        fun inbound(received: Double, lost: Double) =
            listOf(RawStat("I", "inbound-rtp", numbers = mapOf("packetsReceived" to received, "packetsLost" to lost), strings = mapOf("kind" to "audio")))
        assertEquals(0.0, agg.aggregate(inbound(100.0, 0.0)).inboundLossFraction, 1e-9)
        assertEquals(0.1, agg.aggregate(inbound(190.0, 10.0)).inboundLossFraction, 1e-9)
    }

    @Test
    fun nominatedPairWithoutTransportSelection() {
        val report = listOf(
            RawStat("P1", "candidate-pair", numbers = mapOf("nominated" to 0.0), strings = mapOf("state" to "succeeded")),
            RawStat("P2", "candidate-pair", numbers = mapOf("nominated" to 1.0, "availableOutgoingBitrate" to 24000.0, "currentRoundTripTime" to 0.08), strings = mapOf("state" to "succeeded", "localCandidateId" to "L")),
            RawStat("L", "local-candidate", strings = mapOf("candidateType" to "host")),
        )
        val s = MediaStatsAggregator().aggregate(report)
        assertEquals(24000.0, s.availableOutgoingBitrate)
        assertEquals(0.08, s.roundTripTime)
        assertFalse(s.usingRelay)
    }

    @Test
    fun emptyReportIsZero() {
        val s = MediaStatsAggregator().aggregate(emptyList())
        assertNull(s.availableOutgoingBitrate)
        assertEquals(0L, StatsMapper.bytesUsed(StatsMapper.transport(s)))
    }

    @Test
    fun flattenSplitsNumbersAndStrings() {
        val raw = StatsMapper.flatten(
            "P", "candidate-pair",
            mapOf("nominated" to true, "bytesSent" to java.math.BigInteger.valueOf(12), "packetsSent" to 3L, "rtt" to 0.5, "state" to "succeeded", "list" to arrayOf("x")),
        )
        assertEquals(RawStat("P", "candidate-pair", mapOf("nominated" to 1.0, "bytesSent" to 12.0, "packetsSent" to 3.0, "rtt" to 0.5), mapOf("state" to "succeeded")), raw)
    }

    @Test
    fun codecOrderPrefersH264Baseline() {
        val vp8 = VideoCodec("VP8", emptyMap())
        val h264High = VideoCodec("H264", mapOf("profile-level-id" to "640c1f", "packetization-mode" to "1"))
        val h264Baseline = VideoCodec("H264", mapOf("profile-level-id" to "42e01f", "packetization-mode" to "1"))
        val h264BaselineMode0 = VideoCodec("H264", mapOf("profile-level-id" to "42e01f", "packetization-mode" to "0"))
        val rtx = VideoCodec("rtx", mapOf("apt" to "96"))
        val av1 = VideoCodec("AV1", emptyMap())
        val ordered = VideoCodecOrder.order(listOf(vp8, rtx, h264High, av1, h264BaselineMode0, h264Baseline))
        assertEquals(listOf(h264Baseline, h264BaselineMode0, h264High, vp8, av1, rtx), ordered)
    }

    @Test
    fun codecOrderKeepsVp8FirstWithoutH264() {
        val vp8 = VideoCodec("VP8", emptyMap())
        val vp9 = VideoCodec("VP9", emptyMap())
        assertEquals(listOf(vp8, vp9), VideoCodecOrder.order(listOf(vp9, vp8)))
    }

    @Test
    fun remoteVideoDetectorFollowsTheFrameCounter() {
        val d = RemoteVideoDetector()
        assertEquals(null, d.sample(null))
        assertEquals(true, d.sample(10.0))
        assertEquals(null, d.sample(20.0))
        assertEquals(null, d.sample(20.0)) // one stall is not enough
        assertEquals(false, d.sample(20.0))
        assertEquals(null, d.sample(20.0))
        assertEquals(true, d.sample(25.0))
    }

    @Test
    fun iceStateMapsEveryWebRtcState() {
        val names = listOf("NEW", "CHECKING", "CONNECTED", "COMPLETED", "FAILED", "DISCONNECTED", "CLOSED")
        val expected = listOf(IceState.New, IceState.Checking, IceState.Connected, IceState.Completed, IceState.Failed, IceState.Disconnected, IceState.Closed)
        assertEquals(expected, names.map { MediaLogic.iceState(it) })
        assertNull(MediaLogic.iceState("SOMETHING_NEW"))
    }

    @Test
    fun senderBitrateIsCappedByTheProfile() {
        val p = AudioProfile.forMode(DataMode.UltraLow)
        assertEquals(p.maxAverageBitrate, MediaLogic.senderBitrateCap(50_000, p))
        assertEquals(5_000, MediaLogic.senderBitrateCap(5_000, p))
        assertEquals(50_000, MediaLogic.senderBitrateCap(50_000, null))
    }

    /** The bandwidth estimator's ceiling is the mode's wire budget, so loss-driven padding and probes cannot exceed it. */
    @Test
    fun bweCapIsTheModeWireBudget() {
        assertEquals(21_000, MediaLogic.bweMaxBitrate(10_000))
        assertEquals(30_000, MediaLogic.bweMaxBitrate(16_000))
        assertEquals(51_000, MediaLogic.bweMaxBitrate(24_000))
        assertEquals(75_000, MediaLogic.bweMaxBitrate(40_000))
        for (mode in DataMode.entries) {
            val p = AudioProfile.forMode(mode)
            assertEquals(p.wireBudget, MediaLogic.bweMaxBitrate(p.maxAverageBitrate), "$mode")
        }
        assertEquals(21_000, MediaLogic.bweMaxBitrate(6_000)) // below the lowest mode
        assertEquals(30_000, MediaLogic.bweMaxBitrate(12_000)) // the lowest mode that covers it
        assertEquals(75_000, MediaLogic.bweMaxBitrate(64_000)) // above the highest mode
        assertEquals(171_000, MediaLogic.bweMaxBitrate(10_000, videoBitrate = 150_000))
    }
}
