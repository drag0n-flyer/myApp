package org.example.app.ui.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Plays the finished recording on the voice preview, before it is sent. The file is the recorder's own plain file, so no
 * decryption is involved. Main thread only. [toggle] plays, pauses and continues; [poll] (called on a timer while the
 * preview shows) refreshes [positionMs]; [release] frees the player.
 */
class VoicePreviewPlayer(private val file: File, private val engines: VoiceEngineFactory) : VoiceEngine.Listener {
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set

    private var engine: VoiceEngine? = null
    private var prepared = false
    private var wantPlay = false

    fun toggle() {
        val e = engine
        if (e == null) {
            wantPlay = true
            engine = engines.create().also { it.prepare(file, this) }
        } else if (!prepared) {
            wantPlay = !wantPlay
        } else if (playing) {
            e.pause()
            playing = false
        } else {
            e.start()
            playing = true
        }
    }

    fun poll() {
        val e = engine ?: return
        if (playing) positionMs = e.positionMs
    }

    fun release() {
        engine?.release()
        engine = null
        prepared = false
        wantPlay = false
        playing = false
    }

    override fun onPrepared(durationMs: Int) {
        prepared = true
        this.durationMs = durationMs
        if (wantPlay) {
            engine?.start()
            playing = true
        }
    }

    override fun onCompleted() {
        release()
        positionMs = 0
    }

    override fun onError() {
        release()
    }
}
