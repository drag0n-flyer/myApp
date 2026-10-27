import Testing
@testable import RNNoiseLab

struct RateCheckTests {
    @Test func rnnoiseRequires48k() {
        #expect(BenchRate.refusal(mode: "rnnoise", sampleRate: 48_000) == nil)
        let msg = BenchRate.refusal(mode: "rnnoise", sampleRate: 44_100)
        #expect(msg != nil)
        #expect(msg?.contains("44100") == true)
        #expect(msg?.contains("48000") == true)
    }

    @Test func otherModesAcceptAnyRate() {
        #expect(BenchRate.refusal(mode: "raw", sampleRate: 16_000) == nil)
        #expect(BenchRate.refusal(mode: "vpio", sampleRate: 44_100) == nil)
    }
}
