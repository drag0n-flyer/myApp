package org.example.app.media

import java.io.File
import java.io.IOException
import org.example.app.messaging.PreparedMedia
import org.example.app.messaging.media.MediaPlan
import org.example.app.messaging.media.ogg.OggOpusException
import org.example.app.messaging.media.ogg.VoiceNoteException
import org.example.app.messaging.media.ogg.VoiceNoteValidator

/** Checks a recorded note the way a receiver will and reads its real length, so the message carries it. */
object VoicePrep {
    /** Null for a file that is too large, not Ogg Opus or not readable: such a note is not sent. */
    fun prepare(file: File): PreparedMedia? = try {
        VoiceNoteValidator.checkFileSize(file.length())
        val stream = VoiceNoteValidator.validate(file.readBytes())
        PreparedMedia(file, duration = MediaPlan.Voice.clamped(stream.durationSeconds))
    } catch (_: VoiceNoteException) {
        null
    } catch (_: OggOpusException) {
        null
    } catch (_: IOException) {
        null
    }
}
