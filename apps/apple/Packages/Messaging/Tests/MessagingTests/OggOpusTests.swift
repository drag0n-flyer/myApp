import Foundation
import Testing
@testable import Messaging

/// A real file: 0.3 s of 440 Hz, mono, 16 kHz input, made by ffmpeg with libopus (libogg did the
/// paging). It is the independent reference for the page checksum, the header layout and the reader.
let ffmpegFileB64 = "T2dnUwACAAAAAAAAAABBJWEmAAAAAGiqv5EBE09wdXNIZWFkAQE4AYA+AAAAAABPZ2dTAAAAAAAAAAAAAEElYSYBAAAA/s47xAE9T3B1c1RhZ3MMAAAATGF2ZjYxLjcuMTAzAQAAAB0AAABlbmNvZGVyPUxhdmM2MS4xOS4xMDEgbGlib3B1c09nZ1MABHg5AAAAAAAAQSVhJgIAAABmRWjTED8pKzEyKjQvJywtKiYtKRtIggFdbH5A5gAAC9PYYLZXBl2HORjlhtlT/QW+gyii4kNZicPPXfM07o5qEynO6ZO4NgGLLtuOBwPa+sczkmBIofeXrJiFA1wmChaAH061cgLfGstK6ehyTE5pvT9SidZ+Tjgy8rMRY0ibKx91nPxLOkXWd+Q7svIBcyzM+5ouPj5xBY1LVi39PL3Rmk7TTNRLlwFImwYysJKgTNY9ToVLHOcCrPPyAPu0PPICD+YJiR+vchZQ3qazzfIW7+rJlftCowYoSJqy33Wc/EkO2Lq+Ka3jGV51yGvYOtq2OUUAFUAel5F/8HQjRmlvYwsGU0IKXRB2rkBImrKSUUUAzlAVhU5HP85PHPska1lyYPITKYa8RETeaMLzGD+Q3pN2tCRImrLfdZz8SUjzMrkr0uhGFvqvworWsya7kWhZtibeGPQ+ZLMjsLBtQE/MddGMyJYMFvLaSJqy33Wc/Es6RdO0VUs6ynp10Y7raM1soKafS5wgG5mnzuY6WZ8BSFLGn9HsaqJImrLfe82BSlW3FtzJ8aLWN1NXGOeAQSWEEYcq/FkBJdfPVCdBg2BImthMmpe/qq/Aoa5mO0GuE1Vgvs0/CB0BFQWOVcGYBDTZDeSrEuyk8D7RGEibKtJTqkh8pFm7GZIEstQpVsWBoeXHaXt3FxX+wdbRyEOVrImZFjJPdJBd4EibKx91nPxJSiI2gzf4vfxdv8eou+8HLqeKBWUtCp+2L6IS+ufojhmHYEibKx91nPxLOkV5vDt+H1CfGH0ZhjKwB5LNEPOwoMkQnQqqH6qISJsq0lOqROIsskIVqAK1eg+nSqBRCTOAvKNEln8foy1llvacG5NngQd1lTmQSJsrH3Wc/EkzslW6pAl3pmM/OxOPiSqNH3woeA1U61c0C/uXGyeaHkBIBejXSjogw39kDZVLmnbbOkie+r1CvriK5Lw="
private let ffmpegFile = [UInt8](Data(base64Encoded: ffmpegFileB64)!)

/// Deterministic fake packets: valid Opus TOC bytes, filler payload (not decodable audio).
func syntheticPackets(count: Int, sizes: [Int: Int] = [:]) -> [[UInt8]] {
    (0..<count).map { i in
        let size = sizes[i] ?? 20
        return [0xF8] + (1..<max(size, 1)).map { UInt8(truncatingIfNeeded: (i &* 31 &+ $0) % 251) }
    }
}

@Suite("OggOpus") struct OggOpusTests {
    // MARK: checksum

    @Test func checksumMatchesKnownPage() throws {
        // First page (OpusHead) of the ffmpeg file; its stored checksum bytes are 68 aa bf 91.
        var page = Array(ffmpegFile[0..<47])
        #expect(Array(page[22..<26]) == [0x68, 0xAA, 0xBF, 0x91])
        page.replaceSubrange(22..<26, with: [0, 0, 0, 0])
        #expect(OggCRC.checksum(page) == 0x91BF_AA68)
    }

