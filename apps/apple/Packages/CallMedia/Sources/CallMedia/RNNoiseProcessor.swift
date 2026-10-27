import RNNoise

/// RNNoise wrapper: 48 kHz mono, 10 ms frames of 480 samples.
/// Samples are normalized (-1...1); the processor scales to RNNoise's int16 range internally.
/// One instance holds the network state of one audio stream and is not thread-safe:
/// call it from a single audio thread.
public final class RNNoiseProcessor: @unchecked Sendable {
    public static let frameSize = 480
    private let state: OpaquePointer
    private var scratch = [Float](repeating: 0, count: RNNoiseProcessor.frameSize)

    public init() {
        // The model is compiled in (rnnoise_data.c); nil selects it.
        guard let st = rnnoise_create(nil) else { fatalError("rnnoise_create failed") }
        state = st
    }

    deinit { rnnoise_destroy(state) }

    /// Denoises one 480-sample frame in place.
    public func process(_ frame: inout [Float]) {
        precondition(frame.count == Self.frameSize, "frame must hold \(Self.frameSize) samples")
        frame.withUnsafeMutableBufferPointer { process($0.baseAddress!) }
    }

    /// Pointer variant for the real-time audio hook; `samples` must point at 480 floats.
    public func process(_ samples: UnsafeMutablePointer<Float>) {
        for i in 0..<Self.frameSize { scratch[i] = samples[i] * 32768 }
        scratch.withUnsafeMutableBufferPointer { buf in
            _ = rnnoise_process_frame(state, buf.baseAddress, buf.baseAddress)
        }
        for i in 0..<Self.frameSize { samples[i] = scratch[i] / 32768 }
    }
}
