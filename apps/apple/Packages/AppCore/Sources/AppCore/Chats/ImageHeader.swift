import Foundation

/// Reads an image's format and declared size from its first bytes, before anything decodes it. Pure; a picture from
/// another person or a website is never handed to a decoder on the strength of its content type or its length alone.
public enum ImageHeader {
    public enum Format: Equatable, Sendable { case jpeg, png, gif, webp }

    public struct Info: Equatable, Sendable {
        public var format: Format
        /// `nil` when the header does not say (a JPEG with no frame header in the bytes given).
        public var size: (width: Int, height: Int)?

        public static func == (a: Info, b: Info) -> Bool { a.format == b.format && a.size?.width == b.size?.width && a.size?.height == b.size?.height }
    }

    /// The format from the magic bytes, `nil` for anything else (SVG, HTML, a PDF...).
    public static func format(of data: Data) -> Format? {
        let b = [UInt8](data.prefix(16))
        if b.count >= 3, b[0] == 0xFF, b[1] == 0xD8, b[2] == 0xFF { return .jpeg }
        if b.count >= 8, Array(b[0..<8]) == [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] { return .png }
        if b.count >= 6, Array(b[0..<4]) == [0x47, 0x49, 0x46, 0x38], b[4] == 0x37 || b[4] == 0x39, b[5] == 0x61 { return .gif }
        if b.count >= 12, Array(b[0..<4]) == [0x52, 0x49, 0x46, 0x46], Array(b[8..<12]) == [0x57, 0x45, 0x42, 0x50] { return .webp }
        return nil
    }

    /// Format and declared size. For a JPEG the largest frame header before the scan counts, so a second, bigger one
    /// cannot hide behind a small first one.
    public static func inspect(_ data: Data) -> Info? {
        guard let format = format(of: data) else { return nil }
        let b = [UInt8](data)
        switch format {
        case .jpeg: return Info(format: .jpeg, size: jpegSize(b))
        case .png:
            guard b.count >= 24 else { return Info(format: .png, size: nil) }
            return Info(format: .png, size: (be32(b, 16), be32(b, 20)))
        case .gif:
            guard b.count >= 10 else { return Info(format: .gif, size: nil) }
            return Info(format: .gif, size: (Int(b[6]) | Int(b[7]) << 8, Int(b[8]) | Int(b[9]) << 8))
        case .webp: return Info(format: .webp, size: webpSize(b))
        }
    }

    private static func be32(_ b: [UInt8], _ at: Int) -> Int {
        Int(b[at]) << 24 | Int(b[at + 1]) << 16 | Int(b[at + 2]) << 8 | Int(b[at + 3])
    }

    private static func jpegSize(_ b: [UInt8]) -> (Int, Int)? {
        var i = 2
        var best: (Int, Int)?
        while i + 3 < b.count {
            guard b[i] == 0xFF else { i += 1; continue }
            let marker = b[i + 1]
            if marker == 0xFF { i += 1; continue }                       // fill bytes
            if marker == 0xD8 || marker == 0x01 || (0xD0...0xD7).contains(marker) || marker == 0 { i += 2; continue }
            if marker == 0xD9 || marker == 0xDA { break }                // end of image, start of scan
            let length = Int(b[i + 2]) << 8 | Int(b[i + 3])
            if length < 2 { return best }
            let isFrame = (0xC0...0xCF).contains(marker) && ![0xC4, 0xC8, 0xCC].contains(marker)
            if isFrame, i + 8 < b.count {
                let height = Int(b[i + 5]) << 8 | Int(b[i + 6]), width = Int(b[i + 7]) << 8 | Int(b[i + 8])
                if best == nil || width * height > best!.0 * best!.1 { best = (width, height) }
            }
            i += 2 + length
        }
        return best
    }

    private static func webpSize(_ b: [UInt8]) -> (Int, Int)? {
        guard b.count >= 30 else { return nil }
        switch String(decoding: b[12..<16], as: UTF8.self) {
        case "VP8X": return (1 + (Int(b[24]) | Int(b[25]) << 8 | Int(b[26]) << 16), 1 + (Int(b[27]) | Int(b[28]) << 8 | Int(b[29]) << 16))
        case "VP8L":
            guard b[20] == 0x2F else { return nil }
            let bits = Int(b[21]) | Int(b[22]) << 8 | Int(b[23]) << 16 | Int(b[24]) << 24
            return (1 + (bits & 0x3FFF), 1 + ((bits >> 14) & 0x3FFF))
        case "VP8 ":
            guard b[23] == 0x9D, b[24] == 0x01, b[25] == 0x2A else { return nil }
            return ((Int(b[26]) | Int(b[27]) << 8) & 0x3FFF, (Int(b[28]) | Int(b[29]) << 8) & 0x3FFF)
        default: return nil
        }
    }

    /// True when the declared size is known and at most `maxPixels` in total (and `maxSide` on each side when given).
    public static func isWithin(_ info: Info, maxPixels: Int, maxSide: Int? = nil) -> Bool {
        guard let size = info.size, size.width > 0, size.height > 0 else { return false }
        if let maxSide, size.width > maxSide || size.height > maxSide { return false }
        return size.width <= maxPixels && size.height <= maxPixels && size.width * size.height <= maxPixels
    }
}