    @Test func checksumCatalogueCheckValue() {
        // CRC-32 with poly 0x04C11DB7, init 0, no reflection, no xorout: check value of "123456789".
        #expect(OggCRC.checksum(Array("123456789".utf8)) == 0x89A1_897F)
    }

    // MARK: headers (RFC 7845 section 5)

    @Test func headersMatchRFCByHand() throws {
        let file = try OggOpusWriter.write(
            packets: syntheticPackets(count: 3), preSkip: 312, inputSampleRate: 16_000, channels: 1, serial: 0xA1B2_C3D4)
        // Page 1: OpusHead alone, beginning of stream, granule 0, sequence 0.
        #expect(Array(file[0..<4]) == Array("OggS".utf8))
        #expect(file[4] == 0)                  // version
        #expect(file[5] == 0x02)               // BOS
        #expect(Array(file[6..<14]) == [UInt8](repeating: 0, count: 8))
        #expect(Array(file[14..<18]) == [0xD4, 0xC3, 0xB2, 0xA1])   // serial, little-endian
        #expect(Array(file[18..<22]) == [0, 0, 0, 0])
        #expect(file[26] == 1)                 // one segment
        #expect(file[27] == 19)                // OpusHead is 19 bytes
        let head = Array(file[28..<47])
        #expect(Array(head[0..<8]) == Array("OpusHead".utf8))
        #expect(head[8] == 1)                  // version
        #expect(head[9] == 1)                  // channels
        #expect(Array(head[10..<12]) == [0x38, 0x01])           // pre-skip 312
        #expect(Array(head[12..<16]) == [0x80, 0x3E, 0, 0])     // 16000 Hz
        #expect(Array(head[16..<18]) == [0, 0])                 // output gain
        #expect(head[18] == 0)                 // mapping family 0
        // Page 2: OpusTags, not BOS, granule 0, sequence 1.
        let p2 = 47
        #expect(file[p2 + 5] == 0)
        #expect(Array(file[(p2 + 6)..<(p2 + 14)]) == [UInt8](repeating: 0, count: 8))
        #expect(file[p2 + 18] == 1)
        let tagsLen = Int(file[p2 + 27])
        let tags = Array(file[(p2 + 28)..<(p2 + 28 + tagsLen)])
        #expect(Array(tags[0..<8]) == Array("OpusTags".utf8))
        let vendorLen = Int(tags[8])
        #expect(Array(tags[9..<12]) == [0, 0, 0])
        #expect(String(decoding: tags[12..<(12 + vendorLen)], as: UTF8.self) == OggOpusWriter.vendor)
        #expect(Array(tags[(12 + vendorLen)...]) == [0, 0, 0, 0])   // zero user comments
        #expect(tags.count == 12 + vendorLen + 4)
    }

    // MARK: granule and pre-skip

    @Test func granuleAndPreSkip() throws {
        // 100 packets of 20 ms = 96 000 samples; pre-skip 312 is not added to the granule.
        let packets = syntheticPackets(count: 100)
        let file = try OggOpusWriter.write(packets: packets, preSkip: 312, serial: 7)
        let pages = try allPages(file)
        #expect(pages.map(\.granule) == [0, 0, 48_000, 96_000])   // head, tags, 50 + 50 packets
        #expect(pages.last!.flags & OggPage.endOfStream != 0)
        #expect(pages.dropLast().allSatisfy { $0.flags & OggPage.endOfStream == 0 })
        let stream = try OggOpusReader.read(file)
        #expect(stream.preSkip == 312)
        #expect(stream.finalGranule == 96_000)
        #expect(abs(stream.duration - (96_000.0 - 312) / 48_000) < 1e-9)
    }

    @Test func mixedPacketDurations() throws {
        // 10 ms (CELT FB, toc 0xF0), 20 ms (0xF8), 60 ms SILK WB (toc 0x58 = config 11), 2 frames (code 1).
        let packets: [[UInt8]] = [[0xF0, 1], [0xF8, 1], [0x58, 1], [0xF9, 1, 2]]
        #expect(packets.map { OpusPacket.samples48k($0) } == [480, 960, 2_880, 1_920])
        #expect(OpusPacket.samples48k([]) == nil)
        #expect(OpusPacket.samples48k([0xFB]) == nil)             // code 3 needs a count byte
        let stream = try OggOpusReader.read(OggOpusWriter.write(packets: packets, preSkip: 0, serial: 1))
        #expect(stream.finalGranule == 480 + 960 + 2_880 + 1_920)
    }

