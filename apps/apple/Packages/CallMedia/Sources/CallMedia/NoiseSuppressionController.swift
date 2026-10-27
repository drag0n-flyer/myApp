import AppCore
import Foundation

/// Where the RNNoise processor is installed in the audio pipeline (the WebRTC post-processing hook).
/// The WebRTC-backed implementation lives with the media session (Task M1); it registers itself
/// in `PostProcessingHookRegistry`. The hook calls the processor with 480-sample frames.
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
    private let hook: AudioPostProcessingHook?
    private let voiceIsolationActive: @Sendable () -> Bool
    private let lock = NSLock()
    private var processor: RNNoiseProcessor?
    private var mode: NoiseSuppression = .standard

    public init(
        hook: AudioPostProcessingHook? = PostProcessingHookRegistry.current,
        voiceIsolationActive: @escaping @Sendable () -> Bool = { VoiceIsolation.isActive }
    ) {
        self.hook = hook
        self.voiceIsolationActive = voiceIsolationActive
    }

    /// True when the pipeline has a post-processing hook, so `.enhanced` can run.
    public static var enhancedAvailable: Bool { PostProcessingHookRegistry.hasPostProcessingHook }

    public var isVoiceIsolationActive: Bool { voiceIsolationActive() }

    /// What actually runs after the fallbacks: `.enhanced` becomes `.standard` without a hook or with Voice Isolation.
    public var effectiveMode: NoiseSuppression { lock.withLock { mode } }

    public func apply(_ requested: NoiseSuppression) {
        lock.withLock {
            if requested == .enhanced, hook != nil, !isVoiceIsolationActive {
                if processor == nil { processor = RNNoiseProcessor() }
                hook?.setProcessor(processor)
                mode = .enhanced
            } else {
                processor = nil
                hook?.setProcessor(nil)
                mode = requested == .enhanced ? .standard : requested
            }
        }
    }
}
