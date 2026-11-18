package org.example.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.core.settings.DownloadScope
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.NoiseSuppression
import org.example.app.core.settings.UserSettings
import org.example.app.ui.settings.SettingsStore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("settings-test", Context.MODE_PRIVATE)

    @Test
    fun theSignalSwitchesAreOnByDefaultAndSurviveARestart() {
        prefs.edit().clear().commit()
        assertEquals(true, SettingsStore(prefs).settings.value.typingIndicator)
        assertEquals(true, SettingsStore(prefs).settings.value.onlineAndLastSeen)
        SettingsStore(prefs).update { it.copy(typingIndicator = false) }
        val again = SettingsStore(prefs).settings.value
        assertEquals(false, again.typingIndicator)
        assertEquals(true, again.onlineAndLastSeen)
        SettingsStore(prefs).update { it.copy(onlineAndLastSeen = false) }
        assertEquals(false, SettingsStore(prefs).settings.value.onlineAndLastSeen)
    }

    @Test
    fun aValueUpdatedInOneStoreIsReadByASecondOverTheSamePreferences() {
        prefs.edit().clear().commit()
        val first = SettingsStore(prefs)
        first.update {
            it.copy(
                ipHiding = IPHiding.Always, findByEmail = true, readReceipts = false, linkPreviews = true, noiseSuppression = NoiseSuppression.Off,
                call = it.call.copy(callDataMode = ModePreference.Fixed(DataMode.High), videoOffOnMobile = false),
                autoDownload = AutoDownloadSettings(onMobile = DownloadScope.All),
            )
        }
        val second = SettingsStore(prefs).settings.value
        assertEquals(IPHiding.Always, second.ipHiding)
        assertEquals(true, second.findByEmail)
        assertEquals(false, second.readReceipts)
        assertEquals(true, second.linkPreviews)
        assertEquals(NoiseSuppression.Off, second.noiseSuppression)
        assertEquals(ModePreference.Fixed(DataMode.High), second.call.callDataMode)
        assertEquals(false, second.call.videoOffOnMobile)
        assertEquals(DownloadScope.All, second.autoDownload.onMobile)
        assertEquals(first.settings.value, second)
    }

    @Test
    fun missingStoredDataGivesTheInitialSettings() {
        prefs.edit().clear().commit()
        val initial = UserSettings(ipHiding = IPHiding.Never)
        assertEquals(initial, SettingsStore(prefs, initial).settings.value)
    }

    @Test
    fun corruptStoredDataFallsBackToTheInitialSettings() {
        val initial = UserSettings(ipHiding = IPHiding.Never)
        for (junk in listOf("not json", "{", "[]", "{\"ipHiding\":\"Bogus\"}", "{\"readReceipts\":\"maybe\"}", "")) {
            prefs.edit().clear().putString("settings", junk).commit()
            assertEquals(initial, SettingsStore(prefs, initial).settings.value, "stored: $junk")
        }
        prefs.edit().clear().putInt("settings", 7).commit() // the wrong type under the key
        assertEquals(initial, SettingsStore(prefs, initial).settings.value)
    }

    @Test
    fun anUnknownValueInOneFieldKeepsTheOtherFields() {
        prefs.edit().clear().putString("settings", """{"ipHiding":"FromTheFuture","readReceipts":false,"findByEmail":true,"onMobile":"Nope"}""").commit()
        val s = SettingsStore(prefs, UserSettings(ipHiding = IPHiding.Never)).settings.value
        assertEquals(IPHiding.Never, s.ipHiding, "the unknown one takes its default")
        assertEquals(false, s.readReceipts)
        assertEquals(true, s.findByEmail)
        assertEquals(AutoDownloadSettings().onMobile, s.autoDownload.onMobile)
    }

    @Test
    fun linkPreviewsAreOffUnlessStoredAsOn() {
        assertEquals(false, UserSettings().linkPreviews)
        for (junk in listOf("{}", "{\"linkPreviews\":\"yes\"}", "{\"linkPreviews\":1}")) {
            prefs.edit().clear().putString("settings", junk).commit()
            assertEquals(false, SettingsStore(prefs).settings.value.linkPreviews, junk)
        }
    }

    @Test
    fun resetForgetsTheStoredSettings() {
        prefs.edit().clear().commit()
        val store = SettingsStore(prefs)
        store.update { it.copy(readReceipts = false, findByEmail = true) }
        store.reset(UserSettings(ipHiding = IPHiding.Always))
        assertEquals(UserSettings(ipHiding = IPHiding.Always), store.settings.value)
        assertEquals(UserSettings(), SettingsStore(prefs).settings.value, "nothing is left in the preferences")
    }

    @Test
    fun aStoreThatFellBackSavesOnTheNextUpdate() {
        prefs.edit().clear().putString("settings", "junk").commit()
        SettingsStore(prefs).update { it.copy(readReceipts = false) }
        assertEquals(false, SettingsStore(prefs).settings.value.readReceipts)
    }

    @Test
    fun anUpdateThatStartedBeforeAResetIsDropped() {
        val store = SettingsStore()
        val started = store.generation
        store.reset(UserSettings())
        store.update(started) { it.copy(findByEmail = true) } // a late save from the old account
        assertEquals(false, store.settings.value.findByEmail)
        store.update(store.generation) { it.copy(findByEmail = true) }
        assertEquals(true, store.settings.value.findByEmail)
    }
}
