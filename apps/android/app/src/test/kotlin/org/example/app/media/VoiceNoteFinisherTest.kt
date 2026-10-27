package org.example.app.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.example.app.messaging.media.ogg.VoiceNoteValidator

/** The packets-to-file path of the voice recorder, driven with synthetic encoded frames (no microphone). */
class VoiceNoteFinisherTest {
    /** One 20 ms mono SILK wideband Opus frame: TOC 0x48 (config 9, mono, one frame) and some payload. */
    private fun frame(i: Int = 0) = ByteArray(41) { if (it == 0) 0x48 else (i + it).toByte() }
    private fun frames(n: Int) = List(n) { frame(it) }
    private fun dir() = File.createTempFile("voice", "").also { it.delete(); it.mkdirs() }

    @Test fun twoSecondsBecomeAValidOggOpusFile() {
        val d = dir()
        try {
            val r = VoiceNoteFinisher.finish(frames(100), 312, d)
            val ok = assertIs<StopResult.Success>(r)
            assertEquals(listOf(ok.recording.file.name), d.list()!!.toList())
            assertTrue(ok.recording.durationSeconds in 1.9..2.1, "duration ${ok.recording.durationSeconds}")
            // The file is what a receiver will validate.
            val stream = VoiceNoteValidator.validate(ok.recording.file.readBytes())
            assertEquals(100, stream.packets.size)
            assertEquals(1, stream.channels)
        } finally { d.deleteRecursively() }
    }

    @Test fun accidentalTapIsTooShortAndLeavesNothing() {
        val d = dir()
        try {
            assertIs<StopResult.TooShort>(VoiceNoteFinisher.finish(frames(10), 312, d))   // 0.2 s
            assertEquals(emptyList(), d.list()!!.toList())
        } finally { d.deleteRecursively() }
    }

    @Test fun noPacketsIsAnEncoderFailureNotASilentNull() {
        val d = dir()
        try {
            val r = assertIs<StopResult.Failed>(VoiceNoteFinisher.finish(emptyList(), 312, d))
            assertEquals(VoiceFailure.Encoder, r.reason)
        } finally { d.deleteRecursively() }
    }

    @Test fun packetTheValidatorRejectsIsReportedAndDeleted() {
        val d = dir()
        try {
            val stereo = frames(100).toMutableList().also { it[3][0] = (it[3][0].toInt() or 0x04).toByte() }
            val r = assertIs<StopResult.Failed>(VoiceNoteFinisher.finish(stereo, 312, d))
            assertEquals(VoiceFailure.Invalid, r.reason)
            assertEquals(emptyList(), d.list()!!.toList())
        } finally { d.deleteRecursively() }
    }

    @Test fun unwritableOutputDirectoryIsAStorageFailure() {
        val d = dir()
        try {
            val blocker = File(d, "file").also { it.writeText("x") }
            val r = assertIs<StopResult.Failed>(VoiceNoteFinisher.finish(frames(100), 312, File(blocker, "sub")))
            assertEquals(VoiceFailure.Storage, r.reason)
        } finally { d.deleteRecursively() }
    }

    @Test fun exactlyThirtyMinutesIsKept() {
        val d = dir()
        try {
            val ok = assertIs<StopResult.Success>(VoiceNoteFinisher.finish(frames(90_000), 312, d))
            assertEquals(1_800.0, ok.recording.durationSeconds, 0.1)
        } finally { d.deleteRecursively() }
    }

    @Test fun overThirtyMinutesIsRejectedByTheValidator() {
        val d = dir()
        try {
            val r = assertIs<StopResult.Failed>(VoiceNoteFinisher.finish(frames(90_100), 312, d))
            assertEquals(VoiceFailure.Invalid, r.reason)
        } finally { d.deleteRecursively() }
    }

    @Test fun preSkipIsParsedFromTheEncodersOpusHead() {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0x38, 0x01, 0x80.toByte(), 0x3E, 0, 0, 0, 0, 0)
        assertEquals(0x138, VoiceNoteFinisher.preSkipFromOpusHead(head))
        assertEquals(VoiceNoteFinisher.DEFAULT_PRE_SKIP, VoiceNoteFinisher.preSkipFromOpusHead(byteArrayOf(1, 2, 3)))
        assertEquals(VoiceNoteFinisher.DEFAULT_PRE_SKIP, VoiceNoteFinisher.preSkipFromOpusHead(null))
    }
}
