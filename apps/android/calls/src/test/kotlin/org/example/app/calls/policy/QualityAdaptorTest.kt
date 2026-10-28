package org.example.app.calls.policy

import org.example.app.contracts.DataMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sample(avail: Double?, loss: Double = 0.0) = MediaStats(availableOutgoingBitrate = avail, inboundLossFraction = loss)

/** The session's estimator ceiling for the adaptor's current level (`MediaLogic.bweMaxBitrate`). */
private fun cap(a: QualityAdaptor): Double =
    (AudioProfile.forMode(a.currentMode).wireBudget + (a.currentTier?.let { VideoLadder.spec(it).maxBps } ?: 0)).toDouble()

/** Feeds [n] samples of the estimate WebRTC reports while the session caps it: it sits at the cap. */
private fun feedCapped(a: QualityAdaptor, n: Int, loss: Double = 0.0) {
    repeat(n) { val c = cap(a); a.step(MediaStats(availableOutgoingBitrate = c, inboundLossFraction = loss, bweCeiling = c)) }
}

class QualityAdaptorTest {
    @Test fun ladderBasicsAndCaps() {
        assertEquals(VideoTier.P360, VideoLadder.allowed(DataMode.High))
        assertEquals(VideoTier.P270, VideoLadder.allowed(DataMode.Balanced))
        assertEquals(VideoTier.P180, VideoLadder.allowed(DataMode.DataSaver))
        assertEquals(VideoTier.P180, VideoLadder.allowed(DataMode.UltraLow))
        // Caps: 100k, 200k, 300k.
        assertEquals(100_000, VideoLadder.spec(VideoTier.P180).maxBps)
        assertEquals(200_000, VideoLadder.spec(VideoTier.P270).maxBps)
        assertEquals(300_000, VideoLadder.spec(VideoTier.P360).maxBps)
        assertEquals(VideoTier.P360 to 300_000, VideoLadder.pick(300_000.0, VideoTier.P360))
        assertEquals(300_000, VideoLadder.pick(500_000.0, VideoTier.P360)?.second)
        assertEquals(VideoTier.P270, VideoLadder.pick(150_000.0, VideoTier.P360)?.first)
        assertEquals(VideoTier.P180, VideoLadder.pick(150_000.0, VideoTier.P180)?.first)
        assertNull(VideoLadder.pick(30_000.0, VideoTier.P360))
        assertNull(VideoLadder.pick(500_000.0, null))
        assertTrue(VideoLadder.startsOff(DataMode.High, onMobile = true, videoOffOnMobile = true))
        assertTrue(!VideoLadder.startsOff(DataMode.High, onMobile = false, videoOffOnMobile = true))
        assertTrue(!VideoLadder.startsOff(DataMode.High, onMobile = true, videoOffOnMobile = false))
        assertTrue(VideoLadder.startsOff(DataMode.DataSaver, onMobile = false, videoOffOnMobile = false))
        assertTrue(VideoLadder.startsOff(DataMode.UltraLow, onMobile = false, videoOffOnMobile = false))
    }

    @Test fun clampsTierToCeiling() {
        assertEquals(VideoTier.P270, VideoLadder.clampTier(VideoTier.P360, DataMode.Balanced))
        assertEquals(VideoTier.P180, VideoLadder.clampTier(VideoTier.P270, DataMode.UltraLow))
        assertEquals(VideoTier.P360, VideoLadder.effectiveTier(VideoTier.P360, DataMode.High))
        assertNull(VideoLadder.effectiveTier(null, DataMode.High))
    }

    @Test fun startsAtCeilingWithHeadroom() {
        val a = QualityAdaptor(DataMode.High, true)
        val o = a.step(sample(600_000.0))
        assertEquals(DataMode.High, o.mode); assertEquals(VideoTier.P360, o.video)
        assertEquals(CallQuality.Good, o.quality); assertNull(o.banner)
    }

    @Test fun degradesVideoThenAudio() {
        val a = QualityAdaptor(DataMode.High, true)
        a.step(sample(600_000.0))
        val o1 = a.step(sample(260_000.0))
        assertEquals(VideoTier.P270, o1.video); assertEquals(CallBanner.VideoLowered, o1.banner); assertEquals(DataMode.High, o1.mode)
        val o2 = a.step(sample(55_000.0))
        assertNull(o2.video); assertNull(o2.videoBitrate); assertEquals(CallBanner.AudioOnly, o2.banner); assertEquals(DataMode.DataSaver, o2.mode)
        val o3 = a.step(sample(20_000.0))
        assertEquals(DataMode.UltraLow, o3.mode); assertEquals(CallQuality.Weak, o3.quality); assertNull(o3.video)
    }

    @Test fun lossStepsDownAfterTwoSamples() {
        val a = QualityAdaptor(DataMode.High, false)
        assertEquals(DataMode.High, a.step(sample(600_000.0)).mode)
        assertEquals(DataMode.High, a.step(sample(600_000.0, 0.15)).mode)
        assertEquals(DataMode.Balanced, a.step(sample(600_000.0, 0.15)).mode)
    }

