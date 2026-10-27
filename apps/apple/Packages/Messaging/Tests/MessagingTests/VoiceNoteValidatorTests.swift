import Foundation
import Testing
@testable import Messaging

/// Files from other people are untrusted: limits and format checks run before any decoding.
@Suite("VoiceNoteValidator") struct VoiceNoteValidatorTests {
    private func file(_ packets: [[UInt8]], preSkip: Int = 312, channels: Int = 1) throws -> [UInt8] {
        try OggOpusWriter.write(packets: packets, preSkip: preSkip, channels: channels, serial: 1)
    }

    @Test func limitsAreTheDocumentedNumbers() {
        #expect(VoiceNoteLimits.maxDuration == 1_800)
        #expect(VoiceNoteLimits.maxSamples == 86_400_000)
        // 510 kbit/s for 30 minutes is 114 750 000 bytes; the cap adds room for Ogg overhead.
        #expect(VoiceNoteLimits.maxFileBytes == 120_000_000)
    }

    @Test func acceptsOwnFile() throws {
        let data = try file(syntheticPackets(count: 50))
        guard case .ogg(let stream) = try VoiceNoteValidator.validate(data, origin: .received) else { Issue.record("not ogg"); return }
        #expect(stream.packets.count == 50)
    }

    @Test func acceptsLibopusFile() throws {
        // ffmpeg's granule is trimmed below the packet sum by the encoder padding.
        let data = [UInt8](Data(base64Encoded: ffmpegFileB64)!)
        _ = try VoiceNoteValidator.validate(data, origin: .received)
    }

    @Test func rejectsHugeFileBeforeParsing() {
        #expect(throws: VoiceNoteError.tooLarge) { try VoiceNoteValidator.checkFileSize(VoiceNoteLimits.maxFileBytes + 1) }
        #expect(throws: Never.self) { try VoiceNoteValidator.checkFileSize(VoiceNoteLimits.maxFileBytes) }
        #expect(throws: VoiceNoteError.tooLarge) { try VoiceNoteValidator.checkFileSize(-1) }
    }

    @Test func rejectsOversizedDeclaredDuration() throws {
        // A tiny file whose last granule claims 31 minutes.
        var data = try file(syntheticPackets(count: 3))
        let last = try lastPageOffset(data)
        let claimed = Int64(48_000 * 1_860)
        data.replaceSubrange((last + 6)..<(last + 14), with: le64(UInt64(claimed)))
        data = resealed(data, pageAt: last)
        #expect(throws: VoiceNoteError.tooLong) { try VoiceNoteValidator.validate(data, origin: .received) }
    }

    @Test func rejectsPacketsThatAddUpToMoreThanTheCap() throws {
        // 2-byte packets of 120 ms each: 15 100 of them are 1 812 s, over the cap.
        let big = [[UInt8]](repeating: [0x1B, 0x02], count: 15_100)   // config 3 (SILK NB 60 ms), code 3, 2 frames = 120 ms
        #expect(OpusPacket.samples48k(big[0]) == 5_760)
        let data = try file(big)
        #expect(throws: VoiceNoteError.tooLong) { try VoiceNoteValidator.validate(data, origin: .received) }
    }

    @Test func rejectsEmptyPacket() throws {
        let data = try rawFile(packets: [[0xF8, 1], [], [0xF8, 1]])
        #expect(throws: VoiceNoteError.emptyPacket) { try VoiceNoteValidator.validate(data, origin: .received) }
    }

    @Test func rejectsInvalidTocAndStereoInMono() throws {
        let bad = try rawFile(packets: [[0xFB]])   // code 3 without a frame-count byte
        #expect(throws: VoiceNoteError.invalidPacket) { try VoiceNoteValidator.validate(bad, origin: .received) }
        let stereo = try rawFile(packets: [[0xFC, 1]])   // stereo bit set, header says mono
        #expect(throws: VoiceNoteError.channelMismatch) { try VoiceNoteValidator.validate(stereo, origin: .received) }
        let two = try rawFile(packets: [[0xFC, 1]], channels: 2)
        #expect(throws: Never.self) { try VoiceNoteValidator.validate(two, origin: .received) }
    }

