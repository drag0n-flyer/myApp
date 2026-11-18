import Foundation

public enum VoiceLevel {
    /// Maps a meter reading in decibels (0 is the loudest) to 0...1; quieter than -50 dB is 0.
    public static func normalized(decibels: Float) -> Float {
        guard decibels.isFinite else { return decibels > 0 ? 1 : 0 }
        return min(1, max(0, (decibels + 50) / 50))
    }
}

/// A stand-in recorder for sample mode and UI tests: no microphone, a tiny file, a moving level.
/// It reports two seconds more than the clock, so a note made in a test is always long enough to send.
@MainActor
public final class SampleVoiceCapture: VoiceCapture {
    public static let headStart = 2.0
    public private(set) var currentURL: URL?
    private var startedAt = Date()

    public init() {}

    public var permission: VoicePermission { .granted }
    public func requestPermission() async -> Bool { true }
    public var level: Float { Float(0.5 + 0.4 * sin(Date().timeIntervalSince(startedAt) * 6)) }
    public var elapsed: Double { currentURL == nil ? 0 : Date().timeIntervalSince(startedAt) + Self.headStart }

    public func start() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("voice-sample-\(UUID().uuidString.lowercased()).ogg")
        try Data([0]).write(to: url)
        currentURL = url
        startedAt = Date()
    }

    public func stop() throws -> (url: URL, duration: Double) {
        guard let url = currentURL else { throw CocoaError(.fileNoSuchFile) }
        let seconds = elapsed
        currentURL = nil
        return (url, seconds)
    }

    public func cancel() {
        if let url = currentURL { try? FileManager.default.removeItem(at: url) }
        currentURL = nil
    }
}
