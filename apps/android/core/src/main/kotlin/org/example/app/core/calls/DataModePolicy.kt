package org.example.app.core.calls

import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.ServerPolicy

/** One row of the in-call "Data for this call" sheet. Text fields are string resource keys. */
data class DataSheetOption(
    val preference: ModePreference,
    val titleKey: String,
    val subtitleKey: String?,
    /** Estimated audio data per 10 minutes in MB; null for Auto. */
    val estimateMB: Int?,
    val noteKey: String?,
    val isEnabled: Boolean,
    val isSelected: Boolean,
)

/**
 * Decides which data mode a call may use.
 *
 * Order of limits: the server maximum always applies; a fixed in-call choice
 * replaces the user's default and lifts the mobile-data limit for that call;
 * otherwise the mobile-data limit applies on mobile data; the network decides last.
 */
object DataModePolicy {
    fun ceiling(server: ServerPolicy, settings: CallSettings, network: NetworkType, inCall: ModePreference?): DataMode {
        var cap = when (val preference = inCall ?: settings.callDataMode) {
            ModePreference.Auto -> DataMode.High
            is ModePreference.Fixed -> preference.mode
        }
        val liftsMobileLimit = inCall is ModePreference.Fixed
        if (network == NetworkType.Mobile && !liftsMobileLimit) {
            cap = minOf(cap, settings.mobileDataLimit)
        }
        return minOf(cap, server.maximum)
    }

    fun effective(
        server: ServerPolicy, settings: CallSettings, network: NetworkType,
        inCall: ModePreference?, sustainable: DataMode,
    ): DataMode = minOf(ceiling(server, settings, network, inCall), sustainable)

    fun sheetOptions(server: ServerPolicy, settings: CallSettings, network: NetworkType, inCall: ModePreference?): List<DataSheetOption> {
        val selected = inCall ?: settings.callDataMode
        val auto = DataSheetOption(
            ModePreference.Auto, "mode_auto", "mode_auto_subtitle", null, null,
            isEnabled = true, isSelected = selected == ModePreference.Auto,
        )
        val modes = DataMode.entries.map { mode ->
            var note: String? = null
            var enabled = true
            if (mode > server.maximum) {
                note = "note_limited_by_server"
                enabled = false
            } else if (network == NetworkType.Mobile && mode > settings.mobileDataLimit) {
                note = "note_above_mobile_limit"
            }
            DataSheetOption(
                ModePreference.Fixed(mode), DataModeInfo.titleKey(mode), DataModeInfo.subtitleKey(mode),
                mode.estimatedMBPer10Minutes, note, enabled, selected == ModePreference.Fixed(mode),
            )
        }
        return listOf(auto) + modes
    }

    /** Title key of the mode a new call would start with on the current network, for the chat list strip. */
    fun stripLabelKey(server: ServerPolicy, settings: CallSettings, network: NetworkType): String {
        if (settings.callDataMode == ModePreference.Auto && network != NetworkType.Mobile) {
            return "mode_auto"
        }
        return DataModeInfo.titleKey(ceiling(server, settings, network, null))
    }
}
