import Foundation

public enum ProtectionLevel: Sendable {
    case untilFirstUnlock
    case complete
}

public enum StoreLocation {
    /// The shared app-group container (nil when unavailable, e.g. on Linux).
    public static func appGroup(_ id: String) -> URL? {
        #if canImport(Darwin)
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id)
        #else
        nil
        #endif
    }
}

/// Applies iOS data protection to a file and its SQLite `-wal`/`-shm` companions. No-op outside iOS.
public func applyProtection(to url: URL, _ level: ProtectionLevel) {
    #if os(iOS)
    let type: FileProtectionType = level == .complete ? .complete : .completeUntilFirstUnlock
    for suffix in ["", "-wal", "-shm"] {
        let path = url.path + suffix
        if FileManager.default.fileExists(atPath: path) {
            try? FileManager.default.setAttributes([.protectionKey: type], ofItemAtPath: path)
        }
    }
    #endif
}
