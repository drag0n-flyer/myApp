/// The `userInfo` key under which a message notification carries its message's random ID (never its content), so
/// the sweeper can remove the notification when the message expires. The extension writes it; the app reads it.
public enum NotificationMessageKey {
    public static let userInfo = "messageID"

    /// True when a delivered notification's `userInfo` names one of the swept messages.
    public static func isSwept(userInfo: [AnyHashable: Any], swept: Set<String>) -> Bool {
        guard let id = userInfo[Self.userInfo] as? String else { return false }
        return swept.contains(id)
    }
}
