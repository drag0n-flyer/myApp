import Foundation
import LocalStore

/// Who owns the local data in the app-group container, and the durable "wipe pending" marker (sign-out).
///
/// The owner account ID is stored in `kv` with the data. Before a live store is installed, `prepare` wipes first
/// when the stored owner is not the signed-in account or when a wipe was left pending, so one account never
/// opens another's database or MLS state. The marker is a file in the container, written before a sign-out wipe
/// starts and removed only after it succeeded: a kill or a failure leaves it for the next launch.
public enum LocalAccountData {
    public static let ownerKey = "owner.account"
    public static let pendingFileName = "wipe.pending"

    public static func pendingURL(in container: URL) -> URL { container.appendingPathComponent(pendingFileName) }

    public static func isWipePending(in container: URL) -> Bool {
        FileManager.default.fileExists(atPath: pendingURL(in: container).path)
    }

    public static func markWipePending(in container: URL) throws {
        try FileManager.default.createDirectory(at: container, withIntermediateDirectories: true)
        try Data([1]).write(to: pendingURL(in: container), options: .atomic)
    }

    public static func clearWipePending(in container: URL) throws {
        if isWipePending(in: container) { try FileManager.default.removeItem(at: pendingURL(in: container)) }
    }

    /// Makes the data in `repo` safe to use for `accountID`. Wipes (with `wipe`) when a wipe is pending, when the
    /// stored owner is another account, or when no owner is recorded; then records `accountID` as the owner.
    /// A failing wipe throws and leaves the marker and the old owner in place, so the caller must not use the data.
    /// - Returns: whether a wipe ran.
    @discardableResult
    public static func prepare(
        accountID: String, container: URL, repo: Repository, wipe: @Sendable () async throws -> Void
    ) async throws -> Bool {
        let owner = try repo.get(ownerKey).flatMap { String(data: $0, encoding: .utf8) }
        let needsWipe = isWipePending(in: container) || owner != accountID
        if needsWipe {
            try markWipePending(in: container)   // a kill between the wipe and the owner write repeats the wipe
            try await wipe()
        }
        if owner != accountID || needsWipe { try repo.set(ownerKey, Data(accountID.utf8)) }
        if needsWipe { try clearWipePending(in: container) }
        return needsWipe
    }
}
