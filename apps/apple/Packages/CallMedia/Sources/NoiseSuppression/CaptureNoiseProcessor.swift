import Foundation

/// Which capture frames RNNoise may touch. Pure, so it is tested on Linux.
public enum CaptureFramePolicy {
    public static let sampleRateHz = 48_000
    /// Peak above this means the samples are not in the normalized -1...1 range patch 0004 delivers: leave them alone.
    public static let maxPeak: Float = 1.5

    /// RNNoise needs 48 kHz frames of exactly 480 samples (10 ms) and at least one channel.
    public static func accepts(sampleRateHz rate: Int, frames: Int, channelCount: Int) -> Bool {
        rate == sampleRateHz && frames == RNNoiseProcessor.frameSize && channelCount >= 1
    }

    /// True when every sample is within `maxPeak`.
    public static func inRange(_ samples: UnsafePointer<Float>, count: Int) -> Bool {
        for i in 0..<count where !(abs(samples[i]) <= maxPeak) { return false }   // also rejects NaN
        return true
    }
}

/// The capture hook's frame gate in front of an `AudioProcessorSlot`. Holds no WebRTC types.
///
/// Rules:
/// - Anything RNNoise cannot take (wrong rate or frame size, no `initialize` call, out-of-range samples)
///   passes through untouched, and so does everything when no processor is installed (suppression off).
/// - With several channels only channel 0 is denoised; channels 1...n are never modified.
///   (WebRTC delivers the microphone as mono; this keeps the unexpected case harmless.)
public final class CaptureNoiseProcessor: AudioPostProcessingHook, @unchecked Sendable {   // `rate` is behind `lock`; the slot locks itself
    private let slot = AudioProcessorSlot()
    private let lock = NSLock()
    private var rate = 0

    public init() {}

    public func setProcessor(_ processor: RNNoiseProcessor?) { slot.set(processor) }

    public func initialize(sampleRateHz: Int, channels: Int) { lock.withLock { rate = sampleRateHz } }

    /// True when the pipeline runs at a rate RNNoise supports, so `.enhanced` can really run.
    public var isActive: Bool { lock.withLock { rate } == CaptureFramePolicy.sampleRateHz }

    public func process(channels: UnsafePointer<UnsafeMutablePointer<Float>>, frames: Int, channelCount: Int) {
        guard CaptureFramePolicy.accepts(sampleRateHz: lock.withLock { rate }, frames: frames, channelCount: channelCount),
              slot.isInstalled,
              CaptureFramePolicy.inRange(channels[0], count: frames) else { return }
        slot.process(channels[0], count: frames)
    }
}
