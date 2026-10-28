import AppCore
import CallCore
import UserNotifications

extension NotificationService {
    /// The `CALL` branch (plan 8): the notification of an incoming call on a Mac. The text, and whether it still
    /// rings or already shows as a missed call, come from `CallNotificationText`, shared with `MacRinger`. A ring
    /// older than the server's timeout is "Missed call" with no sound. `userInfo` carries what `MacRinger`'s
    /// Answer and Decline actions read: the call ID, video and the ring time.
    ///
    /// - Parameter ringedAt: when the ring reached this device. The push has no send time, so the extension passes
    ///   the time it started processing it.
    static func applyCall(
        _ payload: CallSignalPayload, from contact: Contact, to content: UNMutableNotificationContent,
        ringedAt: Date, now: Date = Date()
    ) {
        let video = payload.video ?? false
        let text = CallNotificationText.content(
            callerName: contact.displayName, video: video, ringAge: now.timeIntervalSince(ringedAt))
        content.title = text.title
        content.body = String(localized: String.LocalizationValue(text.body))
        content.threadIdentifier = contact.id
        content.userInfo = ["callID": payload.callID, "video": video, "ringedAt": ringedAt.timeIntervalSince1970]
        if text.isCall {
            content.categoryIdentifier = "CALL"
            content.interruptionLevel = .timeSensitive
            content.sound = .default
        }
    }
}
