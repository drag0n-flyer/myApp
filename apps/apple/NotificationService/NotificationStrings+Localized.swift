import Foundation
import Messaging

extension NotificationStrings {
    /// The strings from this extension's String Catalog (`Localizable.xcstrings`).
    static func localized() -> NotificationStrings {
        var s = NotificationStrings()
        s.requestTitle = String(localized: "Message request")
        s.requestBody = String(localized: "Open [App] to see it")
        s.reactedFormat = String(localized: "Reacted %@")
        s.newDeviceTitle = String(localized: "New device added")
        s.newDeviceBodyFormat = String(localized: "%@ can now read your new messages. If this wasn't you, remove it in Settings > Linked devices.")
        s.unknownDevice = String(localized: "A new device")
        s.unknownCaller = String(localized: "Unknown")
        s.photo = String(localized: "Photo")
        s.voiceMessage = String(localized: "Voice message")
        s.file = String(localized: "File")
        s.unsupported = String(localized: "Update [App] to see this message")
        s.fallbackTitle = String(localized: "[App]")
        s.fallbackBody = String(localized: "New message")
        return s
    }
}
