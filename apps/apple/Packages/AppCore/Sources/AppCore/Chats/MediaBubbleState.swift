import Foundation

/// What a photo, file or voice bubble shows, from the message's media state.
public enum MediaBubbleState: Equatable, Sendable {
    case notDownloaded, downloading(Double), ready(URL), failed

    /// `fileExists` says whether a local file is still there (a missing or moved file is a failed download).
    public init(media: MediaState?, fileExists: (URL) -> Bool) {
        guard let media else { self = .notDownloaded; return }
        switch media.phase {
        case .remote: self = .notDownloaded
        case .downloading(let fraction): self = .downloading(VoiceSelection.clamp(fraction))
        case .local(let url): self = fileExists(url) ? .ready(url) : .failed
        case .failed: self = .failed
        }
    }
}
