package org.example.app.messaging.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.app.core.calls.NetworkType
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.core.settings.DownloadScope
import org.example.app.core.settings.MediaKind

class AutoDownloadTest {
    @Test fun autoDownloadRules() {
        val s = AutoDownloadSettings()
        for (k in MediaKind.values()) assertTrue(AutoDownload.shouldDownload(k, NetworkType.Wifi, s), "wifi $k")
        for (k in listOf(MediaKind.Photo, MediaKind.Video, MediaKind.File)) assertFalse(AutoDownload.shouldDownload(k, NetworkType.Mobile, s), "mobile $k")
        assertTrue(AutoDownload.shouldDownload(MediaKind.Voice, NetworkType.Mobile, s), "voice on mobile")
        for (k in listOf(MediaKind.Photo, MediaKind.Video, MediaKind.File)) assertFalse(AutoDownload.shouldDownload(k, NetworkType.Offline, s))
    }

    @Test fun photosOnlyAndVoiceSwitch() {
        val s = AutoDownloadSettings(onMobile = DownloadScope.PhotosOnly, voiceAlways = false)
        assertTrue(AutoDownload.shouldDownload(MediaKind.Photo, NetworkType.Mobile, s))
        assertFalse(AutoDownload.shouldDownload(MediaKind.File, NetworkType.Mobile, s))
        assertFalse(AutoDownload.shouldDownload(MediaKind.Voice, NetworkType.Mobile, s))
    }
}
