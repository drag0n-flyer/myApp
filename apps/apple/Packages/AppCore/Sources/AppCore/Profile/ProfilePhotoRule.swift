import Foundation

/// Reads the declared picture size out of a JPEG's frame header without decoding anything.
///
/// The rule (the same on every platform, table in `server/internal/vectors/profile_photo.json`):
/// the data starts with FF D8; segments are walked by their lengths (a marker is FF, any number of FF fill bytes,
/// then the marker byte; every marker except D0-D7, 01 and D8 has a 2-byte big-endian length that includes itself);
/// the first SOF0 (C0), SOF1 (C1) or SOF2 (C2) segment gives precision (1 byte), height (2) and width (2), and needs a
/// length of at least 8 and all its bytes present. EOI (D9), SOS (DA) or the end of the data before a frame header, a
/// segment that runs past the end, or a length below 2: no size. Bytes that look like a frame header inside another
/// segment (an APP1/EXIF block, say) are skipped with that segment.
public enum JPEGFrame {
    public static func size(of data: Data) -> (width: Int, height: Int)? {
        let b = [UInt8](data)
        guard b.count >= 4, b[0] == 0xFF, b[1] == 0xD8 else { return nil }
        var i = 2
        while i < b.count {
            guard b[i] == 0xFF else { return nil }
            while i < b.count, b[i] == 0xFF { i += 1 }   // fill bytes
            guard i < b.count else { return nil }
            let marker = b[i]
            i += 1
            if marker == 0xD8 || marker == 0x01 || (0xD0...0xD7).contains(marker) { continue }
            if marker == 0xD9 || marker == 0xDA { return nil }
            guard i + 2 <= b.count else { return nil }
            let length = Int(b[i]) << 8 | Int(b[i + 1])
            guard length >= 2, i + length <= b.count else { return nil }
            if marker == 0xC0 || marker == 0xC1 || marker == 0xC2 {
                guard length >= 8 else { return nil }
                let height = Int(b[i + 3]) << 8 | Int(b[i + 4])
                let width = Int(b[i + 5]) << 8 | Int(b[i + 6])
                return (width, height)
            }
            i += length
        }
        return nil
    }
}

/// Whether a profile photo may be stored and drawn: a JPEG of at least 4 and at most 24,576 bytes whose frame header
/// declares a size from 1 x 1 up to 1,024 x 1,024. Checked on receive before anything is stored, when the own photo
/// is set, and again before every decode for display (a row written by an earlier build is not trusted).
public enum ProfilePhotoRule {
    public static let maxBytes = 24_576
    public static let minBytes = 4
    public static let maxSide = 1_024

    public static func isAcceptable(_ data: Data) -> Bool {
        guard data.count >= minBytes, data.count <= maxBytes, let size = JPEGFrame.size(of: data) else { return false }
        return (1...maxSide).contains(size.width) && (1...maxSide).contains(size.height)
    }
}
