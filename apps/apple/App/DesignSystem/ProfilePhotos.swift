import SwiftUI
import AppCore
#if canImport(UIKit)
import UIKit
typealias PlatformImage = UIImage
#elseif canImport(AppKit)
import AppKit
typealias PlatformImage = NSImage
#endif

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

/// Decoded photos, so a list does not decode a JPEG again for every redraw.
@MainActor
enum ProfileImageCache {
    private static let cache = NSCache<NSData, PlatformImage>()

    static func image(for data: Data) -> Image? {
        let key = data as NSData
        let platform: PlatformImage
        if let hit = cache.object(forKey: key) {
            platform = hit
        } else if let made = PlatformImage(data: data) {
            cache.setObject(made, forKey: key)
            platform = made
        } else {
            return nil
        }
        #if canImport(UIKit)
        return Image(uiImage: platform)
        #else
        return Image(nsImage: platform)
        #endif
    }
}
