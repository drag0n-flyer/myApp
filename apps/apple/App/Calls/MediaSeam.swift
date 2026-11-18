import Foundation
import AppCore
import CallCore
import CallMedia

// Everything the call engine needs from media goes through CallCore's `MediaSession`;
// the live one is CallMedia's WebRTC session.

enum MediaCapabilities {
    /// True when the linked WebRTC build has the capture post-processing hook; otherwise `enhanced` behaves like `standard`.
    static var enhancedNoiseSuppression: Bool { MediaFactory.captureHookAvailable }
}

enum LiveMedia {
    @MainActor static func make() -> MediaSession { WebRTCMediaSession.live() }
}
