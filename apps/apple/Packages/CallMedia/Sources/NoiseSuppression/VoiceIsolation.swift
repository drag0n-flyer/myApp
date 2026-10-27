#if canImport(AVFoundation) && (os(iOS) || os(macOS))
import AVFoundation

/// System Voice Isolation (iOS 15+, macOS 12+). UNVERIFIED: Apple-only, not compiled on Linux.
public enum VoiceIsolation {
    /// True while the system's Voice Isolation is the active microphone mode.
    public static var isActive: Bool {
        AVCaptureDevice.activeMicrophoneMode == .voiceIsolation
    }

    /// Show the hint when the person has not turned Voice Isolation on.
    public static var shouldShowHint: Bool {
        AVCaptureDevice.preferredMicrophoneMode != .voiceIsolation
            || AVCaptureDevice.activeMicrophoneMode != .voiceIsolation
    }

    /// Opens the system microphone-mode picker (Control Center).
    public static func showPicker() {
        AVCaptureDevice.showSystemUserInterface(.microphoneModes)
    }
}
#else
/// No system Voice Isolation on this platform.
public enum VoiceIsolation {
    public static var isActive: Bool { false }
    public static var shouldShowHint: Bool { false }
    public static func showPicker() {}
}
#endif
