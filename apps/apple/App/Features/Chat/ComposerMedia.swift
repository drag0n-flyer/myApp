import AppCore
import Foundation
import Messaging

/// What the composer hands to the app when the person attaches or records something.
enum ComposerAttachment {
    case photo(URL)
    case file(URL)
    case voice(URL, duration: Double)
}

/// The app's microphone for `VoiceSession`: `VoiceRecorder` (Opus, 16 kHz mono, Ogg file) behind `VoiceCapture`.
@MainActor
final class VoiceRecordingController: VoiceCapture {
    private let recorder = VoiceRecorder()

    var permission: VoicePermission {
        switch VoiceRecorder.permissionStatus {
        case .granted: .granted
        case .denied: .denied
        case .undetermined: .undetermined
        }
    }

    func requestPermission() async -> Bool { await VoiceRecorder.requestPermission() }

    func start() throws { try recorder.start() }

    var level: Float { VoiceLevel.normalized(decibels: recorder.averagePowerDecibels) }

    var elapsed: Double { recorder.elapsed }

    func stop() throws -> (url: URL, duration: Double) {
        let result = try recorder.stop()
        return (result.0, result.1)
    }

    func cancel() { recorder.cancel() }
}
