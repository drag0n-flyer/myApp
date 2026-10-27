import Foundation

/// An Ogg Opus file taken apart: header fields and the audio packets.
public struct OggOpusStream: Equatable, Sendable {
    public var channels: Int
    /// Samples at 48 kHz to discard from the start of the decoded audio.
    public var preSkip: Int
    public var inputSampleRate: UInt32
    /// Q7.8 fixed-point gain in dB from `OpusHead`; 0 for our own files.
    public var outputGain: Int
    public var vendor: String
    public var packets: [[UInt8]]
    /// Granule position of the last page (48 kHz samples, including the pre-skip).
    public var finalGranule: Int64

    /// Playing time in seconds.
    public var duration: Double { Double(max(0, finalGranule - Int64(preSkip))) / 48_000 }
}

/// Reads an Ogg Opus file strictly: any damage (bad checksum, gap, cut-off, missing end) is an error.
public enum OggOpusReader {
    public static func read(_ data: [UInt8]) throws -> OggOpusStream {
        var offset = 0
        var serial: UInt32?
        var nextSequence: UInt32 = 0
        var partial: [UInt8] = []
        var packets: [[UInt8]] = []
        var finalGranule: Int64 = 0
        var ended = false
        var pageIndex = 0

        while offset < data.count {
            guard !ended else { throw OggOpusError.trailingData }
            let (page, next) = try OggPage.parse(data, at: offset)
            offset = next

            if serial == nil {
                guard page.flags & OggPage.beginOfStream != 0 else { throw OggOpusError.badHeader }
                serial = page.serial
            } else {
                guard page.serial == serial else { throw OggOpusError.unexpectedStream }
                guard page.flags & OggPage.beginOfStream == 0 else { throw OggOpusError.unexpectedStream }
            }
            guard page.sequence == nextSequence else { throw OggOpusError.badSequence }
            nextSequence &+= 1

            let continues = page.flags & OggPage.continued != 0
            guard continues == !partial.isEmpty else { throw OggOpusError.badSequence }

            var cursor = 0
            for lace in page.segments {
                partial += page.body[cursor..<cursor + Int(lace)]
                cursor += Int(lace)
                if lace < 255 { packets.append(partial); partial = [] }
            }
            // RFC 7845 section 3: OpusHead and OpusTags are each alone on their page.
            if pageIndex < 2, packets.count != pageIndex + 1 || !partial.isEmpty { throw OggOpusError.badHeader }
            pageIndex += 1
            if page.granule >= 0 { finalGranule = page.granule }
            if page.flags & OggPage.endOfStream != 0 {
                ended = true
                guard partial.isEmpty else { throw OggOpusError.truncated }
            }
        }
        guard serial != nil else { throw OggOpusError.truncated }
        guard ended else { throw OggOpusError.missingEndOfStream }
        guard packets.count >= 2 else { throw OggOpusError.truncated }

        let head = packets[0]
        guard head.count >= 19, Array(head[0..<8]) == Array("OpusHead".utf8) else { throw OggOpusError.badHeader }
        guard head[8] >> 4 == 0 else { throw OggOpusError.unsupportedVersion }
        let channels = Int(head[9])
        guard head[18] == 0, (1...2).contains(channels) else { throw OggOpusError.unsupportedMapping }

        let tags = packets[1]
        guard tags.count >= 16, Array(tags[0..<8]) == Array("OpusTags".utf8) else { throw OggOpusError.badHeader }
        let declaredVendor = readLE32(tags, at: 8)
        guard declaredVendor <= UInt32(tags.count - 12) else { throw OggOpusError.badHeader }
        let vendorLength = Int(declaredVendor)
        let vendor = String(decoding: tags[12..<12 + vendorLength], as: UTF8.self)

        return OggOpusStream(
            channels: channels,
            preSkip: Int(readLE16(head, at: 10)),
            inputSampleRate: readLE32(head, at: 12),
            outputGain: Int(Int16(bitPattern: readLE16(head, at: 16))),
            vendor: vendor,
            packets: Array(packets.dropFirst(2)),
            finalGranule: finalGranule)
    }
}

/// Tells the voice-note containers apart by their first bytes: Ogg Opus (current) or CAF (old
/// recordings in local storage. Only `.ogg` may come from the network (see `VoiceNoteValidator`)).
public enum VoiceContainer: Equatable, Sendable {
    case ogg, caf

    public static func detect(prefix: [UInt8]) -> VoiceContainer? {
        if prefix.starts(with: Array("OggS".utf8)) { return .ogg }
        if prefix.starts(with: Array("caff".utf8)) { return .caf }
        return nil
    }
}
