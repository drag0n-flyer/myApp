package org.example.app.messaging.media.ogg

/** Failures when writing or reading Ogg Opus (RFC 3533 container, RFC 7845 mapping). */
enum class OggOpusError {
    NoPackets, PacketTooLarge, InvalidParameters, Truncated, BadCapturePattern, UnsupportedVersion, BadChecksum,
    BadSequence, BadHeader, UnsupportedMapping, MissingEndOfStream, UnexpectedStream, TrailingData,
}

class OggOpusException(val error: OggOpusError) : Exception(error.name)

internal fun fail(e: OggOpusError): Nothing = throw OggOpusException(e)

/** The Ogg page checksum: CRC-32, polynomial 0x04C11DB7, initial value 0, no reflection, no final XOR. */
internal object OggCrc {
    private val table = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and Int.MIN_VALUE != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    fun checksum(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) crc = (crc shl 8) xor table[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc
    }
}

internal fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
internal fun le32(v: Long) = ByteArray(4) { (v ushr (8 * it)).toByte() }
internal fun le64(v: Long) = ByteArray(8) { (v ushr (8 * it)).toByte() }
internal fun readLe16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
internal fun readLe32(b: ByteArray, o: Int): Long = (0 until 4).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
internal fun readLe64(b: ByteArray, o: Int): Long = (0 until 8).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

/** One Ogg page (RFC 3533 section 6). */
internal class OggPage(
    val flags: Int,
    val granule: Long,
    val serial: Long,
    val sequence: Long,
    /** Lacing values: each is 255 (packet goes on) or less (packet ends here). */
    val segments: ByteArray,
    val body: ByteArray,
) {
    fun encoded(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(CAPTURE)
        out.write(0)
        out.write(flags)
        out.write(le64(granule))
        out.write(le32(serial))
        out.write(le32(sequence))
        out.write(ByteArray(4))
        out.write(segments.size)
        out.write(segments)
        out.write(body)
        val raw = out.toByteArray()
        System.arraycopy(le32(OggCrc.checksum(raw).toLong() and 0xFFFFFFFFL), 0, raw, 22, 4)
        return raw
    }

    companion object {
        val CAPTURE = byteArrayOf(0x4F, 0x67, 0x67, 0x53)
        const val HEADER_SIZE = 27
        const val CONTINUED = 0x01
        const val BEGIN = 0x02
        const val END = 0x04

        /** The lacing values for a packet of [count] bytes. A multiple of 255 ends with a 0 value. */
        fun lacing(count: Int): ByteArray = ByteArray(count / 255 + 1) { if (it < count / 255) 255.toByte() else (count % 255).toByte() }

        /** Parses the page that starts at [offset]; returns it and the offset after it. */
        fun parse(data: ByteArray, offset: Int): Pair<OggPage, Int> {
            if (data.size - offset < HEADER_SIZE) {
                val have = minOf(4, data.size - offset)
                if (have > 0 && !(0 until have).all { data[offset + it] == CAPTURE[it] }) fail(OggOpusError.BadCapturePattern)
                fail(OggOpusError.Truncated)
            }
            if (!(0 until 4).all { data[offset + it] == CAPTURE[it] }) fail(OggOpusError.BadCapturePattern)
            if (data[offset + 4].toInt() != 0) fail(OggOpusError.UnsupportedVersion)
            val count = data[offset + 26].toInt() and 0xFF
            val tableEnd = offset + HEADER_SIZE + count
            if (data.size < tableEnd) fail(OggOpusError.Truncated)
            val segments = data.copyOfRange(offset + HEADER_SIZE, tableEnd)
            val bodySize = segments.sumOf { it.toInt() and 0xFF }
            val end = tableEnd + bodySize
            if (data.size < end) fail(OggOpusError.Truncated)

            val raw = data.copyOfRange(offset, end)
            val stored = readLe32(raw, 22)
            System.arraycopy(ByteArray(4), 0, raw, 22, 4)
            if ((OggCrc.checksum(raw).toLong() and 0xFFFFFFFFL) != stored) fail(OggOpusError.BadChecksum)
            return OggPage(
                flags = data[offset + 5].toInt() and 0xFF,
                granule = readLe64(data, offset + 6),
                serial = readLe32(data, offset + 14),
                sequence = readLe32(data, offset + 18),
                segments = segments,
                body = data.copyOfRange(tableEnd, end),
            ) to end
        }
    }
}

