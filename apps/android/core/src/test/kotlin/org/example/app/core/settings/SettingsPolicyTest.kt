package org.example.app.core.settings

import org.example.app.core.AppBrand
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.ModePreference
import org.example.app.core.calls.NetworkType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsPolicyTest {
    @Test fun autoDownloadDefaults() {
        val d = AutoDownloadSettings()
        assertTrue(AutoDownloadPolicy.shouldDownload(MediaKind.Photo, NetworkType.Wifi, d))
        assertFalse(AutoDownloadPolicy.shouldDownload(MediaKind.Photo, NetworkType.Mobile, d))
        assertTrue(AutoDownloadPolicy.shouldDownload(MediaKind.Voice, NetworkType.Mobile, d))
        assertFalse(AutoDownloadPolicy.shouldDownload(MediaKind.Voice, NetworkType.Offline, d))
        val p = d.copy(onMobile = DownloadScope.PhotosOnly)
        assertTrue(AutoDownloadPolicy.shouldDownload(MediaKind.Photo, NetworkType.Mobile, p))
        assertFalse(AutoDownloadPolicy.shouldDownload(MediaKind.File, NetworkType.Mobile, p))
    }

    @Test fun ipHiding() {
        assertEquals(
            listOf("ip_hiding_never", "ip_hiding_non_contacts", "ip_hiding_always"),
            IPHiding.entries.map { it.titleKey },
        )
        assertFalse(IPHiding.NonContacts.requiresRelay(isContact = true))
        assertTrue(IPHiding.NonContacts.requiresRelay(isContact = false))
        assertTrue(IPHiding.Always.requiresRelay(isContact = true))
        assertFalse(IPHiding.Never.requiresRelay(isContact = false))
    }

    @Test fun initialFromServer() {
        val server = ServerPolicy(ModePreference.Fixed(DataMode.Balanced), DataMode.High, IPHiding.Always, false, "x.org")
        val s = UserSettings.initial(server)
        assertEquals(ModePreference.Fixed(DataMode.Balanced), s.call.callDataMode)
        assertEquals(IPHiding.Always, s.ipHiding)
        assertEquals(DataMode.DataSaver, s.call.mobileDataLimit)
        assertTrue(s.call.videoOffOnMobile)
        assertFalse(s.findByEmail)
    }

    @Test fun syncScopes() {
        val account = SettingKey.entries.filter { it.scope == SettingScope.Account }.toSet()
        assertEquals(
            setOf(
                SettingKey.ChatFolders, SettingKey.Contacts, SettingKey.BlockedList, SettingKey.Privacy,
                SettingKey.CallDataMode, SettingKey.MobileDataLimit, SettingKey.ReadState, SettingKey.DisappearingMessages,
            ),
            account,
        )
    }

    @Test fun brand() = assertEquals("[App]", AppBrand.NAME)
}
