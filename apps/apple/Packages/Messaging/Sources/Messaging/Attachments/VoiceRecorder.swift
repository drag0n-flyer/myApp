#if canImport(AVFoundation)
import AVFoundation
import Foundation

public enum VoiceRecorderError: Error, Equatable, Sendable {
    case permissionDenied
    case couldNotStart
    case notRecording
}

/// Records a voice note as Opus in CAF, 16 kHz mono, 16 kbps (Design 9; values in `MediaPlan.Voice`).
/// Main-actor only: it is driven by the composer.
@MainActor
public final class VoiceRecorder {
    public static let settings: [String: Any] = [
        AVFormatIDKey: kAudioFormatOpus,
        AVSampleRateKey: MediaPlan.Voice.sampleRate,
        AVNumberOfChannelsKey: MediaPlan.Voice.channels,
        AVEncoderBitRateKey: MediaPlan.Voice.bitRate,
    ]

    private let directory: URL
    private var recorder: AVAudioRecorder?
    private var fileURL: URL?

    /// Recordings are written into `directory` (a temporary folder by default).
    public init(directory: URL = FileManager.default.temporaryDirectory) { self.directory = directory }

    public var isRecording: Bool { recorder?.isRecording ?? false }
    /// Seconds recorded so far.
    public var elapsed: Double { recorder?.currentTime ?? 0 }

    /// Asks for microphone access if needed. Returns false when it is denied.
    public static func requestPermission() async -> Bool {
        #if os(iOS)
        return await AVAudioApplication.requestRecordPermission()
        #else
        return await AVCaptureDevice.requestAccess(for: .audio)
        #endif
    }

    public func start() throws {
        cancel()
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)
        #endif
        let url = directory.appendingPathComponent("voice-\(UUID().uuidString.lowercased()).\(MediaPlan.Voice.fileExtension)")
        let rec = try AVAudioRecorder(url: url, settings: Self.settings)
        guard rec.prepareToRecord(), rec.record(forDuration: MediaPlan.Voice.maxDuration) else {
            throw VoiceRecorderError.couldNotStart
        }
        recorder = rec
        fileURL = url
    }

    /// Stops and returns the file and its length in seconds.
    @discardableResult
    public func stop() throws -> (URL, Double) {
        guard let rec = recorder, let url = fileURL else { throw VoiceRecorderError.notRecording }
        let seconds = MediaPlan.Voice.clamped(rec.currentTime)
        rec.stop()
        recorder = nil
        fileURL = nil
        deactivate()
        return (url, seconds)
    }

    /// Stops and deletes the recording.
    public func cancel() {
        guard let rec = recorder else { return }
        rec.stop()
        rec.deleteRecording()
        recorder = nil
        fileURL = nil
        deactivate()
    }

    private func deactivate() {
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }
}
#endif
