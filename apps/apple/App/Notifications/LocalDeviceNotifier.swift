import Foundation
import Messaging
import UserNotifications

/// Posts the "New device added" notice (Messaging Design 19) as a local notification. The notification
/// extension shows the same notice itself, so it never uses this type.
struct LocalDeviceNotifier: DeviceNotifier {
    func ownDeviceAdded(deviceID: String, name: String) async {
        let content = UNMutableNotificationContent()
        content.title = String(localized: "New device added")
        content.body = String(
            localized: "\(name) can now read your new messages. If this wasn't you, remove it in Settings > Linked devices.")
        content.sound = .default
        // One notice per device ID: a repeated request replaces the first.
        let request = UNNotificationRequest(identifier: "new-device-\(deviceID)", content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}
