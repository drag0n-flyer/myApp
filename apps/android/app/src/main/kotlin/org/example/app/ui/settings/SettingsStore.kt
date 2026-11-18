package org.example.app.ui.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.DownloadScope
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.NoiseSuppression
import org.example.app.core.settings.UserSettings
import org.json.JSONObject

/**
 * The user's settings: loaded from [prefs] when made and saved on every [update], so they survive a restart. Stored
 * data is read field by field: a missing key or a value this version does not know gives that field's value from
 * [initial] and keeps the rest. Data that is not JSON at all gives [initial]. Without [prefs] (sample data, tests)
 * the settings live in memory only.
 */
class SettingsStore(private val prefs: SharedPreferences? = null, initial: UserSettings = UserSettings()) {
    private val state = MutableStateFlow(prefs?.let { load(it, initial) } ?: initial)
    val settings: StateFlow<UserSettings> = state.asStateFlow()

    private var epoch = 0L

    /** Counts [reset]s. Work that outlives a reset (a save in flight) carries the value it started under. */
    val generation: Long @Synchronized get() = epoch

    /**
     * Applies [change]. With [startedUnder] (the [generation] read before the work began) the update is dropped when a
     * [reset] happened since: a late result must not write the old account's value into the new account's settings.
     */
    @Synchronized
    fun update(startedUnder: Long? = null, change: (UserSettings) -> UserSettings) {
        if (startedUnder != null && startedUnder != epoch) return
        val next = change(state.value)
        state.value = next
        prefs?.let { save(it, next) }
    }

    /** Forgets everything stored and starts again from [initial]: the next account must not inherit these settings. */
    @Synchronized
    fun reset(initial: UserSettings) {
        epoch++
        state.value = initial
        prefs?.edit()?.remove(KEY)?.commit()
    }

    private companion object {
        const val KEY = "settings"

        fun load(prefs: SharedPreferences, initial: UserSettings): UserSettings? = try {
            prefs.getString(KEY, null)?.let { decode(JSONObject(it), initial) }
        } catch (_: Exception) {
            null // not JSON, or the wrong type under the key
        }

        fun save(prefs: SharedPreferences, s: UserSettings) {
            prefs.edit().putString(KEY, encode(s).toString()).apply()
        }

        fun encode(s: UserSettings) = JSONObject()
            .put("callDataMode", when (val m = s.call.callDataMode) { ModePreference.Auto -> "Auto"; is ModePreference.Fixed -> m.mode.name })
            .put("mobileDataLimit", s.call.mobileDataLimit.name)
            .put("videoOffOnMobile", s.call.videoOffOnMobile)
            .put("onWiFi", s.autoDownload.onWiFi.name)
            .put("onMobile", s.autoDownload.onMobile.name)
            .put("voiceAlways", s.autoDownload.voiceAlways)
            .put("ipHiding", s.ipHiding.name)
            .put("findByEmail", s.findByEmail)
            .put("readReceipts", s.readReceipts)
            .put("noiseSuppression", s.noiseSuppression.name)

        fun decode(o: JSONObject, d: UserSettings): UserSettings {
            fun bool(key: String, default: Boolean): Boolean = (if (o.has(key)) o.opt(key) else null) as? Boolean ?: default
            fun text(key: String): String? = (if (o.has(key)) o.opt(key) else null) as? String
            fun scope(key: String, default: DownloadScope) = text(key)?.let { v -> DownloadScope.entries.firstOrNull { it.name == v } } ?: default
            fun mode(key: String, default: DataMode) = text(key)?.let { v -> DataMode.entries.firstOrNull { it.name == v } } ?: default
            val callMode = text("callDataMode")?.let { v ->
                if (v == "Auto") ModePreference.Auto else DataMode.entries.firstOrNull { it.name == v }?.let { ModePreference.Fixed(it) }
            } ?: d.call.callDataMode
            return d.copy(
                call = CallSettings(callMode, mode("mobileDataLimit", d.call.mobileDataLimit), bool("videoOffOnMobile", d.call.videoOffOnMobile)),
                autoDownload = AutoDownloadSettings(
                    scope("onWiFi", d.autoDownload.onWiFi), scope("onMobile", d.autoDownload.onMobile), bool("voiceAlways", d.autoDownload.voiceAlways),
                ),
                ipHiding = text("ipHiding")?.let { v -> IPHiding.entries.firstOrNull { it.name == v } } ?: d.ipHiding,
                findByEmail = bool("findByEmail", d.findByEmail),
                readReceipts = bool("readReceipts", d.readReceipts),
                noiseSuppression = text("noiseSuppression")?.let { v -> NoiseSuppression.entries.firstOrNull { it.name == v } } ?: d.noiseSuppression,
            )
        }
    }
}
