import AppCore
import Foundation
import UserNotifications

extension NotificationActions {
    /// Removes the delivered notifications of messages that expired. A notification names its message in
    /// `userInfo["messageID"]`: the extension writes it for every message it stores (`NotificationMessageKey`).
    func removeDelivered(messageIDs: [String]) {
        guard !messageIDs.isEmpty else { return }
        let swept = Set(messageIDs)
        Task { await Self.removeDeliveredExpired(swept) }
    }

    nonisolated private static func removeDeliveredExpired(_ swept: Set<String>) async {
        let center = UNUserNotificationCenter.current()
        let ids = await center.deliveredNotifications()
            .filter { NotificationMessageKey.isSwept(userInfo: $0.request.content.userInfo, swept: swept) }
            .map(\.request.identifier)
        if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
    }
}
