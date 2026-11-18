package org.example.app.ui.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * [VoiceFocus] on the platform's `AudioManager`: a transient focus for speech, so music pauses while a voice message
 * plays and comes back after. Losing it (a call, another app) or being asked to duck is reported through `onLost` only
 * for a real loss; the message pauses and the user presses play again.
 */
class AudioManagerFocus(context: Context) : VoiceFocus {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var request: AudioFocusRequest? = null

    override fun request(onLost: () -> Unit): Boolean {
        val manager = audio ?: return true
        abandon()
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
            }, Handler(Looper.getMainLooper()))
            .build()
        val granted = runCatching { manager.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
        if (granted) request = next
        return granted
    }

    override fun abandon() {
        val held = request ?: return
        request = null
        runCatching { audio?.abandonAudioFocusRequest(held) }
    }
}