/** Facts read from the first byte of an Opus packet (RFC 6716 section 3.1). */
object OpusPacket {
    /** How many 48 kHz samples the packet holds, or null when the packet is empty or malformed. */
    fun samples48k(packet: ByteArray): Int? {
        if (packet.isEmpty()) return null
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frame = when (config) {
            in 0..11 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
            in 12..15 -> if (config % 2 == 0) 480 else 960
            else -> intArrayOf(120, 240, 480, 960)[config % 4]
        }
        val frames = when (toc and 0x03) {
            0 -> 1
            1, 2 -> 2
            else -> {
                if (packet.size < 2) return null
                val n = packet[1].toInt() and 0x3F
                if (n < 1) return null
                n
            }
        }
        val total = frame * frames
        return if (total <= 5_760) total else null
    }
}

/** An Ogg Opus file taken apart: header fields and the audio packets. */
class OggOpusStream(
    val channels: Int,
    /** Samples at 48 kHz to discard from the start of the decoded audio. */
    val preSkip: Int,
    val inputSampleRate: Long,
    val outputGain: Int,
    val vendor: String,
    val packets: List<ByteArray>,
    /** Granule position of the last page (48 kHz samples, including the pre-skip). */
    val finalGranule: Long,
) {
    val durationSeconds: Double get() = maxOf(0L, finalGranule - preSkip) / 48_000.0
}

/** Reads an Ogg Opus file strictly: any damage (bad checksum, gap, cut-off, missing end) is an error. */
object OggOpusReader {
    fun read(data: ByteArray): OggOpusStream {
        var offset = 0
        var serial: Long? = null
        var nextSequence = 0L
        var partial = java.io.ByteArrayOutputStream()
        val packets = ArrayList<ByteArray>()
        var finalGranule = 0L
        var ended = false
        var pageIndex = 0

        while (offset < data.size) {
            if (ended) fail(OggOpusError.TrailingData)
            val (page, next) = OggPage.parse(data, offset)
            offset = next

            if (serial == null) {
                if (page.flags and OggPage.BEGIN == 0) fail(OggOpusError.BadHeader)
                serial = page.serial
            } else {
                if (page.serial != serial) fail(OggOpusError.UnexpectedStream)
                if (page.flags and OggPage.BEGIN != 0) fail(OggOpusError.UnexpectedStream)
            }
            if (page.sequence != nextSequence) fail(OggOpusError.BadSequence)
            nextSequence = (nextSequence + 1) and 0xFFFFFFFFL

            val continues = page.flags and OggPage.CONTINUED != 0
            if (continues != (partial.size() > 0)) fail(OggOpusError.BadSequence)

            var cursor = 0
            for (l in page.segments) {
                val lace = l.toInt() and 0xFF
                partial.write(page.body, cursor, lace)
                cursor += lace
                if (lace < 255) {
                    packets.add(partial.toByteArray())
                    partial = java.io.ByteArrayOutputStream()
                }
            }
            // RFC 7845 section 3: OpusHead and OpusTags are each alone on their page.
            if (pageIndex < 2 && (packets.size != pageIndex + 1 || partial.size() > 0)) fail(OggOpusError.BadHeader)
            pageIndex++
            if (page.granule >= 0) finalGranule = page.granule
            if (page.flags and OggPage.END != 0) {
                ended = true
                if (partial.size() > 0) fail(OggOpusError.Truncated)
            }
        }
        if (serial == null) fail(OggOpusError.Truncated)
        if (!ended) fail(OggOpusError.MissingEndOfStream)
        if (packets.size < 2) fail(OggOpusError.Truncated)

        val head = packets[0]
        if (head.size < 19 || !head.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray())) fail(OggOpusError.BadHeader)
        if ((head[8].toInt() and 0xFF) shr 4 != 0) fail(OggOpusError.UnsupportedVersion)
        val channels = head[9].toInt() and 0xFF
        if (head[18].toInt() != 0 || channels !in 1..2) fail(OggOpusError.UnsupportedMapping)

