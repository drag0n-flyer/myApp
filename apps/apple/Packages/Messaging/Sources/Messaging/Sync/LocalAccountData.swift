import Foundation
import LocalStore

/// Who owns the local data in the app-group container, and the durable "wipe pending" marker (sign-out).
///
/// The owner account and device IDs are stored in `kv` with the data. Before a live store is installed, `prepare`
/// wipes first when the stored owner is not the signed-in account and device or when a wipe was left pending, so
/// one account or device never opens another's database or MLS state (a sign-out that keeps the data relies on it). The marker is a file in the container, written before a sign-out wipe
/// starts and removed only after it succeeded: a kill or a failure leaves it for the next launch.
public enum LocalAccountData {
    public static let ownerKey = "owner.account"
    public static let ownerDeviceKey = "owner.device"
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

    /// Whether the data records its owning device. Only then may a sign-out keep it: the next sign-in, a new
    /// device, wipes it in `prepare`. Unreadable counts as not recorded.
    public static func hasRecordedDevice(repo: Repository) -> Bool {
        ((try? repo.get(ownerDeviceKey)) ?? nil) != nil
    }

    /// Makes the data in `repo` safe to use for `accountID` on `deviceID`. Wipes (with `wipe`) when a wipe is pending,
    /// when the stored owner is another account or device, or when no owner is recorded; then records the owner.
    /// A stored account with no device is from before devices were recorded, when every server sign-out wiped, so
    /// it belongs to the signed-in device.
    /// A failing wipe throws and leaves the marker and the old owner in place, so the caller must not use the data.
    /// - Returns: whether a wipe ran.
    @discardableResult
    public static func prepare(
        accountID: String, deviceID: String, container: URL, repo: Repository, wipe: @Sendable () async throws -> Void
    ) async throws -> Bool {
        let owner = try repo.get(ownerKey).flatMap { String(data: $0, encoding: .utf8) }
        let ownerDevice = try repo.get(ownerDeviceKey).flatMap { String(data: $0, encoding: .utf8) }
        let needsWipe = isWipePending(in: container) || owner != accountID || (ownerDevice ?? deviceID) != deviceID
        if needsWipe {
            try markWipePending(in: container)   // a kill between the wipe and the owner write repeats the wipe
            try await wipe()
        }
        if ownerDevice != deviceID || needsWipe { try repo.set(ownerDeviceKey, Data(deviceID.utf8)) }
        if owner != accountID || needsWipe { try repo.set(ownerKey, Data(accountID.utf8)) }
        if needsWipe { try clearWipePending(in: container) }
        return needsWipe
    }
}
