import Foundation
import NoiseSuppression

/// Runs several `CaptureProcessor`s on the one capture hook, in array order.
///
/// Order matters because the hook is a single slot (`MediaFactory.captureProcessor`):
/// sources and test signals (such as the loopback tone) come first, noise suppression comes last,
/// so it cleans the final signal. In production the chain is just `[NoiseCaptureBridge]`.
public final class CaptureProcessorChain: CaptureProcessor {   // immutable Sendable state, so plain Sendable
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
/// (what `MediaFactory.captureProcessor` runs on the microphone audio). All frame rules live in the Linux-tested
/// `CaptureNoiseProcessor` (NoiseSuppression target): wrong rate or frame size, out-of-range samples and
/// suppression off pass through; only channel 0 is ever denoised.
///
/// Patch 0004 delivers full-band floats in [-1, 1] in 10 ms frames (it scales WebRTC's FloatS16 around the delegate call).
/// UNVERIFIED on a device: not compiled here.
public final class NoiseCaptureBridge: CaptureProcessor, AudioPostProcessingHook {   // immutable Sendable state, so plain Sendable
    private let core = CaptureNoiseProcessor()
    public init() {}

    /// False while the pipeline runs at a rate RNNoise cannot take (`.enhanced` then does nothing).
    public var isActive: Bool { core.isActive }

    public func setProcessor(_ processor: RNNoiseProcessor?) { core.setProcessor(processor) }
    public func initialize(sampleRateHz: Int, channels: Int) { core.initialize(sampleRateHz: sampleRateHz, channels: channels) }
    public func process(channels: UnsafePointer<UnsafeMutablePointer<Float>>, frames: Int, channelCount: Int) {
        core.process(channels: channels, frames: frames, channelCount: channelCount)
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
