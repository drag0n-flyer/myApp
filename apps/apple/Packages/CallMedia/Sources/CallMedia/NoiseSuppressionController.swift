import AppCore
import Foundation

/// Where the RNNoise processor is installed in the audio pipeline (the WebRTC post-processing hook).
/// The WebRTC-backed implementation lives with the media session (Task M1); it registers itself
/// in `PostProcessingHookRegistry`.
///
/// Contract:
/// - The hook delivers 48 kHz, mono, 480-sample (10 ms) frames of normalized floats, buffering to
///   that size itself.
/// - The hook OWNS the processor from `setProcessor` on. It must keep it alive, and when the processor
///   is replaced or removed it must not release it while the audio thread is inside `process`.
///   `setProcessor` must not return until the old processor is quiesced. `AudioProcessorSlot` does this;
///   implement the hook on top of one (`setProcessor` calls `slot.set`, the audio callback calls `slot.process`).
public protocol AudioPostProcessingHook: AnyObject, Sendable {
    /// Installs the processor, or removes it (bypass) when nil.
    func setProcessor(_ processor: RNNoiseProcessor?)
}

/// Holds the hook the media factory provides, if any (stand-in for `MediaFactory.hasPostProcessingHook`).
public enum PostProcessingHookRegistry {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var hook: AudioPostProcessingHook?

    public static var current: AudioPostProcessingHook? {
        get { lock.withLock { hook } }
        set { lock.withLock { hook = newValue } }
    }

    public static var hasPostProcessingHook: Bool { current != nil }
}

/// Applies the noise-suppression setting. Only one suppressor runs at a time: when the system's
/// Voice Isolation is active, or the pipeline has no hook, `.enhanced` behaves like `.standard`.
public final class NoiseSuppressionController: @unchecked Sendable {
    private let injectedHook: AudioPostProcessingHook?
    private let voiceIsolationActive: @Sendable () -> Bool
    private let lock = NSLock()
    private var processor: RNNoiseProcessor?
    private var mode: NoiseSuppression = .standard

    /// Pass `hook` to pin one; with nil the controller uses whatever `PostProcessingHookRegistry` holds
    /// at each `apply`, so a hook that registers after the controller exists is picked up.
    public init(
        hook: AudioPostProcessingHook? = nil,
        voiceIsolationActive: @escaping @Sendable () -> Bool = { VoiceIsolation.isActive }
    ) {
        self.injectedHook = hook
        self.voiceIsolationActive = voiceIsolationActive
    }

    private var hook: AudioPostProcessingHook? { injectedHook ?? PostProcessingHookRegistry.current }

    /// True when the registry has a post-processing hook, so `.enhanced` can run for controllers that resolve it there.
    public static var enhancedAvailable: Bool { PostProcessingHookRegistry.hasPostProcessingHook }

    /// Same question for this controller: true when it has a hook (pinned or registered) right now.
    public var canRunEnhanced: Bool { hook != nil }

    public var isVoiceIsolationActive: Bool { voiceIsolationActive() }

    /// What actually runs after the fallbacks: `.enhanced` becomes `.standard` without a hook or with Voice Isolation.
    public var effectiveMode: NoiseSuppression { lock.withLock { mode } }

    public func apply(_ requested: NoiseSuppression) {
        lock.withLock {
            let hook = self.hook
            if requested == .enhanced, let hook, !isVoiceIsolationActive {
                if processor == nil { processor = RNNoiseProcessor() }
                hook.setProcessor(processor)
                mode = .enhanced
            } else {
                // The hook owns the processor now; it releases it only once the audio thread is done with it.
                processor = nil
                hook?.setProcessor(nil)
                mode = requested == .enhanced ? .standard : requested
            }
        }
    }
}
