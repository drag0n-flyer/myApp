package org.example.app.core.calls

/** What the user asked for: automatic, or a fixed mode. */
sealed interface ModePreference {
    data object Auto : ModePreference
    data class Fixed(val mode: DataMode) : ModePreference

    val titleKey: String
        get() = when (this) {
            Auto -> "mode_auto"
            is Fixed -> DataModeInfo.titleKey(mode)
        }
}
