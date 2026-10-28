package org.example.app.core.settings

import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference

/** Settings the server administrator controls. */
data class ServerPolicy(
    val defaultPreference: ModePreference,
    val maximum: DataMode,
    val defaultIPHiding: IPHiding,
    val requiresInvite: Boolean,
    val host: String,
)
