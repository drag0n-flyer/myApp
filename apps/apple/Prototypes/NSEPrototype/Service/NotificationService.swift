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
                        report = "OK \(text) mem=\(Self.footprintMB())MB"
                    } else {
                        report = "OK (not a message) mem=\(Self.footprintMB())MB"
                    }
                } else {
                    report = "OK (fetch inbox) mem=\(Self.footprintMB())MB"
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

    /// Memory used by this process (phys_footprint, the number jetsam counts), in whole megabytes.
    private static func footprintMB() -> Int {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let status = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return status == KERN_SUCCESS ? Int(info.phys_footprint / 1_048_576) : -1
    }
}
