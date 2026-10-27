import Foundation

/// Wraps Opus packets in an Ogg stream as RFC 7845 describes: an `OpusHead` page, an `OpusTags`
/// page, then audio pages. Granule positions count 48 kHz samples including the pre-skip.
public enum OggOpusWriter {
    public static let vendor = "Messaging OggOpus"
    /// A page is closed after this many packets (1 second of 20 ms packets) or when its 255
    /// lacing slots are full, whichever comes first.
    public static let maxPacketsPerPage = 50

    /// - Parameters:
    ///   - packets: Opus packets in order, each at least one byte. They are never split across pages.
    ///   - preSkip: samples at 48 kHz the decoder discards first (the encoder's look-ahead).
    ///   - inputSampleRate: the rate the audio was recorded at; informational (`OpusHead`).
    public static func write(
        packets: [[UInt8]], preSkip: Int, inputSampleRate: UInt32 = 16_000, channels: Int = 1,
        serial: UInt32 = UInt32.random(in: .min ... .max)
    ) throws -> [UInt8] {
        guard !packets.isEmpty else { throw OggOpusError.noPackets }
        guard (0...Int(UInt16.max)).contains(preSkip), (1...2).contains(channels) else { throw OggOpusError.invalidParameters }

        // Granule position: 48 kHz samples the decoder has produced so far. The first packet's
        // output includes the pre-skip, so playing time is granule - preSkip (RFC 7845 section 4).
        var granule: Int64 = 0
        var out: [UInt8] = []
        var sequence: UInt32 = 0

        func emit(_ page: OggPage) { out.append(contentsOf: page.encoded()); sequence += 1 }

        // OpusHead: 19 bytes, channel mapping family 0, output gain 0 dB.
        var head = Array("OpusHead".utf8)
        head += [1, UInt8(channels)]
        head += le16(UInt16(preSkip))
        head += le32(inputSampleRate)
        head += le16(0)
        head += [0]
        emit(OggPage(flags: OggPage.beginOfStream, granule: 0, serial: serial, sequence: sequence,
                     segments: OggPage.lacing(forPacketOf: head.count), body: head))

        // OpusTags: vendor string and no user comments.
        let vendorBytes = Array(vendor.utf8)
        var tags = Array("OpusTags".utf8)
        tags += le32(UInt32(vendorBytes.count)) + vendorBytes + le32(0)
        emit(OggPage(flags: 0, granule: 0, serial: serial, sequence: sequence,
                     segments: OggPage.lacing(forPacketOf: tags.count), body: tags))

        var segments: [UInt8] = []
        var body: [UInt8] = []
        var inPage = 0
        var pageGranule = granule
        func flush(last: Bool) {
            emit(OggPage(flags: last ? OggPage.endOfStream : 0, granule: pageGranule, serial: serial, sequence: sequence,
                         segments: segments, body: body))
            segments = []; body = []; inPage = 0
        }

        for packet in packets {
            guard let samples = OpusPacket.samples48k(packet) else { throw OggOpusError.invalidParameters }
            let lacing = OggPage.lacing(forPacketOf: packet.count)
            guard lacing.count <= 255 else { throw OggOpusError.packetTooLarge }
            if segments.count + lacing.count > 255 || inPage >= maxPacketsPerPage { flush(last: false) }
            segments += lacing
            body += packet
            inPage += 1
            granule += Int64(samples)
            pageGranule = granule
        }
        flush(last: true)
        return out
    }
}