    @Test fun nonConsecutiveLossDoesNotStepDown() {
        val a = QualityAdaptor(DataMode.High, false)
        a.step(sample(600_000.0, 0.15)); a.step(sample(600_000.0, 0.0))
        assertEquals(DataMode.High, a.step(sample(600_000.0, 0.15)).mode)
    }

    @Test fun recoversAfterFiveGoodSamples() {
        val a = QualityAdaptor(DataMode.High, true)
        a.step(sample(600_000.0))
        assertEquals(DataMode.UltraLow, a.step(sample(20_000.0)).mode)
        a.step(sample(600_000.0))
        for (i in 1 until 4) assertEquals(DataMode.UltraLow, a.step(sample(600_000.0)).mode, "sample $i")
        var o = a.step(sample(600_000.0))
        assertEquals(DataMode.DataSaver, o.mode); assertNull(o.video)
        repeat(5) { o = a.step(sample(600_000.0)) }
        assertEquals(DataMode.Balanced, o.mode); assertNull(o.video)
        repeat(5) { o = a.step(sample(600_000.0)) }
        assertEquals(DataMode.High, o.mode); assertNull(o.video)
        repeat(5) { o = a.step(sample(600_000.0)) }
        assertEquals(DataMode.High, o.mode); assertEquals<VideoTier?>(VideoTier.P180, o.video)
    }

    @Test fun neverExceedsCeiling() {
        val rng = Random(42)
        val a = QualityAdaptor(DataMode.High, true)
        var ceiling = DataMode.High
        for (i in 0 until 2000) {
            if (i % 100 == 0) { ceiling = DataMode.entries.random(rng); a.setCeiling(ceiling) }
            val avail = if (rng.nextInt(10) == 0) null else rng.nextDouble(0.0, 800_000.0)
            val loss = if (rng.nextBoolean()) 0.0 else rng.nextDouble(0.0, 0.3)
            val o = a.step(sample(avail, loss))
            assertTrue(o.mode <= ceiling)
            o.video?.let { assertTrue(it <= VideoLadder.allowed(ceiling)) }
            assertEquals(o.video == null, o.videoBitrate == null)
        }
    }

    @Test fun videoNotWantedStaysOff() {
        val a = QualityAdaptor(DataMode.High, false)
        for (avail in listOf(600_000.0, 55_000.0, 20_000.0, 600_000.0)) {
            val o = a.step(sample(avail))
            assertNull(o.video); assertNotEquals(CallBanner.AudioOnly, o.banner)
        }
    }

    @Test fun raisingCeilingKeepsVideo() {
        val a = QualityAdaptor(DataMode.Balanced, true)
        assertEquals(VideoTier.P270, a.step(sample(600_000.0)).video)
        a.setCeiling(DataMode.High)
        val o = a.step(sample(600_000.0))
        assertNotNull(o.video); assertNotEquals(CallBanner.AudioOnly, o.banner); assertEquals(DataMode.High, o.mode)
    }

    @Test fun enablingVideoJumpsToBestFit() {
        val a = QualityAdaptor(DataMode.High, false)
        assertNull(a.step(sample(600_000.0)).video)
        a.setVideoWanted(true)
        val o = a.step(sample(600_000.0))
        assertEquals(VideoTier.P360, o.video); assertEquals(DataMode.High, o.mode)
        val b = QualityAdaptor(DataMode.High, false)
        b.step(sample(260_000.0))
        b.setVideoWanted(true)
        assertEquals(VideoTier.P270, b.step(sample(260_000.0)).video)
    }

    @Test fun nilAvailabilityHolds() {
        val a = QualityAdaptor(DataMode.High, false)
        a.step(sample(600_000.0))
        assertEquals(DataMode.UltraLow, a.step(sample(20_000.0)).mode)
        repeat(3) { a.step(sample(600_000.0)) }
        repeat(20) { assertEquals(DataMode.UltraLow, a.step(sample(null)).mode) }
        assertEquals(DataMode.UltraLow, a.step(sample(600_000.0)).mode)
        assertEquals(DataMode.DataSaver, a.step(sample(600_000.0)).mode)
    }

    @Test fun nilAvailabilityStillAppliesLoss() {
        val a = QualityAdaptor(DataMode.High, false)
        a.step(sample(null, 0.2))
        assertEquals(DataMode.Balanced, a.step(sample(null, 0.2)).mode)
    }

    @Test fun flappingAroundThresholdIsBounded() {
        val a = QualityAdaptor(DataMode.High, true)
        var last = a.step(sample(600_000.0))
        var changes = 0
        for (i in 0 until 200) {
            val o = a.step(sample(if (i % 2 == 0) 250_000.0 else 330_000.0))
            if (o.mode != last.mode || o.video != last.video) changes++
            last = o
        }
        assertTrue(changes <= 4, "changes=$changes")
    }

    // Capped estimate (the session caps the estimate at the level's wire budget)

