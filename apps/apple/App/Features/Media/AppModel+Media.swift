import AppCore
import Foundation
import Messaging

extension AppModel {
    /// Where received and sent media files live; nil with sample data.
    var mediaDirectory: URL? {
        LiveMessaging.container.map { MessagingLocations.mediaURL(in: $0) }
    }

    /// True when a media file can be shown: it exists, is not empty, and lies inside the app's media folder
    /// (a stored path is never trusted). With sample data there are no files, so nothing is usable.
    /// Answers are remembered (see `MediaFileCheck`), so drawing a bubble does not touch the disk every time.
    func mediaFileIsUsable(_ url: URL) -> Bool {
        guard let directory = mediaDirectory else { return false }
        return MediaFileCheck.shared.isUsable(url, in: directory)
    }

    /// Starts (or restarts) a download; progress and the result arrive through the store's change callback.
    func downloadMedia(messageID: String, in chatID: String) {
        chatStore.download(messageID: messageID, in: chatID)
    }
}

/// Remembers whether a media file is usable, so drawing a bubble does not touch the disk every time. A "yes" is
/// asked again after 30 seconds (a wipe also clears everything); a "no" after a second.
@MainActor
final class MediaFileCheck {
    static let shared = MediaFileCheck()
    private var answers: [String: (usable: Bool, at: Date)] = [:]
    /// How long an answer is kept: a "no" briefly (the file may be about to be moved into place), a "yes" for 30 seconds.
    private init() {}

    func isUsable(_ url: URL, in directory: URL) -> Bool {
        let key = url.path
        if let known = answers[key], Date().timeIntervalSince(known.at) < (known.usable ? 30 : 1) { return known.usable }
        let usable = Self.check(url, in: directory)
        if answers.count > 2000 { answers.removeAll() }
        answers[key] = (usable, Date())
        return usable
    }

    func clear() { answers.removeAll() }

    private static func check(_ url: URL, in directory: URL) -> Bool {
        let real = url.resolvingSymlinksInPath()
        guard MediaPathGuard.isInside(real, directory: directory.resolvingSymlinksInPath()),
              let values = try? real.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]),
              values.isRegularFile == true, (values.fileSize ?? 0) > 0 else { return false }
        return true
    }
}
