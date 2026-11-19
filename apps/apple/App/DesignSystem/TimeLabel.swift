import AppCore
import Foundation

/// Chat list and Calls list times: "9:38" today, "Yesterday", weekday for the last 6 days, short date before that.
enum TimeLabel {
    static func format(_ date: Date, now: Date = Date(), calendar: Calendar = .current) -> String {
        RelativeDayLabel.format(date, now: now, calendar: calendar, locale: calendar.locale ?? .current,
                                yesterday: String(localized: "Yesterday"))
    }
}
