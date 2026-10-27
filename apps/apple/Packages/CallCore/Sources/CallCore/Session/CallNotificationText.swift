import Foundation

/// Text of the incoming-call notification, shared by the app (local notification) and the notification
/// service extension (rewritten push). Pure, so both processes agree.
public enum CallNotificationText {
    /// A ring this old (or older) is shown as a missed call. Matches the server's ring timeout.
    public static let staleAfter: TimeInterval = 45

    public static func content(callerName: String, video: Bool, ringAge: TimeInterval) -> (title: String, body: String, isCall: Bool) {
        if ringAge >= staleAfter { return (callerName, "Missed call", false) }
        return (callerName, video ? "Incoming video call" : "Incoming call", true)
    }
}