    // MARK: round trip and lacing

    @Test func roundTripReturnsIdenticalPackets() throws {
        let packets = syntheticPackets(count: 40)
        let file = try OggOpusWriter.write(packets: packets, preSkip: 312, inputSampleRate: 16_000, serial: 99)
        let stream = try OggOpusReader.read(file)
        #expect(stream.packets == packets)
        #expect(stream.channels == 1)
        #expect(stream.inputSampleRate == 16_000)
        #expect(stream.outputGain == 0)
        #expect(stream.vendor == OggOpusWriter.vendor)
    }

    @Test func longNoteSplitsAcrossPagesWithLacing() throws {
        // Sizes around the 255 boundary: 254, 255 (needs a 0 terminator), 256, 510, 1275 (largest).
        let sizes = [10: 254, 11: 255, 12: 256, 13: 510, 14: 1_275, 15: 765, 16: 1]
        let packets = syntheticPackets(count: 400, sizes: sizes)
        let file = try OggOpusWriter.write(packets: packets, preSkip: 312, serial: 5)
        let pages = try allPages(file)
        #expect(pages.count == 2 + 8)                  // 400 packets / 50 per page
        for page in pages {
            #expect(page.segments.count <= 255)
            #expect(page.body.count == page.segments.reduce(0) { $0 + Int($1) })
        }
        #expect(pages.map(\.sequence) == (0..<UInt32(pages.count)).map { $0 })
        // 255 bytes -> [255, 0]; 510 -> [255, 255, 0]; 1275 -> five 255 and a 0.
        #expect(OggPage.lacing(forPacketOf: 255) == [255, 0])
        #expect(OggPage.lacing(forPacketOf: 510) == [255, 255, 0])
        #expect(OggPage.lacing(forPacketOf: 1_275) == [255, 255, 255, 255, 255, 0])
        #expect(OggPage.lacing(forPacketOf: 256) == [255, 1])
        #expect(try OggOpusReader.read(file).packets == packets)
    }

    @Test func pageClosesWhenLacingSlotsRunOut() throws {
        // 50 packets of 1275 bytes need 6 slots each: 300 > 255, so pages hold 42 packets.
        let packets = syntheticPackets(count: 100, sizes: Dictionary(uniqueKeysWithValues: (0..<100).map { ($0, 1_275) }))
        let file = try OggOpusWriter.write(packets: packets, preSkip: 0, serial: 5)
        let pages = try allPages(file)
        #expect(pages.dropFirst(2).allSatisfy { $0.segments.count <= 255 })
        #expect(pages.count > 4)
        #expect(try OggOpusReader.read(file).packets == packets)
    }

    @Test func readerJoinsPacketContinuedOnNextPage() throws {
        // Other writers split packets across pages; ours does not, so build such a file by hand.
        let serial: UInt32 = 3
        let head = try allPages(OggOpusWriter.write(packets: syntheticPackets(count: 1), preSkip: 0, serial: serial))
        let big = syntheticPackets(count: 1, sizes: [0: 600])[0]
        let part1 = Array(big[0..<510]), part2 = Array(big[510...])
        let a = OggPage(flags: 0, granule: -1, serial: serial, sequence: 2, segments: [255, 255], body: part1)
        let b = OggPage(flags: OggPage.continued | OggPage.endOfStream, granule: 960, serial: serial, sequence: 3,
                        segments: [UInt8(part2.count)], body: part2)
        let file = head[0].encoded() + head[1].encoded() + a.encoded() + b.encoded()
        let stream = try OggOpusReader.read(file)
        #expect(stream.packets == [big])
        #expect(stream.finalGranule == 960)
    }

    // MARK: real-world file

