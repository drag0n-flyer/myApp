import AppCore
import ImageIO
import SwiftUI

/// A decoded picture. `CGImage` is immutable, so it is safe to hand across actors.
struct DecodedImage: @unchecked Sendable {
    let cgImage: CGImage
}

/// Decodes photos downsampled to the display size (ImageIO thumbnails, never the full bitmap), off the main
/// actor, and keeps a bounded number of results.
actor ThumbnailLoader {
    static let shared = ThumbnailLoader()
    private var cache = BoundedCache<String, DecodedImage>(capacity: 60)

    /// nil when the file is missing, empty or not an image.
    func image(at url: URL, maxPixel: Int) async -> DecodedImage? {
        let key = "\(url.path)@\(maxPixel)"
        if let hit = cache.get(key) { return hit }
        let decoded = await Task.detached(priority: .userInitiated) { Self.decode(url, maxPixel: maxPixel) }.value
        if let decoded { cache.set(key, decoded) }
        return decoded
    }

    /// Forget everything (sign-out).
    func clear() { cache.removeAll() }

    nonisolated static func decode(_ url: URL, maxPixel: Int) -> DecodedImage? {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size > 0 else { return nil }
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithURL(url as CFURL, sourceOptions), CGImageSourceGetCount(source) > 0 else { return nil }
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(maxPixel, 1),
        ] as CFDictionary
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options).map(DecodedImage.init)
    }
}

/// The photo inside a bubble. A file that cannot be decoded shows the neutral placeholder.
struct PhotoThumbnail: View {
    let url: URL
    @Environment(\.displayScale) private var displayScale
    @ScaledMetric(relativeTo: .body) private var width: CGFloat = 220
    @ScaledMetric(relativeTo: .body) private var height: CGFloat = 160
    @State private var result: Phase = .loading

    private enum Phase {
        case loading, image(DecodedImage), undecodable
    }

    var body: some View {
        ZStack {
            switch result {
            case .image(let decoded):
                Image(decorative: decoded.cgImage, scale: displayScale)
                    .resizable()
                    .scaledToFill()
            case .loading:
                Color.secondary.opacity(0.15)
            case .undecodable:
                Color.secondary.opacity(0.25)
                    .overlay { Image(systemName: "photo").font(.largeTitle).foregroundStyle(.secondary) }
            }
        }
        .frame(width: width, height: height)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Photo"))
        .accessibilityAddTraits(.isImage)
        // Keyed by the file: when the bubble is reused for another photo the old task is cancelled and its
        // result is dropped, so a stale decode never lands here.
        .task(id: url) {
            result = .loading
            let pixels = Int((max(width, height) * displayScale).rounded(.up))
            let decoded = await ThumbnailLoader.shared.image(at: url, maxPixel: pixels)
            guard !Task.isCancelled else { return }
            result = decoded.map(Phase.image) ?? .undecodable
        }
    }
}
