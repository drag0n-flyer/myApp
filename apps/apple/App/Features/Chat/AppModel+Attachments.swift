import AppCore
import Foundation

extension AppModel {
    /// Sends a photo, file or voice note chosen in the composer.
    /// Sample-data stand-in: it adds a message of the right shape and real size. Lane B replaces the
    /// body with `MediaPreparation.preparePhoto` (photos) and `AttachmentService.send(...)`.
    func sendAttachment(_ attachment: ComposerAttachment, in chatID: String) {
        switch attachment {
        case .photo(let url): send(.photo(bytes: Self.fileSize(url)), in: chatID)
        case .file(let url): send(.file(name: url.lastPathComponent, bytes: Self.fileSize(url)), in: chatID)
        case .voice(_, let duration): send(.voice(duration: duration), in: chatID)
        }
    }

    private static func fileSize(_ url: URL) -> Int {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
    }
}
