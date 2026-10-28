package org.example.app.core.settings

import org.example.app.core.calls.NetworkType

enum class MediaKind { Photo, Video, File, Voice }

enum class DownloadScope {
    All, PhotosOnly, None;

    val titleKey: String
        get() = when (this) {
            All -> "download_all"
            PhotosOnly -> "download_photos_only"
            None -> "download_none"
        }
}

data class AutoDownloadSettings(
    val onWiFi: DownloadScope = DownloadScope.All,
    val onMobile: DownloadScope = DownloadScope.None,
    val voiceAlways: Boolean = true,
)

object AutoDownloadPolicy {
    fun shouldDownload(kind: MediaKind, network: NetworkType, settings: AutoDownloadSettings): Boolean {
        val scope = when (network) {
            NetworkType.Offline -> return false
            NetworkType.Wifi -> settings.onWiFi
            NetworkType.Mobile -> settings.onMobile
        }
        if (kind == MediaKind.Voice && settings.voiceAlways) return true
        return when (scope) {
            DownloadScope.All -> true
            DownloadScope.PhotosOnly -> kind == MediaKind.Photo
            DownloadScope.None -> false
        }
    }
}
