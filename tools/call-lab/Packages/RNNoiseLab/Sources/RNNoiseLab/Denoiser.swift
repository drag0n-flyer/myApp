import CRNNoise

/// RNNoise wrapper: 48 kHz mono, 10 ms frames (480 samples), samples scaled to the int16 range.
public final class Denoiser {
    public static let frameSize = 480
    private let state: OpaquePointer

    public init() {
        // Model is compiled in (rnnoise_data.c); NULL selects it.
        guard let st = rnnoise_create(nil) else { fatalError("rnnoise_create failed") }
        state = st
    }

    deinit { rnnoise_destroy(state) }

    /// Denoises one frame in place. Returns the voice-activity probability (0...1).
    public func process(_ frame: inout [Float]) -> Float {
        precondition(frame.count == Denoiser.frameSize, "frame must hold \(Denoiser.frameSize) samples")
        return frame.withUnsafeMutableBufferPointer { buf in
            rnnoise_process_frame(state, buf.baseAddress, buf.baseAddress)
        }
    }
}
