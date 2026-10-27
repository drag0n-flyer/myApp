import Foundation
import os
import UserNotifications
import SecureMessaging

final class NotificationService: UNNotificationServiceExtension, @unchecked Sendable {
    private static let groupID = "group.org.example.app.nseproto"
    private static let fallback = "New message"

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        content.body = Self.fallback
        let userInfo = request.content.userInfo
        let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.groupID)
        Task {
            var report = ""
            do {
                guard let container else { throw MLSError.storage("no app group container") }
                guard let encoded = userInfo["d"] as? String, let padded = Data(base64Encoded: encoded) else {
                    throw MLSError.malformed
                }
                // nil means "fetch the inbox": the prototype just shows the fallback.
                if let message = try PushPayload.unpad(padded) {
                    let engine = try makeMLSEngine(identity: "proto/nse", storage: container.appendingPathComponent("mls"))
                    if case .application(_, _, let plaintext, _) = try await engine.process(message) {
                        let text = String(decoding: plaintext, as: UTF8.self)
                        content.body = text
                        report = "OK \(text) mem=\(os_proc_available_memory())"
                    } else {
                        report = "OK (not a message) mem=\(os_proc_available_memory())"
                    }
                } else {
                    report = "OK (fetch inbox) mem=\(os_proc_available_memory())"
                }
            } catch {
                content.body = Self.fallback
                report = "ERR \(error)"
            }
            if let container {
                try? Data(report.utf8).write(to: container.appendingPathComponent("nse-result.txt"))
            }
            contentHandler(content)
        }
    }
}
