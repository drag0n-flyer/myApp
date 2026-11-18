package org.example.app.core.chats

/** What a photo, file or voice bubble shows: derived from the message's media state and whether its file is still there. */
sealed interface MediaBubbleState {
    data object NotDownloaded : MediaBubbleState
    /** [fraction] is 0.0 to 1.0. */
    data class Downloading(val fraction: Double) : MediaBubbleState
    /** [path] is the downloaded file, which exists. */
    data class Ready(val path: String) : MediaBubbleState
    data object Failed : MediaBubbleState
}

/**
 * [media] null (nothing known about the attachment) and the remote phase are [MediaBubbleState.NotDownloaded]. A local
 * file that [fileExists] no longer finds is [MediaBubbleState.Failed], so the bubble offers a new download instead of
 * trying to show a file that is gone. [fileExists] is asked only for the local phase.
 */
fun MediaBubbleState(media: MediaState?, fileExists: (String) -> Boolean): MediaBubbleState = when (val phase = media?.phase) {
    null, MediaState.Phase.Remote -> MediaBubbleState.NotDownloaded
    is MediaState.Phase.Downloading -> MediaBubbleState.Downloading(phase.progress.takeUnless { it.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.0)
    is MediaState.Phase.Local -> if (fileExists(phase.path)) MediaBubbleState.Ready(phase.path) else MediaBubbleState.Failed
    MediaState.Phase.Failed -> MediaBubbleState.Failed
}
