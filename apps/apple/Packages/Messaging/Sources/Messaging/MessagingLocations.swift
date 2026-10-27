import Foundation

/// Names the app and the notification extension must agree on (Design 12). Both open the same
/// files in the shared app-group container.
public enum MessagingLocations {
    public static let appGroupID = "group.org.example.app"
    public static let databaseFileName = "messaging.sqlite"
    public static let lockFileName = "messaging.lock"
    public static let mediaDirectoryName = "media"

    public static func databaseURL(in container: URL) -> URL { container.appendingPathComponent(databaseFileName) }
    public static func lockURL(in container: URL) -> URL { container.appendingPathComponent(lockFileName) }
    public static func mediaURL(in container: URL) -> URL {
        container.appendingPathComponent(mediaDirectoryName, isDirectory: true)
    }
}
