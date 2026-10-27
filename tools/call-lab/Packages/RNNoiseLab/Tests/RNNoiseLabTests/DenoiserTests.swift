import Testing
@testable import RNNoiseLab

struct DenoiserTests {
    @Test func frameSize() {
        #expect(Denoiser.frameSize == 480)
    }

    @Test func silenceStaysSilent() {
        let d = Denoiser()
        var maxAbs: Float = 0
        var vad: Float = 0
        for _ in 0..<100 {
            var f = [Float](repeating: 0, count: Denoiser.frameSize)
            vad = d.process(&f)
            maxAbs = max(maxAbs, f.map { abs($0) }.max() ?? 0)
        }
        #expect(maxAbs < 1)
        #expect(vad < 0.1)
    }

    @Test func reducesWhiteNoise() {
        let d = Denoiser()
        var rng = SystemRandomNumberGenerator()
        // Uniform in [-a, a] has RMS a/sqrt(3); a = 3000*sqrt(3) gives RMS 3000.
        let a = Float(3000.0 * 3.0.squareRoot())
        var inSq: Double = 0, outSq: Double = 0, n = 0
        for _ in 0..<100 {
            var f = (0..<Denoiser.frameSize).map { _ in Float.random(in: -a...a, using: &rng) }
            inSq += f.reduce(0) { $0 + Double($1 * $1) }
            _ = d.process(&f)
            outSq += f.reduce(0) { $0 + Double($1 * $1) }
            n += f.count
        }
        let inRMS = (inSq / Double(n)).squareRoot()
        let outRMS = (outSq / Double(n)).squareRoot()
        #expect(abs(inRMS - 3000) < 100)
        #expect(outRMS < inRMS * 0.5)
    }
}
