package org.example.app.core.settings

import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.lock.AppLockPolicy

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
    /** Make a link preview for the first link of a message (the device contacts the website). Off until the person turns it on. */
    val linkPreviews: Boolean = false,
    /** Settings > Privacy (design 6.1): off means the app neither sends nor shows typing signals. */
    val typingIndicator: Boolean = true,
    /** Off means the app neither sends nor shows online and last seen. */
    val onlineAndLastSeen: Boolean = true,
    val disappearingMessages: Double? = null,
    val noiseSuppression: NoiseSuppression = NoiseSuppression.Standard,
    /** App lock (design 6.3): the phone's own authentication in front of the screen. Only this switch and the delay are stored. */
    val appLock: Boolean = false,
    /** One of `AppLockPolicy.DELAYS`; 0 is "Immediately". */
    val lockAfterSeconds: Int = AppLockPolicy.DEFAULT_SECONDS,
) {
    companion object {
        /** A new user's settings, taking the server administrator's defaults. */
        fun initial(server: ServerPolicy): UserSettings = UserSettings(
            call = CallSettings(callDataMode = server.defaultPreference),
            ipHiding = server.defaultIPHiding,
        )
    }
}
