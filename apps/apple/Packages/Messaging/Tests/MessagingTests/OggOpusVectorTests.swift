import Foundation
import Testing
@testable import Messaging

/// docs/protocol/test-vectors/voice-ogg-v1.json: a small Ogg Opus file written by `OggOpusWriter`,
/// with every field Android needs to check its own reader and writer against the same bytes.
/// Regenerate on purpose with `OGG_VECTOR_OUT=<dir> swift test --filter OggOpusVector`, then copy
/// the JSON to the test resources and the vector folder (scripts/check-vector-copies.sh checks it).
@Suite("OggOpusVector") struct OggOpusVectorTests {
    private struct Vector: Decodable {
        struct Expected: Decodable {
            let pageCount: Int
            let pageGranules: [Int64]
            let pageCrcs: [String]
            let finalGranule: Int64
            let durationSamples: Int64
        }
        let serial: UInt32
        let preSkip: Int
        let inputSampleRate: UInt32
        let channels: Int
        let vendor: String
        let packetsB64: [String]
        let oggB64: String
        let expected: Expected
    }

    /// 120 packets: 20 ms each except one 10 ms packet; lengths 255, 300, 510 and 1275 exercise lacing.
    static let sizes = [10: 255, 20: 300, 30: 510, 70: 1_275]
    static func packets() -> [[UInt8]] {
        var list = syntheticPackets(count: 120, sizes: sizes)
        list[5][0] = 0xF0   // CELT fullband 10 ms
        return list
    }
    static let serial: UInt32 = 0x1234_5678
    static let preSkip = 312

    @Test func generateWhenAsked() throws {
        guard let dir = ProcessInfo.processInfo.environment["OGG_VECTOR_OUT"] else { return }
        let packets = Self.packets()
        let ogg = try OggOpusWriter.write(packets: packets, preSkip: Self.preSkip, inputSampleRate: 16_000, serial: Self.serial)
        var pages: [OggPage] = [], offset = 0
        while offset < ogg.count { let (p, n) = try OggPage.parse(ogg, at: offset); pages.append(p); offset = n }
        let crcs = pages.map { String(format: "%08x", readLE32($0.encoded(), at: 22)) }
        let stream = try OggOpusReader.read(ogg)
        let json: [String: Any] = [
            "description": "Ogg Opus voice message (RFC 7845), 16 kHz mono input. Packets are valid Opus TOC bytes with filler payload: they exercise the container, not the decoder. Granules count 48 kHz samples; playing time = finalGranule - preSkip. CRC: poly 0x04C11DB7, init 0, no reflection, no xorout, over the page with the CRC field zeroed; crcs are the stored little-endian field read as hex.",
            "serial": Self.serial, "preSkip": Self.preSkip, "inputSampleRate": 16_000, "channels": 1,
            "vendor": OggOpusWriter.vendor,
            "packetsB64": packets.map { Data($0).base64EncodedString() },
            "oggB64": Data(ogg).base64EncodedString(),
            "expected": [
                "pageCount": pages.count, "pageGranules": pages.map(\.granule), "pageCrcs": crcs,
                "finalGranule": stream.finalGranule, "durationSamples": stream.finalGranule - Int64(Self.preSkip),
            ] as [String: Any],
        ]
        let data = try JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
        try data.write(to: URL(fileURLWithPath: dir).appendingPathComponent("voice-ogg-v1.json"))
        try Data(ogg).write(to: URL(fileURLWithPath: dir).appendingPathComponent("voice-v1.ogg"))
    }

    private func load() throws -> Vector {
        let url = try #require(Bundle.module.url(forResource: "voice-ogg-v1", withExtension: "json", subdirectory: "Resources"))
        return try JSONDecoder().decode(Vector.self, from: Data(contentsOf: url))
    }

    @Test func writerReproducesTheVectorBytes() throws {
        let v = try load()
        let packets = v.packetsB64.map { [UInt8](Data(base64Encoded: $0)!) }
        #expect(packets == Self.packets())
        let ogg = try OggOpusWriter.write(packets: packets, preSkip: v.preSkip, inputSampleRate: v.inputSampleRate,
                                          channels: v.channels, serial: v.serial)
        #expect(ogg == [UInt8](Data(base64Encoded: v.oggB64)!))
    }

    @Test func vectorFieldsDecodeByHand() throws {
        let v = try load()
        let ogg = [UInt8](Data(base64Encoded: v.oggB64)!)
        // Walk the pages with raw offsets only (no OggPage), checking every checksum on the way.
        var offset = 0
        var granules: [Int64] = [], crcs: [String] = []
        var sequence: UInt32 = 0
        while offset < ogg.count {
            #expect(Array(ogg[offset..<offset + 4]) == [0x4F, 0x67, 0x67, 0x53])
            #expect(ogg[offset + 4] == 0)
            var g: UInt64 = 0
            for i in 0..<8 { g |= UInt64(ogg[offset + 6 + i]) << (8 * UInt64(i)) }
            granules.append(Int64(bitPattern: g))
            let s = UInt32(ogg[offset + 18]) | UInt32(ogg[offset + 19]) << 8 | UInt32(ogg[offset + 20]) << 16 | UInt32(ogg[offset + 21]) << 24
            #expect(s == sequence); sequence += 1
            let nsegs = Int(ogg[offset + 26])
            let bodyLen = ogg[(offset + 27)..<(offset + 27 + nsegs)].reduce(0) { $0 + Int($1) }
            let end = offset + 27 + nsegs + bodyLen
            var page = Array(ogg[offset..<end])
            crcs.append(page[22..<26].reversed().map { String(format: "%02x", $0) }.joined())
            page.replaceSubrange(22..<26, with: [0, 0, 0, 0])
            // Bitwise CRC, independent of the table version in OggCRC.
            var crc: UInt32 = 0
            for b in page {
                crc ^= UInt32(b) << 24
                for _ in 0..<8 { crc = crc & 0x8000_0000 != 0 ? crc << 1 ^ 0x04C1_1DB7 : crc << 1 }
            }
            #expect(String(format: "%08x", crc) == crcs.last)
            offset = end
        }
        #expect(granules == v.expected.pageGranules)
        #expect(crcs == v.expected.pageCrcs)
        #expect(granules.count == v.expected.pageCount)
        // OpusHead fields at fixed offsets (page 1 body starts at byte 28).
        #expect(Array(ogg[28..<36]) == Array("OpusHead".utf8))
        #expect(ogg[36] == 1 && Int(ogg[37]) == v.channels)
        #expect(Int(ogg[38]) | Int(ogg[39]) << 8 == v.preSkip)
        #expect(UInt32(ogg[40]) | UInt32(ogg[41]) << 8 | UInt32(ogg[42]) << 16 | UInt32(ogg[43]) << 24 == v.inputSampleRate)
        // Granule: 119 x 20 ms + 1 x 10 ms = 119 * 960 + 480 samples.
        #expect(v.expected.finalGranule == 119 * 960 + 480)
        #expect(v.expected.durationSamples == 119 * 960 + 480 - 312)
        #expect(try OggOpusReader.read(ogg).packets == v.packetsB64.map { [UInt8](Data(base64Encoded: $0)!) })
    }
}
