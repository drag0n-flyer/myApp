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
            val r = VoiceNoteFinisher.finish(frames(100), d)
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
            assertIs<StopResult.TooShort>(VoiceNoteFinisher.finish(frames(10), d))   // 0.2 s
            assertEquals(emptyList(), d.list()!!.toList())
        } finally { d.deleteRecursively() }
    }

    @Test fun noPacketsIsAnEncoderFailureNotASilentNull() {
        val d = dir()
        try {
            val r = assertIs<StopResult.Failed>(VoiceNoteFinisher.finish(emptyList(), d))
            assertEquals(VoiceFailure.Encoder, r.reason)
        } finally { d.deleteRecursively() }
    }

    @Test fun packetTheValidatorRejectsIsReportedAndDeleted() {
        val d = dir()
        try {
            val stereo = frames(100).toMutableList().also { it[3][0] = (it[3][0].toInt() or 0x04).toByte() }
            val r = assertIs<StopResult.Failed>(VoiceNoteFinisher.finish(stereo, d))
            assertEquals(VoiceFailure.Invalid, r.reason)
            assertEquals(emptyList(), d.list()!!.toList())
        } finally { d.deleteRecursively() }
    }

    @Test fun unwritableOutputDirectoryIsAStorageFailure() {
        val d = dir()
        try {
            val blocker = File(d, "file").also { it.writeText("x") }
            val r = assertIs<StopResult.Failed>(VoiceNoteFinisher.finish(frames(100), File(blocker, "sub")))
            assertEquals(VoiceFailure.Storage, r.reason)
        } finally { d.deleteRecursively() }
    }

    @Test fun exactlyThirtyMinutesIsKept() {
        val d = dir()
        try {
            val ok = assertIs<StopResult.Success>(VoiceNoteFinisher.finish(frames(90_000), d))
            assertEquals(1_800.0, ok.recording.durationSeconds, 0.1)
        } finally { d.deleteRecursively() }
    }

    @Test fun overThirtyMinutesIsRejectedByTheValidator() {
        val d = dir()
        try {
            val r = assertIs<StopResult.Failed>(VoiceNoteFinisher.finish(frames(90_100), d))
            assertEquals(VoiceFailure.Invalid, r.reason)
        } finally { d.deleteRecursively() }
    }

    @Test fun recorderCapLeavesRoomForTheEncoderToAddFramesAndStaysValid() {
        // The recorder stops at MAX_CAPTURE_SAMPLES (16 kHz); the encoder may add a look-ahead frame or two at the end.
        val captureFrames = VoiceNoteFinisher.MAX_CAPTURE_SAMPLES / 320
        val d = dir()
        try {
            val ok = assertIs<StopResult.Success>(VoiceNoteFinisher.finish(frames(captureFrames.toInt() + 2), d))
            assertEquals(1_800.0, ok.recording.durationSeconds, 0.1)
        } finally { d.deleteRecursively() }
    }

    @Test fun preSkipIsAlwaysTheStandard312In48kHzUnits() {
        val d = dir()
        try {
            val ok = assertIs<StopResult.Success>(VoiceNoteFinisher.finish(frames(100), d))
            assertEquals(312, VoiceNoteValidator.validate(ok.recording.file.readBytes()).preSkip)
            assertEquals(312, VoiceNoteFinisher.DEFAULT_PRE_SKIP)
        } finally { d.deleteRecursively() }
    }

    private fun note(d: File) = assertIs<StopResult.Success>(VoiceNoteFinisher.finish(frames(100), d))

    @Test fun abandoningAfterTheNoteIsReadyDeletesTheFile() {
        val d = dir()
        try {
            val slot = NoteSlot()
            val ok = note(d)
            slot.offer(ok)
            slot.abandon()                     // cancel() after the 30-minute auto-stop
            assertEquals(emptyList(), d.list()!!.toList())
        } finally { d.deleteRecursively() }
    }

    @Test fun abandoningBeforeTheNoteIsReadyDeletesItWhenItArrives() {
        val d = dir()
        try {
            val slot = NoteSlot()
            slot.abandon()                     // stop() gave up waiting while the thread was inside finish
            slot.offer(note(d))
            assertEquals(emptyList(), d.list()!!.toList())
        } finally { d.deleteRecursively() }
    }

    @Test fun aNoteThatIsNotAbandonedIsKept() {
        val d = dir()
        try {
            val slot = NoteSlot()
            val ok = note(d)
            slot.offer(ok)
            assertEquals(ok, slot.result)
            assertEquals(1, d.list()!!.size)
        } finally { d.deleteRecursively() }
    }
}
