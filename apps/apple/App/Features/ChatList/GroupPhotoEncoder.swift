import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Turns a picked image into the JPEG a group carries: at most 256 points on the long side and at most 24,576 bytes.
/// The decode is a thumbnail of at most `side` pixels, so the work does not grow with the size of the picture. Runs
/// off the main actor (callers use `Task.detached`).
enum GroupPhotoEncoder {
    static let side = 256
    static let maxBytes = 24_576
    /// A picked file larger than this is not even opened.
    static let maxInput = 40_000_000

    /// `nil` when the data is not an image, or no quality gives 24,576 bytes or less.
    static func jpeg(from data: Data) -> Data? {
        guard data.count <= maxInput, let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetCount(source) > 0 else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: side,
        ]
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        for quality in [0.8, 0.65, 0.5, 0.35, 0.2] {
            let out = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
            CGImageDestinationAddImage(destination, thumbnail, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
            guard CGImageDestinationFinalize(destination) else { return nil }
            if out.length <= maxBytes, out.length >= 4 { return out as Data }
        }
        return nil
    }

    /// The picture of a JPEG this app made itself (the chosen photo's preview), at its own size.
    static func preview(of jpeg: Data) -> CGImage? {
        guard jpeg.count <= maxBytes, let source = CGImageSourceCreateWithData(jpeg as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }
}
