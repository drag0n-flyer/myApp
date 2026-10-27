package org.example.app.calls.policy

import org.example.app.contracts.DataMode

object VideoLadder {
    data class Spec(val width: Int, val height: Int, val fps: Int, val minBps: Int, val maxBps: Int)

    fun spec(tier: VideoTier): Spec = when (tier) {
        VideoTier.P360 -> Spec(640, 360, 20, 250_000, 300_000)
        VideoTier.P270 -> Spec(480, 270, 15, 140_000, 200_000)
        VideoTier.P180 -> Spec(320, 180, 15, 40_000, 100_000)
    }

    /** The highest tier the ceiling allows. P180 is used at UltraLow and DataSaver, only if the user turned video on. */
    fun allowed(ceiling: DataMode): VideoTier = when (ceiling) {
        DataMode.High -> VideoTier.P360
        DataMode.Balanced -> VideoTier.P270
        DataMode.DataSaver, DataMode.UltraLow -> VideoTier.P180
    }

    fun startsOff(ceiling: DataMode, onMobile: Boolean, videoOffOnMobile: Boolean): Boolean =
        ceiling <= DataMode.DataSaver || (onMobile && videoOffOnMobile)

    /** A tier never exceeds what the ceiling allows. */
    fun clampTier(tier: VideoTier, ceiling: DataMode): VideoTier = minOf(tier, allowed(ceiling))

    /** The tier the media layer applies for `setVideo`: null stays off, anything else is clamped to the ceiling it was given. */
    fun effectiveTier(tier: VideoTier?, ceiling: DataMode): VideoTier? = tier?.let { clampTier(it, ceiling) }

    /** Highest tier (at most [maxTier]) whose minimum fits [budget], with the sender capped at min(budget, max). */
    fun pick(budget: Double, maxTier: VideoTier?): Pair<VideoTier, Int>? {
        if (maxTier == null) return null
        for (tier in VideoTier.entries.reversed()) {
            if (tier > maxTier) continue
            val s = spec(tier)
            if (s.minBps <= budget) return tier to minOf(budget, s.maxBps.toDouble()).toInt()
        }
        return null
    }
}
