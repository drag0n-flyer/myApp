package org.example.app.core.calls

/** Call data modes, from smallest to largest data use. Declaration order is the ordering. */
enum class DataMode {
    UltraLow, DataSaver, Balanced, High;

    /**
     * Estimated audio data per 10 minutes, both directions.
     * Measured 2026-10-28 (CI, simulated), see docs/measurements/2026-10-28-data-use-ci.md
     */
    val estimatedMBPer10Minutes: Int
        get() = when (this) {
            UltraLow -> 2
            DataSaver -> 3
            Balanced -> 5
            High -> 6
        }

    val bytesPerSecond: Int get() = estimatedMBPer10Minutes * 1_000_000 / 600

    /** The wire value of the call payload `ceiling`. */
    val wireName: String
        get() = when (this) {
            UltraLow -> "ultraLow"
            DataSaver -> "dataSaver"
            Balanced -> "balanced"
            High -> "high"
        }
}

/** String resource keys for data mode text; the UI resolves them. */
object DataModeInfo {
    fun titleKey(mode: DataMode): String = when (mode) {
        DataMode.UltraLow -> "mode_ultra_low"
        DataMode.DataSaver -> "mode_data_saver"
        DataMode.Balanced -> "mode_balanced"
        DataMode.High -> "mode_high"
    }

    fun subtitleKey(mode: DataMode): String? = when (mode) {
        DataMode.UltraLow -> "mode_ultra_low_subtitle"
        DataMode.DataSaver -> "mode_data_saver_subtitle"
        DataMode.Balanced, DataMode.High -> null
    }
}
