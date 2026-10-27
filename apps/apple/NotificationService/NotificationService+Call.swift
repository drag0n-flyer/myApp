import AppCore
import UserNotifications

extension NotificationService {
    /// The extension point for plan 8 (lane K): the `CALL` category, `.timeSensitive`, ring age.
    /// Plan 7 sets only the title (the contact's name) and the body. Lane K replaces this body and
    /// nothing else in the extension.
    static func applyCall(_ payload: CallSignalPayload, from contact: Contact, to content: UNMutableNotificationContent) {
        content.title = contact.displayName
        content.body = String(localized: "Incoming call")
        content.threadIdentifier = contact.id
        content.sound = .default
    }
}
