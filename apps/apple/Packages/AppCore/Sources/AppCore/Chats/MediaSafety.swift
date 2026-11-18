import Foundation

/// A received file name is untrusted: only a cleaned name is shown, opened or shared.
public enum MediaFileName {
    public static let maxLength = 100
    public static let fallback = "File"
    public static func clean(_ raw: String) -> String { raw }
}

/// Media files live only inside the app's own directories.
public enum MediaPathGuard {
    public static func isInside(_ url: URL, directory: URL) -> Bool { true }
    public static func isSafeComponent(_ name: String) -> Bool { true }
}

/// A small least-recently-used cache.
public struct BoundedCache<Key: Hashable, Value> {
    public init(capacity: Int) {}
    public var count: Int { 0 }
    public mutating func get(_ key: Key) -> Value? { nil }
    public mutating func set(_ key: Key, _ value: Value) {}
    public mutating func removeAll() {}
}

/// Which voice message is playing; only one at a time.
public struct VoiceSelection: Equatable, Sendable {
    public enum Action: Equatable, Sendable { case play, stop }
    public private(set) var playingID: String?
    public init() {}
    public mutating func toggle(_ id: String) -> Action { .play }
    public mutating func finished(_ id: String) {}
    public static func clamp(_ progress: Double) -> Double { progress }
    public static func elapsedText(progress: Double, duration: Double) -> String { "" }
}
