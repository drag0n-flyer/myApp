import Foundation

public struct VideoSize: Sendable, Equatable, Hashable {
    public var width: Int, height: Int
    public init(width: Int, height: Int) { self.width = width; self.height = height }
}

/// Chooses a camera format for a video tier.
public enum VideoFormatPicker {
    /// The exact size, else the smallest format at least as large as the tier in both dimensions
    /// (smallest area first), else the largest one available. `nil` when `sizes` is empty.
    public static func index(in sizes: [VideoSize], for tier: VideoTier) -> Int? {
        guard !sizes.isEmpty else { return nil }
        let l = VideoLadder.spec(tier)
        func area(_ s: VideoSize) -> Int { s.width * s.height }
        let larger = sizes.indices.filter { sizes[$0].width >= l.width && sizes[$0].height >= l.height }
        if let best = larger.min(by: { area(sizes[$0]) < area(sizes[$1]) }) { return best }
        return sizes.indices.max { area(sizes[$0]) < area(sizes[$1]) }
    }
}
