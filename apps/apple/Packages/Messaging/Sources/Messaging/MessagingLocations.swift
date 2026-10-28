import Foundation
#if canImport(os)
import os
#endif

/// Names the app and the notification extension must agree on (Design 12). Both open the same
/// files in the shared app-group container.
public enum MessagingLocations {
    /// The Info.plist key holding the app group. project.yml sets it to `group.$(APP_ID)`, so the
    /// group follows the app ID in AppID.xcconfig.
    public static let appGroupInfoKey = "AppGroupID"
    /// The app group the app and the notification extension share; nil (reported) when Info.plist lacks it.
    public static var appGroupID: String? { appGroup(in: Bundle.main.infoDictionary ?? [:]) }
    public static func appGroup(in info: [String: Any], onMissing: (String) -> Void = reportMissingInfoValue) -> String? {
        requiredInfoValue(appGroupInfoKey, in: info, onMissing: onMissing)
    }

    /// A string build setting the project writes into Info.plist and the app cannot work without
    /// (the app group, the shared keychain group). Missing or empty is reported through `onMissing`,
    /// never silently replaced: a fallback would split the app's and the extension's data.
    public static func requiredInfoValue(
        _ key: String, in info: [String: Any], onMissing: (String) -> Void = reportMissingInfoValue
    ) -> String? {
        guard let value = (info[key] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            onMissing(key)
            return nil
        }
        return value
    }

    /// Logs the missing key and stops a debug build (a broken project), like the missing MLS keychain key.
    public static func reportMissingInfoValue(_ key: String) {
        #if canImport(os)
        Logger(subsystem: Bundle.main.bundleIdentifier ?? "app", category: "config")
            .fault("Info.plist key \(key, privacy: .public) is missing or empty")
        #endif
        assertionFailure("Info.plist key \(key) is missing or empty")
    }

    public static let databaseFileName = "messaging.sqlite"
    public static let lockFileName = "messaging.lock"
    public static let mediaDirectoryName = "media"

    public static func databaseURL(in container: URL) -> URL { container.appendingPathComponent(databaseFileName) }
    public static func lockURL(in container: URL) -> URL { container.appendingPathComponent(lockFileName) }
    public static func mediaURL(in container: URL) -> URL {
        container.appendingPathComponent(mediaDirectoryName, isDirectory: true)
    }

    public static let mlsDirectoryName = "mls"
    /// Where the app keeps the MLS engine's group state.
    public static func mlsURL(in container: URL) -> URL {
        container.appendingPathComponent(mlsDirectoryName, isDirectory: true)
    }

    /// Deletes the MLS engine's storage (sign-out, device removal). Missing storage is not an error.
    public static func removeMLSStorage(in container: URL) throws {
        let url = mlsURL(in: container)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
}
