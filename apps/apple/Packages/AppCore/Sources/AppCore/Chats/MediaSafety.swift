import Foundation

/// A received file name is untrusted: only a cleaned name is shown, opened or shared.
public enum MediaFileName {
    public static let maxLength = 100
    public static let fallback = "File"

    /// Drops path parts, control characters and direction overrides (such as U+202E), caps the length
    /// (keeping a short extension) and never returns an empty or dot-only name.
    public static func clean(_ raw: String) -> String {
        let lastPart = raw.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
        var scalars = String.UnicodeScalarView()
        for scalar in lastPart.unicodeScalars where !isUnwanted(scalar) { scalars.append(scalar) }
        let trimmed = String(scalars).trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty || trimmed.allSatisfy({ $0 == "." }) { return fallback }
        return capped(trimmed)
    }

    private static func isUnwanted(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .control, .format, .lineSeparator, .paragraphSeparator, .surrogate, .privateUse, .unassigned: return true
        default: return false
        }
    }

    private static func capped(_ name: String) -> String {
        guard name.count > maxLength else { return name }
        let ext = name.lastIndex(of: ".").map { String(name[$0...]) } ?? ""
        let keepExt = ext.count <= 12 ? ext : ""
        let stem = String(name.dropLast(keepExt.count))
        return String(stem.prefix(maxLength - keepExt.count)) + keepExt
    }
}

/// Media files live only inside the app's own directories.
public enum MediaPathGuard {
    /// True when `url` is strictly inside `directory` once `.`, `..` and symbolic links are resolved.
    public static func isInside(_ url: URL, directory: URL) -> Bool {
        // Symlinks are resolved on both sides, so a link inside the folder that points outside is refused.
        let base = directory.resolvingSymlinksInPath().standardizedFileURL.pathComponents
        let target = url.resolvingSymlinksInPath().standardizedFileURL.pathComponents
        guard target.count > base.count, !target.contains("..") else { return false }
        return Array(target.prefix(base.count)) == base
    }

    /// True for a name that can be one path component: no separators, no dots-only, no control characters.
    public static func isSafeComponent(_ name: String) -> Bool {
        guard !name.isEmpty, name != ".", name != "..", name.utf8.count <= 255 else { return false }
        return !name.unicodeScalars.contains { $0 == "/" || $0 == "\\" || $0.properties.generalCategory == .control }
    }
}

/// A least-recently-used cache bounded by total cost (bytes, for pictures), not by item count.
public struct BoundedCache<Key: Hashable, Value> {
    private let capacity: Int
    private var values: [Key: (value: Value, cost: Int)] = [:]
    private var order: [Key] = []
    public private(set) var totalCost = 0

    /// `capacity` is the largest total cost kept (with the default cost of 1, the largest number of items).
    public init(capacity: Int) { self.capacity = max(capacity, 1) }

    public var count: Int { values.count }

    public mutating func get(_ key: Key) -> Value? {
        guard let entry = values[key] else { return nil }
        touch(key)
        return entry.value
    }

    /// An item that costs more than the whole cache is not kept.
    public mutating func set(_ key: Key, _ value: Value, cost: Int = 1) {
        remove(key)
        let cost = max(cost, 0)
        guard cost <= capacity else { return }
        values[key] = (value, cost)
        totalCost += cost
        touch(key)
        while totalCost > capacity, let oldest = order.first { remove(oldest) }
    }

    public mutating func removeAll() {
        values = [:]
        order = []
        totalCost = 0
    }

    private mutating func remove(_ key: Key) {
        guard let entry = values.removeValue(forKey: key) else { return }
        totalCost -= entry.cost
        if let i = order.firstIndex(of: key) { order.remove(at: i) }
    }

    private mutating func touch(_ key: Key) {
        if let i = order.firstIndex(of: key) { order.remove(at: i) }
        order.append(key)
    }
}

/// While a call is on, a call owns the audio: voice messages do not play and the audio session is left alone.
public enum VoicePlaybackPolicy {
    public static func canPlay(isInCall: Bool) -> Bool { !isInCall }
    /// A call that starts stops a voice message that is playing.
    public static func shouldStop(isInCall: Bool, isPlaying: Bool) -> Bool { isInCall && isPlaying }
}

/// Files handed to the engine (photos picked, taken or imported) are plaintext copies in a staging folder.
public enum MediaStaging {
    /// Removes entries last changed more than `age` seconds before `now`. A missing folder is fine.
    public static func sweep(directory: URL, olderThan age: TimeInterval, now: Date) {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.contentModificationDateKey], options: []) else { return }
        for entry in entries {
            let changed = (try? entry.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            if now.timeIntervalSince(changed) > age { try? fm.removeItem(at: entry) }
        }
    }
}

/// Which voice message is playing; only one at a time.
public struct VoiceSelection: Equatable, Sendable {
    public enum Action: Equatable, Sendable { case play, pause, resume }
    public private(set) var playingID: String?
    public private(set) var isPaused = false
    public init() {}

    /// Tapping the current message pauses it, or resumes it when paused; tapping another one starts that one.
    public mutating func toggle(_ id: String) -> Action {
        if playingID == id {
            isPaused.toggle()
            return isPaused ? .pause : .resume
        }
        playingID = id
        isPaused = false
        return .play
    }

    /// Playback ended or failed; only clears when it is still the current one.
    public mutating func finished(_ id: String) {
        if playingID == id { stop() }
    }

    public mutating func stop() { playingID = nil; isPaused = false }

    public static func clamp(_ progress: Double) -> Double {
        progress.isNaN ? 0 : min(max(progress, 0), 1)
    }

    /// "0:10"
    public static func elapsedText(progress: Double, duration: Double) -> String {
        let total = duration.isFinite ? max(duration, 0) : 0
        let seconds = Int((clamp(progress) * total).rounded())
        return "\(seconds / 60):" + (seconds % 60 < 10 ? "0" : "") + "\(seconds % 60)"
    }
}
