import Foundation

/// Which of a chat's messages are photos, videos and files for the viewer and the "Media and files" gallery.
/// A video travels as a file (no protocol change), so it is told apart by the file name's extension.
public enum ChatMedia {
    public enum Kind: Equatable, Sendable { case photo, video, file }

    /// Extensions the system player opens.
    public static let videoExtensions: Set<String> = ["mov", "mp4", "m4v", "3gp"]

    public static func isVideo(name: String) -> Bool {
        guard let dot = name.lastIndex(of: "."), dot != name.startIndex else { return false }
        return videoExtensions.contains(name[name.index(after: dot)...].lowercased())
    }

    /// `nil` for messages that are not attachments (and for deleted ones).
    public static func kind(of message: Message) -> Kind? {
        switch message.content {
        case .photo: .photo
        case .file(let name, _): isVideo(name: name) ? .video : .file
        default: nil
        }
    }

    public struct ViewerItem: Equatable, Sendable {
        public var messageID: String
        public var url: URL
        public var isVideo: Bool

        public init(messageID: String, url: URL, isVideo: Bool) {
            self.messageID = messageID
            self.url = url
            self.isVideo = isVideo
        }
    }

    /// Every photo and video of the chat that is on this device, in chat order (oldest first). `isUsable` says whether
    /// the local file may be shown (it exists and lies inside the app's media folder).
    public static func viewerItems(_ messages: [Message], isUsable: (URL) -> Bool) -> [ViewerItem] {
        messages.compactMap { message in
            guard let kind = kind(of: message), kind != .file,
                  case .local(let url)? = message.media?.phase, isUsable(url) else { return nil }
            return ViewerItem(messageID: message.id, url: url, isVideo: kind == .video)
        }
    }

    /// Where the viewer starts: the tapped item, or the first when it is not in the list.
    public static func startIndex(of messageID: String, in items: [ViewerItem]) -> Int {
        items.firstIndex { $0.messageID == messageID } ?? 0
    }

    /// The gallery's Media tab: photos and videos, downloaded or not, newest first.
    public static func mediaMessages(_ messages: [Message]) -> [Message] {
        messages.filter { [.photo, .video].contains(kind(of: $0)) }.sorted { $0.sentAt > $1.sentAt }
    }

    /// The gallery's Files tab: everything else that was sent as a file, newest first.
    public static func fileMessages(_ messages: [Message]) -> [Message] {
        messages.filter { kind(of: $0) == .file }.sorted { $0.sentAt > $1.sentAt }
    }

    /// The number shown next to "Media and files" in chat info.
    public static func count(_ messages: [Message]) -> Int { mediaMessages(messages).count + fileMessages(messages).count }
}
