import Foundation
import AVFoundation
@preconcurrency import WebRTC

/// What runs on the captured microphone audio, after WebRTC's own processing (patch 0004).
/// `process` runs on the audio thread once per 10 ms frame: do not block or allocate.
public protocol CaptureProcessor: AnyObject, Sendable {
    func initialize(sampleRateHz: Int, channels: Int)
    func process(channels: UnsafePointer<UnsafeMutablePointer<Float>>, frames: Int, channelCount: Int)
    func reset()
}

/// Thin bridge from a `CaptureProcessor` to patch 0004's `RTCAudioCapturePostProcessing`.
/// Noise suppression reaches it through `NoiseCaptureBridge`.
public final class CapturePostProcessingAdapter: NSObject, RTCAudioCapturePostProcessing {
    private let processor: CaptureProcessor
    public init(_ processor: CaptureProcessor) { self.processor = processor }

    public func initialize(withSampleRateHz sampleRateHz: Int, channels: Int) {
        processor.initialize(sampleRateHz: sampleRateHz, channels: channels)
    }
    public func processChannels(_ channels: UnsafePointer<UnsafeMutablePointer<Float>>, frames: Int, channelCount: Int) {
        processor.process(channels: channels, frames: frames, channelCount: channelCount)
    }
    public func reset() { processor.reset() }
}

/// One `RTCPeerConnectionFactory` per process.
/// Default encoder and decoder factories (H.264 through VideoToolbox is preferred by the SDP);
/// the capture hook is installed when `captureProcessor` is set before the first `shared()` call
/// and the build has patch 0004's hook (`hasCapturePostProcessingHook`).
public enum MediaFactory {
    /// All process-wide state, behind one lock.
    // @unchecked Sendable: every field is read and written under `lock`.
    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var captureProcessor: CaptureProcessor?
        var instance: RTCPeerConnectionFactory?
        // The factory holds the hook weakly, so the adapter is kept here for the process lifetime.
        var adapter: CapturePostProcessingAdapter?
    }
    private static let state = State()

    /// The capture hook processor. Set before the first `shared()` call; read once there.
    public static var captureProcessor: CaptureProcessor? {
        get { state.lock.withLock { state.captureProcessor } }
        set { state.lock.withLock { state.captureProcessor = newValue } }
    }

    public static func shared() -> RTCPeerConnectionFactory {
        state.lock.lock(); defer { state.lock.unlock() }
        if let f = state.instance { return f }
        RTCInitializeSSL()
        #if os(iOS)
        // CallKit owns the audio session; WebRTC must not start audio before it is activated.
        let s = RTCAudioSession.sharedInstance()
        s.useManualAudio = true
        s.isAudioEnabled = false
        #endif
        let encoder = RTCDefaultVideoEncoderFactory(), decoder = RTCDefaultVideoDecoderFactory()
        let f: RTCPeerConnectionFactory
        if let processor = state.captureProcessor, RTCPeerConnectionFactory.hasCapturePostProcessingHook {
            let a = CapturePostProcessingAdapter(processor)
            state.adapter = a
            f = RTCPeerConnectionFactory(encoderFactory: encoder, decoderFactory: decoder, capturePostProcessing: a)
        } else {
            f = RTCPeerConnectionFactory(encoderFactory: encoder, decoderFactory: decoder)
        }
        state.instance = f
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
