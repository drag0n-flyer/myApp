#if os(iOS)
import AVFoundation
#if canImport(WebRTC)
import WebRTC
#endif

/// Connects CallKit's audio session activation to WebRTC. Audio starts only after `didActivate`.
/// WebRTC is optional here: until plan 3's package is linked the WebRTC calls compile out.
@MainActor
final class AudioSessionBridge {
    static let category: AVAudioSession.Category = .playAndRecord
    static let mode: AVAudioSession.Mode = .voiceChat
    static let options: AVAudioSession.CategoryOptions = [.allowBluetooth, .allowBluetoothA2DP]

    private(set) var isActive = false

    init() {
        #if canImport(WebRTC)
        RTCAudioSession.sharedInstance().useManualAudio = true
        RTCAudioSession.sharedInstance().isAudioEnabled = false
        #endif
    }

    /// Sets the category before the call is reported or started, so CallKit activates the right session.
    func configure() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(Self.category, mode: Self.mode, options: Self.options)
    }

    func didActivate(_ session: AVAudioSession) {
        isActive = true
        #if canImport(WebRTC)
        let rtc = RTCAudioSession.sharedInstance()
        rtc.audioSessionDidActivate(session)
        rtc.isAudioEnabled = true
        #endif
    }

    func didDeactivate(_ session: AVAudioSession) {
        isActive = false
        #if canImport(WebRTC)
        let rtc = RTCAudioSession.sharedInstance()
        rtc.isAudioEnabled = false
        rtc.audioSessionDidDeactivate(session)
        #endif
    }

    func setSpeaker(_ on: Bool) {
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(on ? .speaker : .none)
    }
}
#endif
