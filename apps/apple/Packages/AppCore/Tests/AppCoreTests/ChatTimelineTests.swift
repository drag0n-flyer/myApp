import XCTest
@testable import AppCore

final class ChatTimelineTests: XCTestCase {
    private var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        c.locale = Locale(identifier: "en_US")
        return c
    }

    private func date(_ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 3, day: day, hour: hour, minute: minute))!
    }

    private func msg(_ id: String, _ at: Date, sender: String = "x") -> Message {
        Message(id: id, senderID: sender, sentAt: at, content: .text(id), status: .read, replyToID: nil, reactions: [])
    }

    func testEmpty() {
        XCTAssertEqual(ChatTimeline.rows(messages: [], firstUnreadID: nil, calendar: calendar), [])
    }

    func testOneDay() {
        let a = msg("a", date(10, 9)), b = msg("b", date(10, 10))
        let rows = ChatTimeline.rows(messages: [a, b], firstUnreadID: nil, calendar: calendar)
        XCTAssertEqual(rows, [.date(date(10, 0)), .message(a), .message(b)])
    }

    func testAcrossMidnight() {
        let a = msg("a", date(10, 23, 59)), b = msg("b", date(11, 0, 1))
        let rows = ChatTimeline.rows(messages: [a, b], firstUnreadID: nil, calendar: calendar)
        XCTAssertEqual(rows, [.date(date(10, 0)), .message(a), .date(date(11, 0)), .message(b)])
    }

    func testUnreadMarkerAfterDateRow() {
        let a = msg("a", date(10, 9)), b = msg("b", date(11, 10))
        let rows = ChatTimeline.rows(messages: [a, b], firstUnreadID: "b", calendar: calendar)
        XCTAssertEqual(rows, [.date(date(10, 0)), .message(a), .date(date(11, 0)), .unreadMarker, .message(b)])
    }

    func testUnreadMarkerBetweenMessages() {
        let a = msg("a", date(10, 9)), b = msg("b", date(10, 10))
        let rows = ChatTimeline.rows(messages: [a, b], firstUnreadID: "b", calendar: calendar)
        XCTAssertEqual(rows, [.date(date(10, 0)), .message(a), .unreadMarker, .message(b)])
    }

    func testUnknownUnreadIDGivesNoMarker() {
        let a = msg("a", date(10, 9))
        XCTAssertFalse(ChatTimeline.rows(messages: [a], firstUnreadID: "zzz", calendar: calendar).contains(.unreadMarker))
    }

    func testRowIDsAreUnique() {
        let a = msg("a", date(10, 9)), b = msg("b", date(11, 9))
        let rows = ChatTimeline.rows(messages: [a, b], firstUnreadID: "b", calendar: calendar)
        XCTAssertEqual(Set(rows.map(\.id)).count, rows.count)
    }

    func testDateLabels() {
        let now = date(20, 15)
        XCTAssertEqual(ChatTimeline.dateLabel(for: date(20, 1), now: now, calendar: calendar), "Today")
        XCTAssertEqual(ChatTimeline.dateLabel(for: date(19, 23), now: now, calendar: calendar), "Yesterday")
        let older = ChatTimeline.dateLabel(for: date(3, 12), now: now, calendar: calendar)
        XCTAssertNotEqual(older, "Today")
        XCTAssertNotEqual(older, "Yesterday")
        XCTAssertTrue(older.contains("2026"), older)
        XCTAssertEqual(ChatTimeline.daySpan(for: date(3, 12), now: now, calendar: calendar), .other)
    }

    func testFirstUnreadID() {
        let ms = [msg("a", date(10, 9), sender: "them"), msg("b", date(10, 10), sender: "me"),
                  msg("c", date(10, 11), sender: "them"), msg("d", date(10, 12), sender: "them")]
        XCTAssertEqual(ChatTimeline.firstUnreadID(messages: ms, unreadCount: 2, myID: "me"), "c")
        XCTAssertEqual(ChatTimeline.firstUnreadID(messages: ms, unreadCount: 3, myID: "me"), "a")
        XCTAssertNil(ChatTimeline.firstUnreadID(messages: ms, unreadCount: 0, myID: "me"))
        // More unread than loaded: the oldest loaded incoming message.
        XCTAssertEqual(ChatTimeline.firstUnreadID(messages: ms, unreadCount: 9, myID: "me"), "a")
        XCTAssertNil(ChatTimeline.firstUnreadID(messages: [], unreadCount: 3, myID: "me"))
    }
}
