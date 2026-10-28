import Foundation

/// Names the app and the notification extension must agree on (Design 12). Both open the same
/// files in the shared app-group container.
public enum MessagingLocations {
    /// The Info.plist key holding the app group. project.yml sets it to `group.$(APP_ID)`, so the
    /// group follows the app ID in AppID.xcconfig.
    public static let appGroupInfoKey = "AppGroupID"
    /// The app group the app and the notification extension share; nil when Info.plist lacks it.
    public static var appGroupID: String? { appGroup(in: Bundle.main.infoDictionary ?? [:]) }
    public static func appGroup(in info: [String: Any]) -> String? {
        guard let id = (info[appGroupInfoKey] as? String)?.trimmingCharacters(in: .whitespaces), !id.isEmpty else {
            return nil
        }
        return id
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
