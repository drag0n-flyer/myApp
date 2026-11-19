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
/// thumbnail (at most 512 pixels), never a full-size decode. The first decode runs off the main actor (`load`); a view
/// draws from `cached` meanwhile. Cleared when the account signs out.
@MainActor
enum ProfileImageCache {
    private static let cache = NSCache<NSData, CGImage>()
    private static let maxPixels = 512
    /// Bumped by `clear`, so a decode that was running at sign-out is not stored afterwards.
    private static var generation = 0

    /// The photo when it was decoded before; never decodes.
    static func cached(for data: Data) -> Image? {
        guard ProfilePhotoRule.isAcceptable(data), let hit = cache.object(forKey: data as NSData) else { return nil }
        return Image(decorative: hit, scale: 1)
    }

    /// Decodes off the main actor (a detached task), stores the result here on the main actor. `nil` when the data fails
    /// the rule or is not an image.
    static func load(_ data: Data) async -> Image? {
        guard ProfilePhotoRule.isAcceptable(data) else { return nil }
        if let hit = cached(for: data) { return hit }
        let started = generation
        let limit = maxPixels // read here: the detached task is not on the main actor
        let decoded = await Task.detached(priority: .userInitiated) { decode(data, maxPixels: limit) }.value
        guard let decoded else { return nil }
        if started == generation { cache.setObject(decoded.cgImage, forKey: data as NSData) }
        return Image(decorative: decoded.cgImage, scale: 1)
    }

    static func clear() {
        generation += 1
        cache.removeAllObjects()
    }

    /// `DecodedImage` (PhotoThumbnail.swift) carries the immutable `CGImage` back across actors.
    private nonisolated static func decode(_ data: Data, maxPixels: Int) -> DecodedImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary).map(DecodedImage.init)
    }
}

/// A profile or group photo: drawn at once when it is already decoded, otherwise decoded off the main actor and drawn
/// when ready; `fallback` shows until then and when the data is no usable photo.
struct ProfilePhotoImage<Photo: View, Fallback: View>: View {
    let data: Data?
    @ViewBuilder let photo: (Image) -> Photo
    @ViewBuilder let fallback: () -> Fallback
    @State private var loaded: Loaded?

    private struct Loaded {
        let data: Data
        let image: Image
    }

    var body: some View {
        Group {
            if let data, let image = ProfileImageCache.cached(for: data) ?? (loaded?.data == data ? loaded?.image : nil) {
                photo(image)
            } else {
                fallback()
            }
        }
        .task(id: data) {
            guard let data, ProfileImageCache.cached(for: data) == nil else { return }
            if let image = await ProfileImageCache.load(data) { loaded = Loaded(data: data, image: image) }
        }
    }
}
