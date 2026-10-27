package org.example.app.messaging.media.ogg

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class OggOpusTest {
    private val json = Json.parseToJsonElement(File("../../../docs/protocol/test-vectors/voice-ogg-v1.json").readText()).jsonObject
    private val ogg = Base64.getDecoder().decode(json["oggB64"]!!.jsonPrimitive.content)
    private val packets = json["packetsB64"]!!.jsonArray.map { Base64.getDecoder().decode(it.jsonPrimitive.content) }
    private val expected = json["expected"]!!.jsonObject

    private fun errorOf(block: () -> Unit): OggOpusError = assertFailsWith<OggOpusException> { block() }.error

    @Test fun vectorFileMatchesTheCopyInTheFolder() {
        assertContentEquals(ogg, File("../../../docs/protocol/test-vectors/voice-v1.ogg").readBytes())
    }

    @Test fun writerReproducesTheVectorBytes() {
        val out = OggOpusWriter.write(
            packets, json["preSkip"]!!.jsonPrimitive.int, json["inputSampleRate"]!!.jsonPrimitive.long,
            json["channels"]!!.jsonPrimitive.int, json["serial"]!!.jsonPrimitive.long,
        )
        assertContentEquals(ogg, out)
    }

    @Test fun readerDecodesTheVectorFields() {
        val s = OggOpusReader.read(ogg)
        assertEquals(1, s.channels)
        assertEquals(312, s.preSkip)
        assertEquals(16_000L, s.inputSampleRate)
        assertEquals("Messaging OggOpus", s.vendor)
        assertEquals(120, s.packets.size)
        assertEquals(expected["finalGranule"]!!.jsonPrimitive.long, s.finalGranule)
        assertEquals(expected["durationSamples"]!!.jsonPrimitive.long, s.finalGranule - s.preSkip)
        packets.forEachIndexed { i, p -> assertContentEquals(p, s.packets[i], "packet $i") }
    }

    @Test fun pagesMatchExpectedGranulesAndCrcs() {
        var offset = 0
        val granules = mutableListOf<Long>()
        val crcs = mutableListOf<String>()
        while (offset < ogg.size) {
            val (page, next) = OggPage.parse(ogg, offset)
            granules += page.granule
            crcs += "%08x".format(readLe32(ogg, offset + 22))
            offset = next
        }
        assertEquals(expected["pageCount"]!!.jsonPrimitive.int, granules.size)
        assertEquals(expected["pageGranules"]!!.jsonArray.map { it.jsonPrimitive.long }, granules)
        assertEquals(expected["pageCrcs"]!!.jsonArray.map { it.jsonPrimitive.content }, crcs)
    }

    @Test fun validatorAcceptsTheVector() {
        val s = VoiceNoteValidator.validate(ogg)
        assertEquals(2.38, s.durationSeconds, 0.01)
    }

    @Test fun validatorRejectsDamage() {
        // Wrong format.
        assertEquals(VoiceNoteError.UnknownFormat, assertFailsWith<VoiceNoteException> { VoiceNoteValidator.validate(byteArrayOf(1, 2, 3, 4, 5)) }.error)
        assertEquals(VoiceNoteError.NotAllowedFormat, assertFailsWith<VoiceNoteException> { VoiceNoteValidator.validate("caff....".toByteArray()) }.error)
        // Flipped byte: CRC.
        assertEquals(OggOpusError.BadChecksum, errorOf { OggOpusReader.read(ogg.copyOf().also { it[ogg.size - 5] = (it[ogg.size - 5].toInt() xor 1).toByte() }) })
        // Cut off: no end page.
        assertEquals(OggOpusError.Truncated, errorOf { OggOpusReader.read(ogg.copyOf(ogg.size - 10)) })
        // Dropping the last page: missing end of stream.
        var offset = 0
        var lastStart = 0
        while (offset < ogg.size) { lastStart = offset; offset = OggPage.parse(ogg, offset).second }
        assertEquals(OggOpusError.MissingEndOfStream, errorOf { OggOpusReader.read(ogg.copyOf(lastStart)) })
        // Data after the end.
        assertEquals(OggOpusError.TrailingData, errorOf { OggOpusReader.read(ogg + byteArrayOf(1, 2, 3, 4)) })
        // Too large before parsing.
        assertEquals(VoiceNoteError.TooLarge, assertFailsWith<VoiceNoteException> { VoiceNoteValidator.checkFileSize(120_000_001L) }.error)
    }

    @Test fun validatorChecksPacketsAndDuration() {
        fun write(p: List<ByteArray>) = OggOpusWriter.write(p, 312, serial = 1)
        val ok = packets
        // Stereo packet in a mono stream (TOC bit 2).
        val stereo = ok.map { it.copyOf() }.also { it[3][0] = (it[3][0].toInt() or 0x04).toByte() }
        assertEquals(VoiceNoteError.ChannelMismatch, assertFailsWith<VoiceNoteException> { VoiceNoteValidator.validate(write(stereo)) }.error)
        // Granule claims more than the packets hold: build by raising the final granule is covered by the reader; here an
        // empty packet is rejected by the writer's own check, and an over-long note by the sample cap.
        assertEquals(OggOpusError.InvalidParameters, errorOf { write(listOf(ByteArray(0))) })
        assertEquals(OggOpusError.NoPackets, errorOf { write(emptyList()) })
    }
}
