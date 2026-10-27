import Foundation
import LocalStore
import ServerKit

/// Block and report requests that the server has not acknowledged yet. They live in `kv` (a cleared entry has an
/// empty value), so they survive a restart; `LiveChatStore` retries them on every flush and inbox sync. The local
/// effect (blocked, hidden) is already applied when the entry is written.
enum PendingActions {
    static let blockPrefix = "pending.block."
    static let reportPrefix = "pending.report."

    struct Report: Codable {
        var accountID: String
        var conversationID: String?
        var messages: [ReportedMessage]
    }

    /// Only one run at a time: two overlapping runs would both send the same entry.
    private static let gate = Gate()

    private final class Gate: @unchecked Sendable {
        private let lock = NSLock()
        private var busy = false
        func enter() -> Bool { lock.withLock { if busy { return false }; busy = true; return true } }
        func leave() { lock.withLock { busy = false } }
    }

    static func run(repo: Repository, server: ServerClient) async {
        guard gate.enter() else { return }
        defer { gate.leave() }
        let blocks = ((try? repo.entries(prefix: blockPrefix)) ?? [:]).filter { !$0.value.isEmpty }
        for key in blocks.keys.sorted() {
            do { try await server.block(accountID: String(key.dropFirst(blockPrefix.count))) } catch { continue }
            try? repo.set(key, Data())
        }
        let reports = ((try? repo.entries(prefix: reportPrefix)) ?? [:]).filter { !$0.value.isEmpty }
        for key in reports.keys.sorted() {
            guard let report = try? JSONDecoder().decode(Report.self, from: reports[key] ?? Data()) else {
                try? repo.set(key, Data())   // unreadable: nothing to retry
                continue
            }
            do {
                try await server.report(
                    accountID: report.accountID, conversationID: report.conversationID, reason: "abuse",
                    messages: report.messages, note: nil)
            } catch { continue }
            try? repo.set(key, Data())
        }
    }
}
