import Foundation
import Testing
@testable import AppCore

@Suite struct SendPlanTests {
    private let photo = PickedMedia(kind: .photo, bytes: 1_000)

    @Test func captionGoesAfterTheFirstItemTrimmed() {
        let result = SendPlan.plan(items: [photo, photo, photo], caption: " hi ", limitBytes: 10_000)
        #expect(result == .send([.attachment(0), .text("hi"), .attachment(1), .attachment(2)]))
    }

    @Test func aBlankCaptionAddsNoText() {
        #expect(SendPlan.plan(items: [photo, photo], caption: " \n ", limitBytes: 10_000) == .send([.attachment(0), .attachment(1)]))
        #expect(SendPlan.plan(items: [photo], caption: "", limitBytes: 10_000) == .send([.attachment(0)]))
    }

    @Test func elevenItemsAreTooMany() {
        let items = Array(repeating: photo, count: 11)
        #expect(SendPlan.plan(items: items, caption: "", limitBytes: 10_000) == .refused(.tooMany))
        #expect(SendPlan.plan(items: Array(items.prefix(10)), caption: "", limitBytes: 10_000) != .refused(.tooMany))
    }

    @Test func aVideoOneByteOverTheLimitIsRefused() {
        let limit: Int64 = 5_000
        #expect(SendPlan.plan(items: [PickedMedia(kind: .video, bytes: limit + 1)], caption: "x", limitBytes: limit) == .refused(.videoTooBig))
        #expect(SendPlan.plan(items: [PickedMedia(kind: .video, bytes: limit)], caption: "", limitBytes: limit) == .send([.attachment(0)]))
        // Photos are re-encoded, so only videos are checked here.
        #expect(SendPlan.plan(items: [PickedMedia(kind: .photo, bytes: limit + 1)], caption: "", limitBytes: limit) == .send([.attachment(0)]))
    }

    @Test func tooManyWinsOverTooBig() {
        let items = Array(repeating: PickedMedia(kind: .video, bytes: 9), count: 11)
        #expect(SendPlan.plan(items: items, caption: "", limitBytes: 1) == .refused(.tooMany))
    }

    @Test func cancelInEachPhase() {
        #expect(UploadCancel.onCancel(.queued) == .removeUnsent)
        #expect(UploadCancel.onCancel(.uploading) == .removeUnsent)
        #expect(UploadCancel.onCancel(.sent) == .keep)
    }
}

@Suite struct GalleryGroupsTests {
    private func date(_ iso: String) -> Date { ISO8601DateFormatter().date(from: iso)! }
    private func item(_ id: String, _ iso: String) -> GalleryItem { GalleryItem(messageID: id, date: date(iso)) }

    @Test func groupsAcrossAYearBoundaryNewestFirst() {
        let utc = TimeZone(identifier: "UTC")!
        let months = GalleryGroups.byMonth([
            item("a", "2025-12-31T23:00:00Z"), item("b", "2026-01-01T01:00:00Z"),
            item("c", "2026-01-20T10:00:00Z"), item("d", "2025-12-01T00:00:00Z"),
        ], zone: utc)
        #expect(months.map { [$0.year, $0.month] } == [[2026, 1], [2025, 12]])
        #expect(months[0].items.map(\.messageID) == ["c", "b"])
        #expect(months[1].items.map(\.messageID) == ["a", "d"])
    }

    @Test func theTimeZoneDecidesTheMonth() {
        let items = [item("x", "2026-01-31T23:30:00Z")]
        #expect(GalleryGroups.byMonth(items, zone: TimeZone(identifier: "UTC")!).map(\.month) == [1])
        #expect(GalleryGroups.byMonth(items, zone: TimeZone(identifier: "Asia/Kolkata")!).map { [$0.year, $0.month] } == [[2026, 2]])
        #expect(GalleryGroups.byMonth(items, zone: TimeZone(identifier: "America/Los_Angeles")!).map(\.month) == [1])
    }

    @Test func emptyHasNoGroups() {
        #expect(GalleryGroups.byMonth([], zone: TimeZone(identifier: "UTC")!).isEmpty)
    }
}
