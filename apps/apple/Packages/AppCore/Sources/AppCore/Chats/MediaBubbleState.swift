import Foundation

/// What a photo, file or voice bubble shows, from the message's media state.
public enum MediaBubbleState: Equatable, Sendable {
    case notDownloaded, downloading(Double), ready(URL), failed

    public init(media: MediaState?, fileExists: (URL) -> Bool) {
        self = .notDownloaded
    }
}
