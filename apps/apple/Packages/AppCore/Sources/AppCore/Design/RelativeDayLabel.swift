import Foundation

/// Chat list and Calls list times: the time today, "Yesterday", the weekday for the last 6 days, a short date before that.
public enum RelativeDayLabel {
    /// `yesterday` is the localized word; the app passes it from the String Catalog.
    public static func format(_ date: Date, now: Date, calendar: Calendar, locale: Locale, yesterday: String) -> String {
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: now)).day ?? 0
        if days <= 0 {
            return string(date, calendar: calendar, locale: locale) { $0.timeStyle = .short; $0.dateStyle = .none }
        }
        if days == 1 { return yesterday }
        if days <= 6 {
            return string(date, calendar: calendar, locale: locale) { $0.setLocalizedDateFormatFromTemplate("EEE") }
        }
        return string(date, calendar: calendar, locale: locale) { $0.dateStyle = .short; $0.timeStyle = .none }
    }

    private static func string(_ date: Date, calendar: Calendar, locale: Locale, configure: (DateFormatter) -> Void) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = locale
        configure(formatter)
        return formatter.string(from: date)
    }
}
