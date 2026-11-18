import AppCore
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Makes the profile photo from a picked or captured picture with system image APIs only: the picture's own
/// orientation is applied, the middle square is cut out, and it is scaled down and re-encoded as a JPEG until it is
/// at most 24,576 bytes. A fresh encode carries no metadata (no location, no camera details). Every decision about
/// the next size and quality is `ProfilePhotoSizing`, which has tests; this file only runs the image calls.
enum ProfilePhotoEncoder {
    /// The JPEG, or `nil` when the data is not a picture or no size fits.
    nonisolated static func jpeg(from data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,   // applies the orientation
            kCGImageSourceThumbnailMaxPixelSize: 1024,          // never decodes a full-size photo
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              let square = ProfilePhotoSizing.centreSquare(width: image.width, height: image.height),
              let cropped = image.cropping(to: CGRect(x: square.x, y: square.y, width: square.side, height: square.side))
        else { return nil }

        var attempt = ProfilePhotoSizing.first
        for _ in 0..<64 {
            guard let encoded = encode(cropped, side: attempt.side, quality: attempt.quality) else { return nil }
            switch ProfilePhotoSizing.decision(after: attempt, bytes: encoded.count) {
            case .accept: return encoded
            case .retry(let next): attempt = next
            case .giveUp: return nil
            }
        }
        return nil
    }

    private nonisolated static func encode(_ image: CGImage, side: Int, quality: Double) -> Data? {
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
        guard let scaled = context.makeImage() else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, scaled, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }
}