    @Test func readsFileFromLibopus() throws {
        let stream = try OggOpusReader.read(ffmpegFile)
        #expect(stream.preSkip == 312)
        #expect(stream.inputSampleRate == 16_000)
        #expect(stream.channels == 1)
        #expect(stream.vendor == "Lavf61.7.103")
        #expect(!stream.packets.isEmpty)
        // Durations from the TOC bytes agree with the granule position.
        let samples = stream.packets.compactMap(OpusPacket.samples48k).reduce(0, +)
        #expect(Int64(samples) >= stream.finalGranule - 960)
        #expect(abs(stream.duration - 0.3) < 0.03)
        // Our writer reproduces the same headers' fields and packets.
        let again = try OggOpusReader.read(OggOpusWriter.write(
            packets: stream.packets, preSkip: stream.preSkip, inputSampleRate: stream.inputSampleRate, serial: 1))
        #expect(again.packets == stream.packets)
    }

    // MARK: damage

    @Test func truncatedFilesFailCleanly() throws {
        let file = try OggOpusWriter.write(packets: syntheticPackets(count: 60), preSkip: 312, serial: 5)
        #expect(throws: OggOpusError.self) { try OggOpusReader.read([]) }
        for cut in [1, 3, 20, 30, 46, 47, 60, file.count / 2, file.count - 1] {
            #expect(throws: OggOpusError.self, "cut at \(cut)") { try OggOpusReader.read(Array(file[0..<cut])) }
        }
        // Cut exactly at a page boundary: the end-of-stream page is missing.
        let pages = try allPages(file)
        let boundary = pages.dropLast().map { $0.encoded().count }.reduce(0, +)
        #expect(throws: OggOpusError.missingEndOfStream) { try OggOpusReader.read(Array(file[0..<boundary])) }
    }

    @Test func corruptFilesFailCleanly() throws {
        let file = try OggOpusWriter.write(packets: syntheticPackets(count: 60), preSkip: 312, serial: 5)
        var flipped = file
        flipped[file.count - 5] ^= 0x01
        #expect(throws: OggOpusError.badChecksum) { try OggOpusReader.read(flipped) }
        var magic = file
        magic[0] = 0x58
        #expect(throws: OggOpusError.badCapturePattern) { try OggOpusReader.read(magic) }
        var version = file
        version[4] = 1
        #expect(throws: OggOpusError.unsupportedVersion) { try OggOpusReader.read(version) }
        #expect(throws: OggOpusError.trailingData) { try OggOpusReader.read(file + [0]) }
        // A missing middle page: sequence numbers jump.
        let pages = try allPages(file).map { $0.encoded() }
        #expect(throws: OggOpusError.badSequence) {
            try OggOpusReader.read(Array(pages[0...1].joined()) + Array(pages[3...].joined()))
        }
        // Valid Ogg that is not Opus.
        let junk = OggPage(flags: OggPage.beginOfStream | OggPage.endOfStream, granule: 0, serial: 1, sequence: 0,
                           segments: [4], body: [1, 2, 3, 4]).encoded()
        #expect(throws: OggOpusError.truncated) { try OggOpusReader.read(junk) }
    }

    @Test func badHeadersAreRejected() throws {
        func file(head: [UInt8], tags: [UInt8]) -> [UInt8] {
            OggPage(flags: OggPage.beginOfStream, granule: 0, serial: 1, sequence: 0,
                    segments: OggPage.lacing(forPacketOf: head.count), body: head).encoded()
            + OggPage(flags: OggPage.endOfStream, granule: 0, serial: 1, sequence: 1,
                      segments: OggPage.lacing(forPacketOf: tags.count), body: tags).encoded()
        }
        var tags: [UInt8] = Array("OpusTags".utf8)
        tags += le32(0); tags += le32(0)
        var head: [UInt8] = Array("OpusHead".utf8)
        head += [1, 1]; head += le16(312); head += le32(16_000); head += le16(0); head += [0]
        #expect(throws: Never.self) { try OggOpusReader.read(file(head: head, tags: tags)) }
        var v = head; v[8] = 0x10
        #expect(throws: OggOpusError.unsupportedVersion) { try OggOpusReader.read(file(head: v, tags: tags)) }
        var m = head; m[18] = 1
        #expect(throws: OggOpusError.unsupportedMapping) { try OggOpusReader.read(file(head: m, tags: tags)) }
        head[0] = 0x58
        #expect(throws: OggOpusError.badHeader) { try OggOpusReader.read(file(head: head, tags: tags)) }
        #expect(throws: OggOpusError.badHeader) { try OggOpusReader.read(file(head: Array(head[0..<10]), tags: tags)) }
    }

    @Test func writerRejectsBadInput() {
        #expect(throws: OggOpusError.noPackets) { try OggOpusWriter.write(packets: [], preSkip: 0) }
        #expect(throws: OggOpusError.invalidParameters) { try OggOpusWriter.write(packets: [[0xF8]], preSkip: 70_000) }
        #expect(throws: OggOpusError.invalidParameters) { try OggOpusWriter.write(packets: [[]], preSkip: 0) }
        #expect(throws: OggOpusError.packetTooLarge) {
            try OggOpusWriter.write(packets: [[0xF8] + [UInt8](repeating: 0, count: 70_000)], preSkip: 0)
        }
    }

    // MARK: container sniffing

    @Test func detectsContainers() {
        #expect(VoiceContainer.detect(prefix: Array("OggS\0\u{2}".utf8)) == .ogg)
        #expect(VoiceContainer.detect(prefix: Array("caff\0\u{1}".utf8)) == .caf)
        #expect(VoiceContainer.detect(prefix: [1, 2, 3]) == nil)
        #expect(VoiceContainer.detect(prefix: []) == nil)
    }

    @Test func voicePlanUsesOgg() {
        #expect(MediaPlan.Voice.fileExtension == "ogg")
        #expect(MediaPlan.Voice.mime == "audio/ogg; codecs=opus")
        #expect(MediaPlan.Voice.maxDuration == 1_800)
    }

    private func allPages(_ data: [UInt8]) throws -> [OggPage] {
        var pages: [OggPage] = [], offset = 0
        while offset < data.count { let (p, n) = try OggPage.parse(data, at: offset); pages.append(p); offset = n }
        return pages
    }
}