        val tags = packets[1]
        if (tags.size < 16 || !tags.copyOfRange(0, 8).contentEquals("OpusTags".toByteArray())) fail(OggOpusError.BadHeader)
        val declared = readLe32(tags, 8)
        if (declared > tags.size - 12) fail(OggOpusError.BadHeader)
        val vendor = String(tags, 12, declared.toInt(), Charsets.UTF_8)

        return OggOpusStream(
            channels = channels,
            preSkip = readLe16(head, 10),
            inputSampleRate = readLe32(head, 12),
            outputGain = readLe16(head, 16).toShort().toInt(),
            vendor = vendor,
            packets = packets.drop(2),
            finalGranule = finalGranule,
        )
    }
}

/** Wraps Opus packets in an Ogg stream as RFC 7845 describes. Granule positions count 48 kHz samples. */
object OggOpusWriter {
    const val VENDOR = "Messaging OggOpus"
    private const val MAX_PACKETS_PER_PAGE = 50

    fun write(
        packets: List<ByteArray>,
        preSkip: Int,
        inputSampleRate: Long = 16_000,
        channels: Int = 1,
        serial: Long = java.security.SecureRandom().nextInt().toLong() and 0xFFFFFFFFL,
    ): ByteArray {
        if (packets.isEmpty()) fail(OggOpusError.NoPackets)
        if (preSkip !in 0..0xFFFF || channels !in 1..2) fail(OggOpusError.InvalidParameters)

        var granule = 0L
        val out = java.io.ByteArrayOutputStream()
        var sequence = 0L
        fun emit(p: OggPage) { out.write(p.encoded()); sequence++ }

        val head = java.io.ByteArrayOutputStream().apply {
            write("OpusHead".toByteArray()); write(1); write(channels); write(le16(preSkip))
            write(le32(inputSampleRate)); write(le16(0)); write(0)
        }.toByteArray()
        emit(OggPage(OggPage.BEGIN, 0, serial, sequence, OggPage.lacing(head.size), head))

        val vendor = VENDOR.toByteArray()
        val tags = java.io.ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray()); write(le32(vendor.size.toLong())); write(vendor); write(le32(0))
        }.toByteArray()
        emit(OggPage(0, 0, serial, sequence, OggPage.lacing(tags.size), tags))

        var segments = java.io.ByteArrayOutputStream()
        var segCount = 0
        var body = java.io.ByteArrayOutputStream()
        var inPage = 0
        var pageGranule = granule
        fun flush(last: Boolean) {
            emit(OggPage(if (last) OggPage.END else 0, pageGranule, serial, sequence, segments.toByteArray(), body.toByteArray()))
            segments = java.io.ByteArrayOutputStream(); segCount = 0; body = java.io.ByteArrayOutputStream(); inPage = 0
        }
        for (packet in packets) {
            val samples = OpusPacket.samples48k(packet) ?: fail(OggOpusError.InvalidParameters)
            val lacing = OggPage.lacing(packet.size)
            if (lacing.size > 255) fail(OggOpusError.PacketTooLarge)
            if (segCount + lacing.size > 255 || inPage >= MAX_PACKETS_PER_PAGE) flush(false)
            segments.write(lacing); segCount += lacing.size
            body.write(packet)
            inPage++
            granule += samples
            pageGranule = granule
        }
        flush(true)
        return out.toByteArray()
    }
}
