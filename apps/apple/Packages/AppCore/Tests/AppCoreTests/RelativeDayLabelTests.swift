import Foundation
import Testing
@testable import AppCore

struct RelativeDayLabelTests {
    private let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        c.locale = Locale(identifier: "en_US_POSIX")
        return c
    }()
    private let locale = Locale(identifier: "en_US_POSIX")

    private func date(_ day: Int, _ hour: Int = 9, _ minute: Int = 38) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }
    // now: Thursday 19 Nov 2026, 15:00 UTC
    private var now: Date { date(19, 15, 0) }

    private func label(_ d: Date) -> String {
        RelativeDayLabel.format(d, now: now, calendar: calendar, locale: locale, yesterday: "Yesterday")
    }

    @Test func todayShowsTime() { #expect(label(date(19, 9, 38)).hasPrefix("9:38")) }
    @Test func yesterdayShowsWord() { #expect(label(date(18, 23, 59)) == "Yesterday") }
    @Test func yesterdayAtMidnightStart() { #expect(label(date(18, 0, 0)) == "Yesterday") }
    @Test func twoDaysAgoShowsWeekday() { #expect(label(date(17)) == "Tue") }
    @Test func sixDaysAgoShowsWeekday() { #expect(label(date(13, 0, 1)) == "Fri") }
    @Test func sevenDaysAgoShowsShortDate() { #expect(label(date(12)).contains("12")) }
    @Test func lastYearShowsShortDate() {
        let old = calendar.date(from: DateComponents(year: 2025, month: 3, day: 4, hour: 8))!
        let text = label(old)
        #expect(text.contains("25") && !text.contains(":"))
    }
}