@Suite("OggOpusRobustness") struct OggOpusRobustnessTests {
    @Test func everyTocByteGivesAKnownDuration() {
        for toc in 0...255 {
            for code in 0...3 {
                let byte = UInt8(toc & ~3 | code)
                let packet: [UInt8] = code == 3 ? [byte, 1] : [byte, 0]
                let samples = OpusPacket.samples48k(packet)
                let frame = [480, 960, 1_920, 2_880, 480, 960, 1_920, 2_880, 480, 960, 1_920, 2_880, 480, 960, 480, 960,
                             120, 240, 480, 960, 120, 240, 480, 960, 120, 240, 480, 960, 120, 240, 480, 960][Int(byte >> 3)]
                let frames = [1, 2, 2, 1][code]
                #expect(samples == frame * frames, "toc \(byte)")
            }
        }
    }

    @Test func randomDamageNeverCrashes() throws {
        let file = try OggOpusWriter.write(packets: syntheticPackets(count: 30, sizes: [3: 300]), preSkip: 312, serial: 9)
        var seed: UInt64 = 0x9E37_79B9_7F4A_7C15
        func next() -> Int { seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407; return Int(seed >> 33) }
        for _ in 0..<3_000 {
            var copy = file
            for _ in 0...(next() % 4) { copy[next() % copy.count] = UInt8(next() & 0xFF) }
            if next() % 3 == 0 { copy = Array(copy[0..<(next() % copy.count)]) }
            _ = try? OggOpusReader.read(copy)
            _ = try? VoiceNoteValidator.validate(copy, origin: .received)
        }
        // Pure noise, with and without the capture pattern.
        for _ in 0..<500 {
            let noise = (0..<(next() % 200)).map { _ in UInt8(next() & 0xFF) }
            _ = try? VoiceNoteValidator.validate(noise, origin: .received)
            _ = try? VoiceNoteValidator.validate(Array("OggS".utf8) + noise, origin: .received)
        }
    }

    @Test func headerPacketsMustBeAloneOnTheirPages() throws {
        var head: [UInt8] = Array("OpusHead".utf8)
        head += [1, 1]; head += le16(312); head += le32(16_000); head += le16(0); head += [0]
        var tags: [UInt8] = Array("OpusTags".utf8)
        tags += le32(0); tags += le32(0)
        let both = OggPage(flags: OggPage.beginOfStream | OggPage.endOfStream, granule: 0, serial: 1, sequence: 0,
                           segments: [UInt8(head.count), UInt8(tags.count)], body: head + tags).encoded()
        #expect(throws: OggOpusError.badHeader) { try OggOpusReader.read(both) }
    }
}
