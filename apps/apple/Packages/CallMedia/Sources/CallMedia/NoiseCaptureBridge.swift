import Foundation
import NoiseSuppression

/// Runs several `CaptureProcessor`s on the one capture hook, in array order.
///
/// Order matters because the hook is a single slot (`MediaFactory.captureProcessor`):
/// sources and test signals (such as the loopback tone) come first, noise suppression comes last,
/// so it cleans the final signal. In production the chain is just `[NoiseCaptureBridge]`.
public final class CaptureProcessorChain: CaptureProcessor, @unchecked Sendable {
    private let processors: [CaptureProcessor]
    public init(_ processors: [CaptureProcessor]) { self.processors = processors }

    public func initialize(sampleRateHz: Int, channels: Int) {
        for p in processors { p.initialize(sampleRateHz: sampleRateHz, channels: channels) }
    }
    public func process(channels: UnsafePointer<UnsafeMutablePointer<Float>>, frames: Int, channelCount: Int) {
        for p in processors { p.process(channels: channels, frames: frames, channelCount: channelCount) }
    }
    public func reset() { for p in processors { p.reset() } }
}

/// Thin conformance between M3's `AudioPostProcessingHook` (installs an `RNNoiseProcessor`) and M1's `CaptureProcessor`
/// (what `MediaFactory.captureProcessor` runs on the microphone audio).
///
/// RNNoise needs 48 kHz mono frames of exactly 480 samples. Anything else (another rate, another frame size)
/// passes through untouched. With more than one channel, channel 0 is denoised and copied to the others.
/// The slot is empty (bypass) until `NoiseSuppressionController` installs a processor.
/// UNVERIFIED: depends on patch 0004 delivering normalized floats (-1...1) in 10 ms frames; not compiled here.
public final class NoiseCaptureBridge: CaptureProcessor, AudioPostProcessingHook, @unchecked Sendable {
    private let slot = AudioProcessorSlot()
    private let lock = NSLock()
    private var supported = false

    public init() {}

    // MARK: AudioPostProcessingHook
    public func setProcessor(_ processor: RNNoiseProcessor?) { slot.set(processor) }

    // MARK: CaptureProcessor
    public func initialize(sampleRateHz: Int, channels: Int) {
        lock.withLock { supported = sampleRateHz == 48_000 }
    }

    public func process(channels: UnsafePointer<UnsafeMutablePointer<Float>>, frames: Int, channelCount: Int) {
        guard channelCount > 0, frames == RNNoiseProcessor.frameSize, lock.withLock({ supported }) else { return }
        slot.process(channels[0], count: frames)
        if channelCount > 1 {
            for c in 1..<channelCount { channels[c].update(from: channels[0], count: frames) }
        }
    }

    public func reset() {}
}

extension NoiseCaptureBridge {
    /// Wires noise suppression into the capture hook. Call once, before the first `MediaFactory.shared()`.
    /// `before` runs ahead of noise suppression (sources and test signals; see `CaptureProcessorChain`).
    /// Then use `NoiseSuppressionController()` (it finds the bridge through `PostProcessingHookRegistry`)
    /// and point `WebRTCMediaSession.noiseSuppressionHandler` at its `apply`.
    @discardableResult
    public static func install(before: [CaptureProcessor] = []) -> NoiseCaptureBridge {
        let bridge = NoiseCaptureBridge()
        PostProcessingHookRegistry.current = bridge
        MediaFactory.captureProcessor = CaptureProcessorChain(before + [bridge])
        return bridge
    }
}
