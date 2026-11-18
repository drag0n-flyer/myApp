import Foundation
import Testing
@testable import AppCore

@Suite struct MediaBubbleStateTests {
    private let url = URL(fileURLWithPath: "/media/a.jpg")

    @Test func nilMediaIsNotDownloaded() {
        #expect(MediaBubbleState(media: nil, fileExists: { _ in true }) == .notDownloaded)
    }

    @Test func remoteIsNotDownloaded() {
        let media = MediaState(attachmentID: "a", phase: .remote)
        #expect(MediaBubbleState(media: media, fileExists: { _ in true }) == .notDownloaded)
    }

    @Test func downloadingKeepsFraction() {
        let media = MediaState(attachmentID: "a", phase: .downloading(0.4))
        #expect(MediaBubbleState(media: media, fileExists: { _ in false }) == .downloading(0.4))
    }

    @Test func downloadingFractionIsClamped() {
        let high = MediaState(attachmentID: "a", phase: .downloading(7))
        let low = MediaState(attachmentID: "a", phase: .downloading(-1))
        #expect(MediaBubbleState(media: high, fileExists: { _ in false }) == .downloading(1))
        #expect(MediaBubbleState(media: low, fileExists: { _ in false }) == .downloading(0))
    }

    @Test func localWithFileIsReady() {
        let media = MediaState(attachmentID: "a", phase: .local(url))
        #expect(MediaBubbleState(media: media, fileExists: { $0 == url }) == .ready(url))
    }

    @Test func localWithMissingFileFails() {
        let media = MediaState(attachmentID: "a", phase: .local(url))
        #expect(MediaBubbleState(media: media, fileExists: { _ in false }) == .failed)
    }

    @Test func failedIsFailed() {
        let media = MediaState(attachmentID: "a", phase: .failed)
        #expect(MediaBubbleState(media: media, fileExists: { _ in true }) == .failed)
    }
}
