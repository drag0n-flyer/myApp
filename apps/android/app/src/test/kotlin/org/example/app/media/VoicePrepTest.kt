package org.example.app.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What is sent with a recorded note: its real length, and nothing at all for a file that is not a valid note. */
class VoicePrepTest {
    private fun frame(i: Int = 0) = ByteArray(41) { if (it == 0) 0x48 else (i + it).toByte() }
    private fun dir() = File.createTempFile("voiceprep", "").also { it.delete(); it.mkdirs() }

    @Test fun aRecordedNoteIsSentWithItsRealLength() {
        val d = dir()
        try {
            val note = assertIs<StopResult.Success>(VoiceNoteFinisher.finish(List(150) { frame(it) }, d)).recording.file
            val prepared = assertNotNull(VoicePrep.prepare(note))
            assertEquals(note, prepared.file)
            assertTrue(prepared.duration in 2.9..3.1, "length ${prepared.duration}")
        } finally { d.deleteRecursively() }
    }

    @Test fun aBrokenNoteIsNotSent() {
        val d = dir()
        try {
            val broken = File(d, "broken.ogg").also { it.writeBytes(ByteArray(64) { 7 }) }
            assertNull(VoicePrep.prepare(broken))
            assertNull(VoicePrep.prepare(File(d, "missing.ogg")))
        } finally { d.deleteRecursively() }
    }
}