    @Test fun capSaturationStepsBackUp() {
        val a = QualityAdaptor(DataMode.Balanced, videoWanted = false)
        a.step(sample(30_000.0))
        assertEquals(DataMode.UltraLow, a.currentMode)
        feedCapped(a, QualityAdaptor.GOOD_SAMPLES_TO_STEP_UP - 1)
        assertEquals(DataMode.UltraLow, a.currentMode)
        feedCapped(a, 1)
        assertEquals(DataMode.DataSaver, a.currentMode)
        feedCapped(a, QualityAdaptor.GOOD_SAMPLES_TO_STEP_UP)
        assertEquals(DataMode.Balanced, a.currentMode)
    }

    @Test fun capSaturationTurnsVideoBackOnAtTheLowestTier() {
        val a = QualityAdaptor(DataMode.High, videoWanted = true)
        a.step(sample(50_000.0))
        assertNull(a.currentTier)
        repeat(20) { if (a.currentTier == null) feedCapped(a, 1) }
        assertEquals(DataMode.High, a.currentMode)
        assertEquals(VideoTier.P180, a.currentTier)
    }

    @Test fun capSaturationWithLossDoesNotStepUp() {
        val a = QualityAdaptor(DataMode.Balanced, videoWanted = false)
        a.step(sample(30_000.0))
        feedCapped(a, 30, loss = 0.05)
        assertEquals(DataMode.UltraLow, a.currentMode)
    }

    /** A step-up whose estimate is still under the new level's step-down line when the grace period ends. */
    private fun failAfterGrace(a: QualityAdaptor, avail: Double) {
        repeat(QualityAdaptor.STEP_UP_GRACE_SAMPLES) { a.step(sample(avail)) }
    }

    @Test fun slowlyRampingEstimateAfterAStepUpHolds() {
        val a = QualityAdaptor(DataMode.Balanced, videoWanted = false)
        a.step(sample(30_000.0))
        feedCapped(a, QualityAdaptor.GOOD_SAMPLES_TO_STEP_UP)
        assertEquals(DataMode.DataSaver, a.currentMode)
        // WebRTC ramps up over several seconds; Data saver's step-down line is 25.5k.
        for (est in listOf(22_000.0, 23_500.0, 25_000.0, 27_000.0, 30_000.0)) {
            a.step(MediaStats(availableOutgoingBitrate = est, bweCeiling = 30_000.0))
            assertEquals(DataMode.DataSaver, a.currentMode, "$est")
        }
        feedCapped(a, QualityAdaptor.GOOD_SAMPLES_TO_STEP_UP)
        assertEquals(DataMode.Balanced, a.currentMode) // the step-up held, so no hold-off
    }

    @Test fun lossDuringTheGracePeriodFallsBackAndHoldsOff() {
        val a = QualityAdaptor(DataMode.Balanced, videoWanted = false)
        a.step(sample(30_000.0))
        feedCapped(a, QualityAdaptor.GOOD_SAMPLES_TO_STEP_UP)
        assertEquals(DataMode.DataSaver, a.currentMode)
        feedCapped(a, 2, loss = 0.15) // two samples over the loss limit: down at once
        assertEquals(DataMode.UltraLow, a.currentMode)
        feedCapped(a, QualityAdaptor.STEP_UP_HOLD_OFF_SAMPLES + QualityAdaptor.GOOD_SAMPLES_TO_STEP_UP - 1)
        assertEquals(DataMode.UltraLow, a.currentMode)
        feedCapped(a, 1)
        assertEquals(DataMode.DataSaver, a.currentMode)
    }

    @Test fun failedStepUpHoldsOffAndBacksOff() {
        val a = QualityAdaptor(DataMode.Balanced, videoWanted = false)
        val up = QualityAdaptor.GOOD_SAMPLES_TO_STEP_UP
        val hold = QualityAdaptor.STEP_UP_HOLD_OFF_SAMPLES
        a.step(sample(30_000.0))
        feedCapped(a, up)
        assertEquals(DataMode.DataSaver, a.currentMode)
        failAfterGrace(a, 20_000.0) // the network cannot sustain it
        assertEquals(DataMode.UltraLow, a.currentMode)
        feedCapped(a, hold + up - 1)
        assertEquals(DataMode.UltraLow, a.currentMode)
        feedCapped(a, 1)
        assertEquals(DataMode.DataSaver, a.currentMode)
        failAfterGrace(a, 20_000.0) // fails again: the hold-off doubles
        feedCapped(a, 2 * hold + up - 1)
        assertEquals(DataMode.UltraLow, a.currentMode)
        feedCapped(a, 1)
        assertEquals(DataMode.DataSaver, a.currentMode)
        // A step-up that holds clears the back-off.
        feedCapped(a, up)
        assertEquals(DataMode.Balanced, a.currentMode)
        failAfterGrace(a, 40_000.0)
        assertEquals(DataMode.DataSaver, a.currentMode)
        feedCapped(a, hold + up)
        assertEquals(DataMode.Balanced, a.currentMode)
    }

    @Test fun estimateBelowTheCapIsNotSaturated() {
        val a = QualityAdaptor(DataMode.Balanced, videoWanted = false)
        a.step(sample(30_000.0))
        repeat(30) { a.step(MediaStats(availableOutgoingBitrate = 19_000.0, bweCeiling = 21_000.0)) }
        assertEquals(DataMode.UltraLow, a.currentMode)
    }
}
