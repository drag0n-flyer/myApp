import Foundation

/// Holds the installed processor for an audio hook and makes swaps safe against the audio thread.
///
/// `process` runs the processor while holding the lock, and `set` takes the same lock, so when `set`
/// returns the audio thread is no longer inside the old processor and never enters it again.
/// The old processor is released after the lock is dropped, so `rnnoise_destroy` can never run
/// while a frame is being processed. Hooks should use a slot instead of keeping their own reference.
/// The lock is held for one 10 ms frame at most and is uncontended except during a mode switch.
public final class AudioProcessorSlot: @unchecked Sendable {
    private let lock = NSLock()
    private var processor: RNNoiseProcessor?
    private var processed = 0

    public init() {}

    public var isInstalled: Bool { lock.withLock { processor != nil } }
    /// Frames that went through a processor so far.
    public var processedFrames: Int { lock.withLock { processed } }

    /// Installs or removes (nil) the processor. On return the previous one is quiesced.
    public func set(_ new: RNNoiseProcessor?) {
        let old: RNNoiseProcessor? = lock.withLock {
            let o = processor
            processor = new
            return o
        }
        _ = old  // released here, outside the lock and after any in-flight frame finished
    }

    /// Audio-thread entry: processes one 480-sample frame in place, or leaves it untouched when bypassed.
    public func process(_ samples: UnsafeMutablePointer<Float>, count: Int) {
        lock.lock()
        defer { lock.unlock() }
        guard let processor else { return }
        processor.process(samples, count: count)
        processed += 1
    }
}
