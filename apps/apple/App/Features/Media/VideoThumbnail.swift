import AppCore
import AVFoundation
import ImageIO
import SwiftUI

/// A video's first frame and length, read from the local file (no thumbnail travels with the video).
struct VideoInfo: @unchecked Sendable {
    let image: CGImage?
    let duration: Double
}

/// Makes video posters off the main actor and keeps a bounded number of them.
actor VideoPoster {
    static let shared = VideoPoster()
    private var cache = BoundedCache<String, VideoInfo>(capacity: 40_000_000)

    func info(at url: URL, maxPixel: Int) async -> VideoInfo {
        let key = "\(url.path)@\(maxPixel)"
        if let hit = cache.get(key) { return hit }
        let asset = AVURLAsset(url: url)
        let seconds = (try? await asset.load(.duration).seconds) ?? 0
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: max(maxPixel, 1), height: max(maxPixel, 1))
        let image = try? await generator.image(at: .zero).image
        let info = VideoInfo(image: image, duration: seconds.isFinite ? max(seconds, 0) : 0)
        if let image { cache.set(key, info, cost: image.bytesPerRow * image.height) }
        return info
    }

    /// Forget everything (sign-out).
    func clear() { cache.removeAll() }
}

/// The video inside a bubble: its first frame, a play glyph and the length.
struct VideoThumbnail: View {
    let url: URL
    @Environment(\.displayScale) private var displayScale
    @ScaledMetric(relativeTo: .body) private var width: CGFloat = 220
    @ScaledMetric(relativeTo: .body) private var height: CGFloat = 160
    @State private var info: VideoInfo?

    var body: some View {
        ZStack {
            if let image = info?.image {
                Image(decorative: image, scale: displayScale)
                    .resizable()
                    .scaledToFill()
            } else {
                Color.secondary.opacity(0.25)
            }
            Image(systemName: "play.fill")
                .font(.title2)
                .foregroundStyle(.white)
                .frame(width: 48, height: 48)
                .background(Circle().fill(.black.opacity(0.45)))
                .accessibilityHidden(true)
        }
        .frame(width: width, height: height)
        .overlay(alignment: .bottomTrailing) {
            if let duration = info?.duration, duration > 0 {
                Text(Message.clockText(duration))
                    .font(.caption.monospacedDigit().weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(.black.opacity(0.5)))
                    .padding(6)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Play video"))
        .accessibilityValue(Text(info.map { Message.clockText($0.duration) } ?? ""))
        .task(id: url) {
            info = nil
            let pixels = Int((max(width, height) * displayScale).rounded(.up))
            let loaded = await VideoPoster.shared.info(at: url, maxPixel: pixels)
            guard !Task.isCancelled else { return }
            info = loaded
        }
    }
}
