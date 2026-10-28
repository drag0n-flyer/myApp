import AppCore
import Foundation

extension AppModel {
    /// Sends a photo, file or voice note chosen in the composer.
    /// With the live store, `LiveChatStore` re-encodes photos (`MediaPreparation`), encrypts, uploads and queues
    /// the message (`AttachmentService`). The sample store only adds a message of the right shape and size.
    func sendAttachment(_ attachment: ComposerAttachment, in chatID: String) {
        if chatStore is SampleChatStore {
            switch attachment {
            case .photo(let url): send(.photo(bytes: Self.fileSize(url)), in: chatID)
            case .file(let url): send(.file(name: url.lastPathComponent, bytes: Self.fileSize(url)), in: chatID)
            case .voice(_, let duration): send(.voice(duration: duration), in: chatID)
            }
            return
        }
        switch attachment {
        case .photo(let url): chatStore.sendAttachment(url, kind: .photo, in: chatID)
        case .file(let url): chatStore.sendAttachment(url, kind: .file, in: chatID)
        case .voice(let url, let duration): chatStore.sendVoice(url, duration: duration, in: chatID)
        }
    }

    private static func fileSize(_ url: URL) -> Int64 {
        Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
    }
}
