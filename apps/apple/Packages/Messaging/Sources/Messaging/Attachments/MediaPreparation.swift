#if canImport(ImageIO)
import Foundation
import ImageIO
import UniformTypeIdentifiers

public enum MediaPreparationError: Error, Equatable, Sendable {
    case unreadableImage
    case encodingFailed
}

/// Re-encodes photos for sending (Design 9). Sizes and qualities come from `MediaPlan`.
/// The output is a JPEG written without any metadata (no EXIF, GPS or TIFF dictionaries).
public enum MediaPreparation {
    /// Writes a JPEG of at most 1 600 px (quality 0.7) to `to` and returns its size and a
    /// 32 px thumbnail of at most 1 536 bytes. Orientation is baked into the pixels.
    @discardableResult
    public static func preparePhoto(_ url: URL, to: URL) throws -> (w: Int, h: Int, thumb: Data) {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { throw MediaPreparationError.unreadableImage }
        let main = try scaled(source, maxSide: MediaPlan.photoMaxSide)
        try write(main, quality: MediaPlan.photoQuality, to: to)

        let thumbImage = try scaled(source, maxSide: MediaPlan.thumbnailSide)
        var thumb = Data()
        for quality in MediaPlan.thumbnailQualities() {
            thumb = try encode(thumbImage, quality: quality)
            if thumb.count <= MediaPlan.thumbnailMaxBytes { break }
        }
        guard thumb.count <= MediaPlan.thumbnailMaxBytes else { throw MediaPreparationError.encodingFailed }
        return (main.width, main.height, thumb)
    }

    /// Decodes with the longest side capped; `kCGImageSourceCreateThumbnailWithTransform` applies orientation.
    private static func scaled(_ source: CGImageSource, maxSide: Int) throws -> CGImage {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw MediaPreparationError.unreadableImage
        }
        return image
    }

    private static func destination(_ sink: CFTypeRef) throws -> CGImageDestination {
        let type = UTType.jpeg.identifier as CFString
        let dest: CGImageDestination?
        if let data = sink as? NSMutableData { dest = CGImageDestinationCreateWithData(data, type, 1, nil) }
        else { dest = CGImageDestinationCreateWithURL(sink as! CFURL, type, 1, nil) }
        guard let dest else { throw MediaPreparationError.encodingFailed }
        return dest
    }

    private static func finish(_ dest: CGImageDestination, _ image: CGImage, _ quality: Double) throws {
        // Only the quality is passed, so no metadata is written.
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw MediaPreparationError.encodingFailed }
    }

    private static func write(_ image: CGImage, quality: Double, to url: URL) throws {
        try finish(try destination(url as CFURL), image, quality)
    }

    private static func encode(_ image: CGImage, quality: Double) throws -> Data {
        let data = NSMutableData()
        try finish(try destination(data), image, quality)
        return data as Data
    }
}
#endif