    @Test func rejectsGranuleThatDisagreesWithPackets() throws {
        var data = try file(syntheticPackets(count: 3))   // 2 880 samples
        let last = try lastPageOffset(data)
        data.replaceSubrange((last + 6)..<(last + 14), with: le64(UInt64(100_000)))
        data = resealed(data, pageAt: last)
        #expect(throws: VoiceNoteError.durationMismatch) { try VoiceNoteValidator.validate(data, origin: .received) }
    }

    @Test func receivedFilesMustBeOgg() throws {
        let caf = Array("caff".utf8) + [0, 1, 0, 0, 0, 0, 0, 0]
        #expect(throws: VoiceNoteError.notAllowedFormat) { try VoiceNoteValidator.validate(caf, origin: .received) }
        guard case .caf = try VoiceNoteValidator.validate(caf, origin: .local) else { Issue.record("not caf"); return }
        #expect(throws: VoiceNoteError.unknownFormat) { try VoiceNoteValidator.validate([1, 2, 3, 4, 5], origin: .received) }
        #expect(throws: VoiceNoteError.unknownFormat) { try VoiceNoteValidator.validate([], origin: .local) }
    }

    @Test func localCafLengthIsCapped() {
        #expect(throws: Never.self) { try VoiceNoteValidator.checkCAFLength(frames: 16_000 * 1_800, sampleRate: 16_000) }
        #expect(throws: VoiceNoteError.tooLong) { try VoiceNoteValidator.checkCAFLength(frames: 16_000 * 1_801, sampleRate: 16_000) }
        #expect(throws: VoiceNoteError.tooLong) { try VoiceNoteValidator.checkCAFLength(frames: 10, sampleRate: 0) }
    }

    @Test func ogginessOfDamageStillThrowsOggErrors() throws {
        let data = try file(syntheticPackets(count: 5))
        #expect(throws: OggOpusError.self) { try VoiceNoteValidator.validate(Array(data.dropLast(3)), origin: .received) }
    }

    // MARK: helpers

    /// An Ogg Opus file with the given packets as-is (the writer would refuse an empty one).
    private func rawFile(packets: [[UInt8]], channels: Int = 1) throws -> [UInt8] {
        var head: [UInt8] = Array("OpusHead".utf8)
        head += [1, UInt8(channels)]; head += le16(312); head += le32(16_000); head += le16(0); head += [0]
        var tags: [UInt8] = Array("OpusTags".utf8)
        tags += le32(0); tags += le32(0)
        var body: [UInt8] = [], segs: [UInt8] = []
        for p in packets { body += p; segs += OggPage.lacing(forPacketOf: p.count) }
        return OggPage(flags: OggPage.beginOfStream, granule: 0, serial: 1, sequence: 0,
                       segments: OggPage.lacing(forPacketOf: head.count), body: head).encoded()
            + OggPage(flags: 0, granule: 0, serial: 1, sequence: 1,
                      segments: OggPage.lacing(forPacketOf: tags.count), body: tags).encoded()
            + OggPage(flags: OggPage.endOfStream, granule: 480, serial: 1, sequence: 2, segments: segs, body: body).encoded()
    }

    private func lastPageOffset(_ data: [UInt8]) throws -> Int {
        var offset = 0, last = 0
        while offset < data.count { last = offset; offset = try OggPage.parse(data, at: offset).1 }
        return last
    }

    /// Recomputes the checksum of the page at `offset` after the test edited its header.
    private func resealed(_ data: [UInt8], pageAt offset: Int) -> [UInt8] {
        var out = data
        let n = Int(out[offset + 26])
        let end = offset + 27 + n + out[(offset + 27)..<(offset + 27 + n)].reduce(0) { $0 + Int($1) }
        out.replaceSubrange((offset + 22)..<(offset + 26), with: [0, 0, 0, 0])
        out.replaceSubrange((offset + 22)..<(offset + 26), with: le32(OggCRC.checksum(Array(out[offset..<end]))))
        return out
    }
}
