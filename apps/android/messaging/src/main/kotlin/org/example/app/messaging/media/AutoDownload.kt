package org.example.app.messaging.media

import org.example.app.core.calls.NetworkType
import org.example.app.core.settings.AutoDownloadPolicy
import org.example.app.core.settings.AutoDownloadSettings
import org.example.app.core.settings.MediaKind

/**
 * Whether an attachment is fetched without a tap (Design 9). Wi-Fi: everything by default; mobile data: nothing
 * by default; a voice note always downloads unless the person turned that off. Offline never downloads.
 */
object AutoDownload {
    fun shouldDownload(kind: MediaKind, network: NetworkType, settings: AutoDownloadSettings = AutoDownloadSettings()): Boolean =
        AutoDownloadPolicy.shouldDownload(kind, network, settings)
}
