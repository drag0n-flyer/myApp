import AppCore
import Foundation
import Messaging
import Observation

/// What the composer hands to the app when the person attaches or records something.
enum ComposerAttachment {
    case photo(URL)
    case file(URL)
    case voice(URL, duration: Double)
}

/// Drives voice recording for the composer, using `VoiceRecorder` from the `Messaging` package.
@MainActor @Observable
final class VoiceRecordingController {
    private(set) var isRecording = false
    private(set) var denied = false
    private(set) var startedAt: Date?

    private let recorder = VoiceRecorder()

    func start() async {
        guard !isRecording else { return }
        guard await VoiceRecorder.requestPermission() else { denied = true; return }
        denied = false
        do {
            try recorder.start()
            startedAt = Date()
            isRecording = true
        } catch { isRecording = false }
    }

    /// Stops and returns the recording, or nil when it was too short to keep.
    func finish() -> (URL, Double)? {
        guard isRecording else { return nil }
        isRecording = false
        startedAt = nil
        guard let result = try? recorder.stop() else { recorder.cancel(); return nil }
        if MediaPlan.Voice.outcome(duration: result.1) == .tooShort {
            try? FileManager.default.removeItem(at: result.0)
            return nil
        }
        return result
    }

    func dismissDenied() { denied = false }

    func cancel() {
        recorder.cancel()
        isRecording = false
        startedAt = nil
    }
}
