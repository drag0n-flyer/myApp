package org.example.app.messaging.media.ogg

enum class VoiceNoteError {
    TooLarge, TooLong, EmptyPacket, InvalidPacket, ChannelMismatch, DurationMismatch,

    /** A format that must not come from the network (CAF). */
    NotAllowedFormat, UnknownFormat,
}

class VoiceNoteException(val error: VoiceNoteError) : Exception(error.name)

object VoiceNoteLimits {
    /** Owner decision: a voice note is at most 30 minutes. */
    const val MAX_DURATION_SECONDS = 1_800
    const val MAX_SAMPLES: Long = MAX_DURATION_SECONDS * 48_000L

    /** 510 kbit/s for 30 minutes is 114 750 000 bytes; the rest is Ogg page overhead and the two header pages. */
    const val MAX_FILE_BYTES = 120_000_000
}

/**
 * Checks run on a voice note before anything is decoded, so a hostile file from a peer cannot exhaust memory.
 * Android only plays and sends Ogg Opus; a CAF (the old iPhone format) is never accepted.
 */
object VoiceNoteValidator {
    fun checkFileSize(bytes: Long) {
        if (bytes < 0 || bytes > VoiceNoteLimits.MAX_FILE_BYTES) throw VoiceNoteException(VoiceNoteError.TooLarge)
    }

    fun validate(data: ByteArray): OggOpusStream {
        checkFileSize(data.size.toLong())
        val prefix = data.copyOfRange(0, minOf(4, data.size))
        when {
            prefix.contentEquals("OggS".toByteArray()) -> Unit
            prefix.contentEquals("caff".toByteArray()) -> throw VoiceNoteException(VoiceNoteError.NotAllowedFormat)
            else -> throw VoiceNoteException(VoiceNoteError.UnknownFormat)
        }
        val stream = OggOpusReader.read(data)
        check(stream)
        return stream
    }

    internal fun check(stream: OggOpusStream) {
        val cap = VoiceNoteLimits.MAX_SAMPLES + stream.preSkip
        if (stream.finalGranule > cap) throw VoiceNoteException(VoiceNoteError.TooLong)
        var total = 0L
        for (packet in stream.packets) {
            if (packet.isEmpty()) throw VoiceNoteException(VoiceNoteError.EmptyPacket)
            val samples = OpusPacket.samples48k(packet) ?: throw VoiceNoteException(VoiceNoteError.InvalidPacket)
            if (stream.channels == 1 && packet[0].toInt() and 0x04 != 0) throw VoiceNoteException(VoiceNoteError.ChannelMismatch)
            total += samples
            if (total > cap) throw VoiceNoteException(VoiceNoteError.TooLong)
        }
        // The last granule may trim encoder padding (less than one packet) but never claims more.
        if (stream.finalGranule > total || total - stream.finalGranule >= 5_760) throw VoiceNoteException(VoiceNoteError.DurationMismatch)
    }
}
