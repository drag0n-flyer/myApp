import Foundation

/// Which of a chat's messages are photos, videos and files for the viewer and the "Media and files" gallery.
/// A video travels as a file (no protocol change), so it is told apart by the file name's extension.
public enum ChatMedia {
    public enum Kind: Equatable, Sendable { case photo, video, file }

    /// Extensions that make a file a video (same list on Android).
    public static let videoExtensions: Set<String> = ["mp4", "m4v", "mov", "3gp", "webm", "mkv"]

    /// A video is `file` content whose mime starts with `video/` or whose name ends in a video extension, either
    /// case-insensitive. A name with no extension (or only a leading dot) does not count.
    public static func isVideo(name: String, mime: String) -> Bool {
        if mime.lowercased().hasPrefix("video/") { return true }
        guard let dot = name.lastIndex(of: "."), dot != name.startIndex else { return false }
        return videoExtensions.contains(name[name.index(after: dot)...].lowercased())
    }

    /// The message content here carries no mime, so screens that only have the name use this (the name rule alone).
    public static func isVideo(name: String) -> Bool { isVideo(name: name, mime: "") }

    /// The mime a sender gives a video of this extension: `video/quicktime` for .mov, `video/mp4` for .mp4 and .m4v.
    public static func videoMime(forExtension ext: String) -> String? {
        switch ext.lowercased() {
        case "mov": "video/quicktime"
        case "mp4", "m4v": "video/mp4"
        case "3gp": "video/3gpp"
        case "webm": "video/webm"
        case "mkv": "video/x-matroska"
        default: nil
        }
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

    /// Reads every attachment message of a chat through `pager`, newest first, up to `cap`.
    public static func loadAll(_ pager: AttachmentPager, pageSize: Int = 200, cap: Int = 5_000) -> [Message] {
        var all: [Message] = []
        var before: String?
        while all.count < cap {
            let page = pager(before, pageSize)
            all += page
            guard page.count == pageSize, let last = page.last else { break }
            before = last.id
        }
        return all
    }
}
