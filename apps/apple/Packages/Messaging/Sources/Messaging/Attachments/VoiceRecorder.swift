#if canImport(AVFoundation)
import AVFoundation
import Foundation

public enum VoiceRecorderError: Error, Equatable, Sendable {
    case permissionDenied
    case couldNotStart
    case notRecording
    case couldNotConvert
}

/// Records a voice note as Opus, 16 kHz mono, 16 kbps (Design 9; values in `MediaPlan.Voice`). The recorder
/// writes CAF; `stop()` repackages the packets as Ogg Opus (RFC 7845) and returns that file.
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
    private var startedAt: TimeInterval = 0

    /// Recordings are written into `directory` (a temporary folder by default).
    public init(directory: URL = FileManager.default.temporaryDirectory) { self.directory = directory }

    public var isRecording: Bool { recorder?.isRecording ?? false }
    /// Seconds recorded so far.
    public var elapsed: Double { recorder == nil ? 0 : max(recorder?.currentTime ?? 0, measured) }
    private var measured: Double { ProcessInfo.processInfo.systemUptime - startedAt }

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
        let url = directory.appendingPathComponent("voice-\(UUID().uuidString.lowercased()).\(MediaPlan.Voice.recordingExtension)")
        let rec = try AVAudioRecorder(url: url, settings: Self.settings)
        guard rec.prepareToRecord(), rec.record(forDuration: MediaPlan.Voice.maxDuration) else {
            throw VoiceRecorderError.couldNotStart
        }
        recorder = rec
        fileURL = url
        startedAt = ProcessInfo.processInfo.systemUptime
    }

    /// Stops and returns the Ogg Opus file and its length in seconds.
    @discardableResult
    public func stop() throws -> (URL, Double) {
        guard let rec = recorder, let url = fileURL else { throw VoiceRecorderError.notRecording }
        // Read before stopping; after an auto-stop at the limit the recorder reports 0.
        let seconds = MediaPlan.Voice.finalDuration(recorderTime: rec.currentTime, measured: measured)
        rec.stop()
        recorder = nil
        fileURL = nil
        deactivate()
        do { return (try CAFOpusConverter.convertToOgg(caf: url), seconds) } catch {
            try? FileManager.default.removeItem(at: url)
            throw VoiceRecorderError.couldNotConvert
        }
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
