import AppCore
import Foundation
#if canImport(Messaging)
import Messaging
#endif
import Observation

/// What the composer hands to the app when the person attaches or records something.
enum ComposerAttachment {
    case photo(URL)
    case file(URL)
    case voice(URL, duration: Double)
}

/// Drives voice recording for the composer. The real recording uses `VoiceRecorder` from the
/// `Messaging` package; until the app target links it, `isAvailable` is false and the composer
/// keeps its sample-data behaviour.
@MainActor @Observable
final class VoiceRecordingController {
    private(set) var isRecording = false
    private(set) var denied = false
    private(set) var startedAt: Date?

    #if canImport(Messaging)
    private let recorder = VoiceRecorder()
    static let isAvailable = true
    #else
    static let isAvailable = false
    #endif

    func start() async {
        #if canImport(Messaging)
        guard !isRecording else { return }
        guard await VoiceRecorder.requestPermission() else { denied = true; return }
        denied = false
        do {
            try recorder.start()
            startedAt = Date()
            isRecording = true
        } catch { isRecording = false }
        #endif
    }

    /// Stops and returns the recording, or nil when it was too short to keep.
    func finish() -> (URL, Double)? {
        #if canImport(Messaging)
        guard isRecording, let result = try? recorder.stop() else { return nil }
        isRecording = false
        startedAt = nil
        if MediaPlan.Voice.outcome(duration: result.1) == .tooShort {
            try? FileManager.default.removeItem(at: result.0)
            return nil
        }
        return result
        #else
        return nil
        #endif
    }

    func dismissDenied() { denied = false }

    func cancel() {
        #if canImport(Messaging)
        recorder.cancel()
        #endif
        isRecording = false
        startedAt = nil
    }
}
