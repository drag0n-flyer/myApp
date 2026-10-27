package org.example.app.calls.policy

import org.example.app.contracts.DataMode

data class AdaptorOutput(
    val mode: DataMode,
    val video: VideoTier?,
    val videoBitrate: Int?,
    val quality: CallQuality,
    val banner: CallBanner?,
)

/**
 * Picks the audio mode and video tier a network can sustain, from a [MediaStats] sample every 2 s.
 * Degrades video tier, then video off, then audio. Recovers one level after several good samples.
 * Mirrors the iPhone's QualityAdaptor exactly.
 */
class QualityAdaptor(private var ceiling: DataMode, private var videoWanted: Boolean) {
    companion object {
        const val STEP_DOWN_MARGIN = 0.85
        const val STEP_UP_MARGIN = 1.3
        const val LOSS_DOWN = 0.10
        const val LOSS_UP_MAX = 0.02
        const val GOOD_SAMPLES_TO_STEP_UP = 5
        const val AUDIO_ONLY_BELOW = 60_000.0
    }

    private data class Level(val mode: DataMode, val tier: VideoTier?)

    private var current = Level(ceiling, if (videoWanted) VideoLadder.allowed(ceiling) else null)
    private var badLossRun = 0
    private var goodRun = 0
    private var lastAvail: Double? = null

    val currentMode: DataMode get() = current.mode
    val currentTier: VideoTier? get() = current.tier

    fun setCeiling(c: DataMode) {
        val raised = c > ceiling
        ceiling = c
        val tier = current.tier
        if (raised && tier != null) {
            // Video levels only exist at the ceiling mode, so a running video call moves up with the ceiling.
            current = Level(c, tier)
        } else {
            clamp()
        }
    }

    fun setVideoWanted(on: Boolean) {
        val turnedOn = on && !videoWanted
        videoWanted = on
        clamp()
        // Jump straight to the best level the last known bandwidth supports instead of waiting for good samples.
        val avail = lastAvail
        if (turnedOn && avail != null) {
            val ladder = levels()
            val idx = ladder.indexOf(current)
            if (idx >= 0) {
                ladder.subList(0, idx + 1).firstOrNull { fits(it, avail) }?.let { current = it }
            }
        }
        goodRun = 0
    }

    fun step(s: MediaStats): AdaptorOutput {
        val ladder = levels()
        val found = ladder.indexOf(current)
        val idx = if (found >= 0) found else ladder.size - 1

        badLossRun = if (s.inboundLossFraction > LOSS_DOWN) badLossRun + 1 else 0
        val lossTrigger = badLossRun >= 2

        // No bandwidth estimate: hold. Only the loss rule applies, and goodRun is neither counted nor reset.
        val measured = s.availableOutgoingBitrate
        if (measured == null) {
            if (lossTrigger) {
                goodRun = 0
                badLossRun = 0
                if (idx < ladder.size - 1) current = ladder[idx + 1]
            }
            return output(lastAvail ?: Double.POSITIVE_INFINITY)
        }
        val avail = measured
        lastAvail = measured
        val bandwidthBad = avail < need(ladder[idx]) * STEP_DOWN_MARGIN ||
            (current.tier != null && avail < AUDIO_ONLY_BELOW)

        if (bandwidthBad || lossTrigger) {
            goodRun = 0
            if (idx < ladder.size - 1) {
                current = if (bandwidthBad) {
                    ladder.subList(idx + 1, ladder.size).firstOrNull { fits(it, avail) } ?: ladder.last()
                } else {
                    ladder[idx + 1]
                }
            }
            if (lossTrigger) badLossRun = 0
        } else if (idx > 0) {
            val next = ladder[idx - 1]
            val good = s.inboundLossFraction < LOSS_UP_MAX &&
                avail >= need(next) * STEP_UP_MARGIN &&
                (next.tier == null || avail >= AUDIO_ONLY_BELOW)
            goodRun = if (good) goodRun + 1 else 0
            if (goodRun >= GOOD_SAMPLES_TO_STEP_UP) {
                current = next
                goodRun = 0
            }
        } else {
            goodRun = 0
        }
        return output(avail)
    }

    /** Best first: video tiers at the ceiling mode, then audio only from the ceiling mode down. */
    private fun levels(): List<Level> {
        val out = mutableListOf<Level>()
        if (videoWanted) {
            val top = VideoLadder.allowed(ceiling)
            for (tier in VideoTier.entries.reversed()) if (tier <= top) out += Level(ceiling, tier)
        }
        for (mode in DataMode.entries.reversed()) if (mode <= ceiling) out += Level(mode, null)
        return out
    }

    private fun need(l: Level): Int =
        AudioProfile.forMode(l.mode).wireBudget + (l.tier?.let { VideoLadder.spec(it).minBps } ?: 0)

    private fun fits(l: Level, avail: Double): Boolean {
        // Stricter than the step-down trigger (need x 0.85 vs avail x 0.85) on purpose: a hold gap that stops flapping.
        if (l.tier != null && avail < AUDIO_ONLY_BELOW) return false
        return need(l).toDouble() <= avail * STEP_DOWN_MARGIN
    }

    /** Keeps the current level where possible, but never above the ceiling or an unwanted video tier. */
    private fun clamp() {
        val ladder = levels()
        val curTier = current.tier?.ordinal ?: -1
        current = ladder.firstOrNull { l -> l.mode <= current.mode && (l.tier?.ordinal ?: -1) <= curTier }
            ?: ladder.last()
    }

    private fun output(avail: Double): AdaptorOutput {
        var bitrate: Int? = null
        var banner: CallBanner? = null
        val tier = current.tier
        if (tier != null) {
            val spec = VideoLadder.spec(tier)
            val budget = avail - AudioProfile.forMode(current.mode).wireBudget
            bitrate = budget.coerceIn(spec.minBps.toDouble(), spec.maxBps.toDouble()).toInt()
            if (tier < VideoLadder.allowed(ceiling)) banner = CallBanner.VideoLowered
        } else if (videoWanted) {
            banner = CallBanner.AudioOnly
        }
        return AdaptorOutput(
            current.mode, tier, bitrate,
            if (current.mode < ceiling) CallQuality.Weak else CallQuality.Good, banner,
        )
    }
}
