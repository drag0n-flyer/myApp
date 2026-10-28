package org.example.app.calls.policy

import org.example.app.contracts.DataMode

data class AudioProfile(
    val mode: DataMode,
    val maxAverageBitrate: Int,
    val ptime: Int,
    val maxPlaybackRate: Int,
    val wireBudget: Int,
    val inbandFEC: Boolean,
    val dtx: Boolean,
) {
    companion object {
        /** The single source for data mode to wire settings. Same values as the iPhone's table. */
        val table: Map<DataMode, AudioProfile> = mapOf(
            DataMode.UltraLow to AudioProfile(DataMode.UltraLow, 10_000, 60, 16_000, 21_000, inbandFEC = false, dtx = true),
            DataMode.DataSaver to AudioProfile(DataMode.DataSaver, 16_000, 40, 16_000, 30_000, inbandFEC = true, dtx = true),
            DataMode.Balanced to AudioProfile(DataMode.Balanced, 24_000, 20, 48_000, 51_000, inbandFEC = true, dtx = true),
            DataMode.High to AudioProfile(DataMode.High, 40_000, 20, 48_000, 75_000, inbandFEC = true, dtx = true),
        )

        fun forMode(mode: DataMode): AudioProfile = table.getValue(mode)
    }
}
