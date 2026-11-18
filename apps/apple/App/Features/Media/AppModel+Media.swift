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

    /// Every photo, video and file of the chat that this device has, oldest first. Reads the store page by page, so a
    /// long chat is covered, not only the newest messages.
    func chatAttachmentMessages(in chatID: String) -> [Message] {
        var all: [Message] = []
        var before: String?
        while all.count < 5_000 {
            let page = chatStore.messages(in: chatID, before: before, limit: 200)
            guard let first = page.first else { break }
            all.insert(contentsOf: page, at: 0)
            before = first.id
        }
        return all.filter { ChatMedia.kind(of: $0) != nil }
    }

    /// What the viewer shows when `messageID` is tapped: every local photo and video of the chat, in order.
    func viewerRequest(chatID: String, startingAt messageID: String) -> ViewerRequest? {
        let items = ChatMedia.viewerItems(chatAttachmentMessages(in: chatID), isUsable: mediaFileIsUsable)
        guard !items.isEmpty else { return nil }
        return ViewerRequest(items: items, start: ChatMedia.startIndex(of: messageID, in: items))
    }

    /// The size a video may have: the server's limit when known, otherwise the default.
    var attachmentLimitBytes: Int64 { SendPlan.defaultLimitBytes }

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
