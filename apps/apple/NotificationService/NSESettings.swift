import AppCore
import Foundation
import LocalStore
import Messaging

/// The two plain settings the app writes for the extension (the muted chat IDs and the app-lock flag), read from the
/// shared app-group container. No keys, keychain or database are touched. Missing or unreadable means locked with
/// nothing muted (`NotificationSettings.unreadable`): content is hidden and the sound stays.
enum NSESettings {
    static func read() -> NotificationSettings {
        guard let group = MessagingLocations.appGroupID, let container = StoreLocation.appGroup(group) else {
            return .unreadable
        }
        let url = container.appendingPathComponent(NotificationSettings.fileName)
        return NotificationSettings.decode(try? Data(contentsOf: url))
    }
}
