import XCTest
@testable import AppCore

final class BubbleRunTests: XCTestCase {
    private var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }

    private func msg(_ id: String, minute: Int, day: Int = 10, sender: String = "a", content: MessageContent? = nil) -> Message {
        let at = calendar.date(from: DateComponents(year: 2026, month: 3, day: day, hour: 9, minute: minute))!
        return Message(id: id, senderID: sender, sentAt: at, content: content ?? .text(id), status: .read, replyToID: nil, reactions: [])
    }

    func testRunOfThree() {
        let list = [msg("1", minute: 0), msg("2", minute: 1), msg("3", minute: 2)]
        let runs = list.indices.map { BubbleRun.at($0, in: list, calendar: calendar) }
        XCTAssertEqual(runs, [BubbleRun(joinsAbove: false, joinsBelow: true), BubbleRun(joinsAbove: true, joinsBelow: true),
                              BubbleRun(joinsAbove: true, joinsBelow: false)])
    }

    func testBreaks() {
        let list = [msg("1", minute: 0), msg("2", minute: 1, sender: "b"), msg("3", minute: 30, sender: "b"),
                    msg("4", minute: 31, day: 11, sender: "b"),
                    msg("5", minute: 32, day: 11, sender: "b", content: .system(.safetyNumberChanged)),
                    msg("6", minute: 33, day: 11, sender: "b")]
        for i in list.indices {
            XCTAssertEqual(BubbleRun.at(i, in: list, calendar: calendar), BubbleRun(joinsAbove: false, joinsBelow: false), "\(i)")
        }
    }

    func testOutOfRange() {
        XCTAssertEqual(BubbleRun.at(3, in: [], calendar: calendar), BubbleRun(joinsAbove: false, joinsBelow: false))
    }
}
