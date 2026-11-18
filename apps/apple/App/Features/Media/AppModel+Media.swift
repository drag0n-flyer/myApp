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
    func mediaFileIsUsable(_ url: URL) -> Bool {
        guard let directory = mediaDirectory else { return false }
        let fm = FileManager.default
        let real = url.resolvingSymlinksInPath()
        guard MediaPathGuard.isInside(real, directory: directory.resolvingSymlinksInPath()),
              let size = try? real.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]).fileSize,
              size > 0, fm.fileExists(atPath: real.path) else { return false }
        return true
    }

    /// Starts (or restarts) a download; progress and the result arrive through the store's change callback.
    func downloadMedia(messageID: String, in chatID: String) {
        chatStore.download(messageID: messageID, in: chatID)
    }
}
