import Foundation
import AVFoundation
import WebRTC

/// One `RTCPeerConnectionFactory` per process.
/// H.264 hardware encoder and decoder factories; the audio processing module is the
/// optional hook `audioProcessingModule` (Task M3 sets it before the first `shared()` call).
public enum MediaFactory {
    nonisolated(unsafe) public static var audioProcessingModule: RTCAudioProcessingModule?

    nonisolated(unsafe) private static var instance: RTCPeerConnectionFactory?
    private static let lock = NSLock()

    public static func shared() -> RTCPeerConnectionFactory {
        lock.lock(); defer { lock.unlock() }
        if let f = instance { return f }
        RTCInitializeSSL()
        #if os(iOS)
        // CallKit owns the audio session; WebRTC must not start audio before it is activated.
        let s = RTCAudioSession.sharedInstance()
        s.useManualAudio = true
        s.isAudioEnabled = false
        #endif
        let f = RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory(),
            audioProcessingModule: audioProcessingModule)
        instance = f
        return f
    }

    #if os(iOS)
    /// Call from CallKit's `didActivate`.
    public static func audioSessionDidActivate(_ session: AVAudioSession) {
        let s = RTCAudioSession.sharedInstance()
        s.audioSessionDidActivate(session)
        s.isAudioEnabled = true
    }

    /// Call from CallKit's `didDeactivate`.
    public static func audioSessionDidDeactivate(_ session: AVAudioSession) {
        let s = RTCAudioSession.sharedInstance()
        s.isAudioEnabled = false
        s.audioSessionDidDeactivate(session)
    }
    #endif
}
