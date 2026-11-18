import Foundation
import Testing
@testable import AppCore

@Suite struct ChatMediaTests {
    private let t0 = Date(timeIntervalSince1970: 1_700_000_000)

    private func message(_ id: String, _ content: MessageContent, at offset: TimeInterval, local: String? = nil) -> Message {
        Message(id: id, senderID: "x", sentAt: t0.addingTimeInterval(offset), content: content, status: .sent, replyToID: nil, reactions: [],
                media: local.map { MediaState(attachmentID: id, phase: .local(URL(fileURLWithPath: $0))) })
    }

    @Test func videosAreToldApartByExtension() {
        #expect(ChatMedia.isVideo(name: "clip.MOV"))
        #expect(ChatMedia.isVideo(name: "a.b.mp4"))
        #expect(!ChatMedia.isVideo(name: "report.pdf"))
        #expect(!ChatMedia.isVideo(name: "mp4"))
        #expect(!ChatMedia.isVideo(name: ".mp4"))
    }

    @Test func kinds() {
        #expect(ChatMedia.kind(of: message("a", .photo(bytes: 1), at: 0)) == .photo)
        #expect(ChatMedia.kind(of: message("b", .file(name: "v.mp4", bytes: 1), at: 0)) == .video)
        #expect(ChatMedia.kind(of: message("c", .file(name: "n.pdf", bytes: 1), at: 0)) == .file)
        #expect(ChatMedia.kind(of: message("d", .text("hi"), at: 0)) == nil)
        #expect(ChatMedia.kind(of: message("e", .deleted, at: 0)) == nil)
    }

    @Test func theViewerHoldsLocalPhotosAndVideosInChatOrder() {
        let all = [
            message("p1", .photo(bytes: 1), at: 0, local: "/m/p1.jpg"),
            message("t", .text("hi"), at: 1),
            message("f", .file(name: "n.pdf", bytes: 1), at: 2, local: "/m/f.pdf"),
            message("remote", .photo(bytes: 1), at: 3),
            message("v", .file(name: "v.mov", bytes: 1), at: 4, local: "/m/v.mov"),
            message("gone", .photo(bytes: 1), at: 5, local: "/m/gone.jpg"),
        ]
        let items = ChatMedia.viewerItems(all) { $0.path != "/m/gone.jpg" }
        #expect(items.map(\.messageID) == ["p1", "v"])
        #expect(items.map(\.isVideo) == [false, true])
        #expect(ChatMedia.startIndex(of: "v", in: items) == 1)
        #expect(ChatMedia.startIndex(of: "unknown", in: items) == 0)
    }

    @Test func theGalleryTabsAreNewestFirstAndCountMatches() {
        let all = [
            message("p1", .photo(bytes: 1), at: 0),
            message("f1", .file(name: "a.pdf", bytes: 1), at: 1),
            message("v1", .file(name: "v.mp4", bytes: 1), at: 2),
            message("p2", .photo(bytes: 1), at: 3),
            message("f2", .file(name: "b.pdf", bytes: 1), at: 4),
            message("t", .text("x"), at: 5),
        ]
        #expect(ChatMedia.mediaMessages(all).map(\.id) == ["p2", "v1", "p1"])
        #expect(ChatMedia.fileMessages(all).map(\.id) == ["f2", "f1"])
        #expect(ChatMedia.count(all) == 5)
        #expect(ChatMedia.count([]) == 0)
    }
}
