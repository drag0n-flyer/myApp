import AppCore
import CoreGraphics
import ImageIO
import SwiftUI

private struct ProfilePhotoKey: EnvironmentKey {
    static let defaultValue: @MainActor (String) -> Data? = { _ in nil }
}

extension EnvironmentValues {
    /// The photo to draw for an account ID (a contact's `profile`, or the own photo); `nil` shows the coloured letter.
    /// Set once at the root from the store, so no screen reads the profile table itself.
    var profilePhoto: @MainActor (String) -> Data? {
        get { self[ProfilePhotoKey.self] }
        set { self[ProfilePhotoKey.self] = newValue }
    }
}

/// Decoded photos, so a list does not decode a JPEG again for every redraw. Data that fails `ProfilePhotoRule` (at most
/// 24,576 bytes and a declared size of at most 1,024 x 1,024) is never decoded, and a decode is always a bounded
/// thumbnail (at most 512 pixels), never a full-size decode. Cleared when the account signs out.
@MainActor
enum ProfileImageCache {
    private static let cache = NSCache<NSData, CGImage>()
    private static let maxPixels = 512

    static func image(for data: Data) -> Image? {
        guard ProfilePhotoRule.isAcceptable(data) else { return nil }
        let key = data as NSData
        if let hit = cache.object(forKey: key) { return Image(decorative: hit, scale: 1) }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        cache.setObject(decoded, forKey: key)
        return Image(decorative: decoded, scale: 1)
    }

    static func clear() { cache.removeAllObjects() }
}
