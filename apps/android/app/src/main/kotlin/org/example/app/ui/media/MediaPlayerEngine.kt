package org.example.app.ui.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * [VoiceEngine] on the platform's [MediaPlayer], which plays the Ogg Opus voice notes the app records and receives
 * (Android decodes Opus in Ogg since 5.0). Every call is guarded: a player in the wrong state or already released is a
 * failure to report, never a crash.
 */
class MediaPlayerEngine : VoiceEngine {
    private val player = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }
    private var released = false

    override fun prepare(file: File, listener: VoiceEngine.Listener) {
        player.setOnPreparedListener { listener.onPrepared(runCatching { it.duration }.getOrDefault(0).coerceAtLeast(0)) }
        player.setOnCompletionListener { listener.onCompleted() }
        player.setOnErrorListener { _, _, _ ->
            listener.onError()
            true
        }
        try {
            player.setDataSource(file.path)
            player.prepareAsync()
        } catch (e: Exception) {
            listener.onError()
        }
    }

    override fun start() {
        if (!released) runCatching { player.start() }
    }

    override fun pause() {
        if (!released) runCatching { player.pause() }
    }

    override val positionMs: Int
        get() = if (released) 0 else runCatching { player.currentPosition }.getOrDefault(0)

    override fun release() {
        if (released) return
        released = true
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    companion object {
        val factory = VoiceEngineFactory { MediaPlayerEngine() }
    }
}
