import Foundation
import LocalStore

/// The notification extension's limits and the decisions that follow from them, kept here (not in
/// the extension target) so Linux tests cover them.
///
/// iOS ends a notification service extension that uses more than about 24 MB or runs longer than
/// about 30 s. The budget below keeps both with room to spare:
/// - one push per launch, never a loop over the inbox beyond `NotificationProcessor`'s own sync;
/// - the database opens lazily, after the push has been checked, with a small page cache;
/// - the push is at most 3 072 bytes (server design 7.4), so it is rejected before any decoding
///   or database work if the base64 text is longer than that bucket allows.
public enum NotificationBudget {
    /// The ceiling iOS enforces, in bytes. Recorded for the owner's manual memory check.
    public static let memoryLimitBytes = 24 * 1024 * 1024
    /// Seconds the processor gets; iOS gives about 30 s, the rest is for delivering the content.
    public static let processingSeconds = 25
    /// `PRAGMA cache_size`: a negative value is KiB, so this is about 2 MB of page cache.
    public static let databaseCacheKiB = 2000
    /// The largest padded push body the server sends (a 3 072-byte bucket).
    public static let maxPushBytes = 3072

    /// The processor's deadline for a push that arrived at `now`.
    public static func deadline(from now: Date) -> Date {
        now.addingTimeInterval(TimeInterval(processingSeconds))
    }

    /// Decodes the push's `d` value (standard base64). Returns nil when it is not a string, is not
    /// valid base64, is empty, or decodes to more than `maxPushBytes`.
    public static func decodePush(_ value: Any?) -> Data? {
        guard let text = value as? String else { return nil }
        // Base64 grows 3 bytes to 4 characters: check the length before allocating the result.
        guard !text.isEmpty, text.utf8.count <= (maxPushBytes + 2) / 3 * 4 else { return nil }
        guard let data = Data(base64Encoded: text), !data.isEmpty, data.count <= maxPushBytes else { return nil }
        return data
    }

    /// Applies the extension's database settings. Call once, right after opening.
    public static func configure(_ db: Database) throws {
        try db.execute("PRAGMA cache_size=-\(databaseCacheKiB)")
    }
}

/// Runs a closure at most once, from any thread. The extension must call the system's content
/// handler exactly once: the processing task and `serviceExtensionTimeWillExpire` race for it.
public final class OnceGate: @unchecked Sendable {
    private let lock = NSLock()
    private var used = false

    public init() {}

    /// Returns true when `body` ran.
    @discardableResult
    public func run(_ body: () -> Void) -> Bool {
        let first = lock.withLock { () -> Bool in
            if used { return false }
            used = true
            return true
        }
        if first { body() }
        return first
    }
}
