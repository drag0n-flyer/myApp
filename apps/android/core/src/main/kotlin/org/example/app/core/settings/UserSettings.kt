package org.example.app.core.settings

import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference

/** The user's call data settings. */
data class CallSettings(
    val callDataMode: ModePreference = ModePreference.Auto,
    val mobileDataLimit: DataMode = DataMode.DataSaver,
    val videoOffOnMobile: Boolean = true,
)

enum class NoiseSuppression { Standard, Enhanced, Off }

data class UserSettings(
    val call: CallSettings = CallSettings(),
    val autoDownload: AutoDownloadSettings = AutoDownloadSettings(),
    val ipHiding: IPHiding = IPHiding.NonContacts,
    val findByUsername: Boolean = true,
    val findByEmail: Boolean = false,
    val readReceipts: Boolean = true,
    val disappearingMessages: Double? = null,
    val noiseSuppression: NoiseSuppression = NoiseSuppression.Standard,
) {
    companion object {
        /** A new user's settings, taking the server administrator's defaults. */
        fun initial(server: ServerPolicy): UserSettings = UserSettings(
            call = CallSettings(callDataMode = server.defaultPreference),
            ipHiding = server.defaultIPHiding,
        )
    }
}
