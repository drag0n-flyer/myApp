import Foundation

/// Failures when writing or reading Ogg Opus (RFC 3533 container, RFC 7845 mapping).
public enum OggOpusError: Error, Equatable, Sendable {
    case noPackets
    case packetTooLarge
    case invalidParameters
    case truncated
    case badCapturePattern
    case unsupportedVersion
    case badChecksum
    case badSequence
    case badHeader
    case unsupportedMapping
    case missingEndOfStream
    case unexpectedStream
    case trailingData
}

/// One Ogg page (RFC 3533 section 6). Pure value, little-endian fields, no Foundation file access.
struct OggPage: Equatable {
    static let capturePattern: [UInt8] = [0x4F, 0x67, 0x67, 0x53]   // "OggS"
    static let headerSize = 27
    static let continued: UInt8 = 0x01
    static let beginOfStream: UInt8 = 0x02
    static let endOfStream: UInt8 = 0x04

    var flags: UInt8
    var granule: Int64
    var serial: UInt32
    var sequence: UInt32
    /// Lacing values: each is 255 (packet goes on) or less (packet ends here).
    var segments: [UInt8]
    var body: [UInt8]

    /// The lacing values for a packet of `count` bytes. A multiple of 255 ends with a 0 value.
    static func lacing(forPacketOf count: Int) -> [UInt8] {
        [UInt8](repeating: 255, count: count / 255) + [UInt8(count % 255)]
    }

    func encoded() -> [UInt8] {
        var out = Self.capturePattern
        out.append(0)                       // stream structure version
        out.append(flags)
        out.append(contentsOf: le64(UInt64(bitPattern: granule)))
        out.append(contentsOf: le32(serial))
        out.append(contentsOf: le32(sequence))
        out.append(contentsOf: [0, 0, 0, 0])  // checksum, filled in below
        out.append(UInt8(segments.count))
        out.append(contentsOf: segments)
        out.append(contentsOf: body)
        let crc = OggCRC.checksum(out)
        out.replaceSubrange(22..<26, with: le32(crc))
        return out
    }

    /// Parses the page that starts at `offset`; returns it and the offset after it.
    static func parse(_ data: [UInt8], at offset: Int) throws -> (OggPage, Int) {
        guard data.count - offset >= headerSize else {
            // Fewer than four bytes cannot be told from garbage; a wrong prefix is reported as such.
            let have = min(4, data.count - offset)
            if have > 0, Array(data[offset..<offset + have]) != Array(capturePattern[0..<have]) { throw OggOpusError.badCapturePattern }
            throw OggOpusError.truncated
        }
        guard Array(data[offset..<offset + 4]) == capturePattern else { throw OggOpusError.badCapturePattern }
        guard data[offset + 4] == 0 else { throw OggOpusError.unsupportedVersion }
        let count = Int(data[offset + 26])
        let tableEnd = offset + headerSize + count
        guard data.count >= tableEnd else { throw OggOpusError.truncated }
        let segments = Array(data[offset + headerSize..<tableEnd])
        let bodySize = segments.reduce(0) { $0 + Int($1) }
        let end = tableEnd + bodySize
        guard data.count >= end else { throw OggOpusError.truncated }

        var raw = Array(data[offset..<end])
        let stored = readLE32(raw, at: 22)
        raw.replaceSubrange(22..<26, with: [0, 0, 0, 0])
        guard OggCRC.checksum(raw) == stored else { throw OggOpusError.badChecksum }

        let page = OggPage(
            flags: data[offset + 5],
            granule: Int64(bitPattern: readLE64(data, at: offset + 6)),
            serial: readLE32(data, at: offset + 14),
            sequence: readLE32(data, at: offset + 18),
            segments: segments,
            body: Array(data[tableEnd..<end]))
        return (page, end)
    }
}

/// The Ogg page checksum: CRC-32, polynomial 0x04C11DB7, initial value 0, no reflection, no final
/// XOR, computed over the whole page with the checksum field set to zero.
enum OggCRC {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var r = UInt32(i) << 24
        for _ in 0..<8 { r = (r & 0x8000_0000) != 0 ? (r << 1) ^ 0x04C1_1DB7 : r << 1 }
        return r
    }

    static func checksum(_ bytes: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0
        for b in bytes { crc = (crc << 8) ^ table[Int((crc >> 24) ^ UInt32(b)) & 0xFF] }
        return crc
    }
}

func le16(_ v: UInt16) -> [UInt8] { [UInt8(v & 0xFF), UInt8(v >> 8)] }
func le32(_ v: UInt32) -> [UInt8] { (0..<4).map { UInt8((v >> (8 * UInt32($0))) & 0xFF) } }
func le64(_ v: UInt64) -> [UInt8] { (0..<8).map { UInt8((v >> (8 * UInt64($0))) & 0xFF) } }
func readLE16(_ b: [UInt8], at o: Int) -> UInt16 { UInt16(b[o]) | UInt16(b[o + 1]) << 8 }
func readLE32(_ b: [UInt8], at o: Int) -> UInt32 { (0..<4).reduce(0) { $0 | UInt32(b[o + $1]) << (8 * UInt32($1)) } }
func readLE64(_ b: [UInt8], at o: Int) -> UInt64 { (0..<8).reduce(0) { $0 | UInt64(b[o + $1]) << (8 * UInt64($1)) } }
