import XCTest
import AppCore
@testable import CallMedia

final class FakeHook: AudioPostProcessingHook, @unchecked Sendable {
    private(set) var installed: RNNoiseProcessor?
    private(set) var calls = 0
    func setProcessor(_ processor: RNNoiseProcessor?) { installed = processor; calls += 1 }
}

final class NoiseTests: XCTestCase {
    private func loadWAV(_ name: String) throws -> [Float] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "wav", subdirectory: "Fixtures"))
        let data = try Data(contentsOf: url)
        XCTAssertEqual(data.count, 44 + 48_000 * 5 * 2, "5 s, 48 kHz, 16-bit mono")
        let body = data.dropFirst(44)
        return stride(from: body.startIndex, to: body.endIndex, by: 2).map {
            Float(Int16(bitPattern: UInt16(body[$0]) | UInt16(body[$0 + 1]) << 8)) / 32768
        }
    }

    private func denoise(_ x: [Float]) -> [Float] {
        let p = RNNoiseProcessor()
        var out: [Float] = []
        for start in stride(from: 0, to: x.count - 479, by: 480) {
            var frame = Array(x[start..<start + 480])
            p.process(&frame)
            out += frame
        }
        return out
    }

    private func energyDB(_ x: ArraySlice<Float>) -> Double {
        let e = x.reduce(0.0) { $0 + Double($1 * $1) } / Double(x.count)
        return 10 * log10(max(e, 1e-20))
    }

    private let lead = 0..<24_000        // 0.5 s non-speech lead-in
    private let speech = 24_000..<240_000

    func testRNNoiseReducesNoise() throws {
        let noisy = try loadWAV("speech-fan")
        let out = denoise(noisy)
        let drop = energyDB(noisy[lead]) - energyDB(out[lead])
        XCTAssertGreaterThanOrEqual(drop, 10, "lead-in energy drop \(drop) dB")
    }

    func testRNNoiseKeepsSpeech() throws {
        let clean = try loadWAV("speech-clean")
        let out = denoise(clean)
        let change = abs(energyDB(clean[speech]) - energyDB(out[speech]))
        XCTAssertLessThan(change, 3, "speech energy change \(change) dB")
    }

    func testOneSuppressorAtATime() {
        let hook = FakeHook()
        let c = NoiseSuppressionController(hook: hook, voiceIsolationActive: { true })
        XCTAssertTrue(c.isVoiceIsolationActive)
        c.apply(.enhanced)
        XCTAssertNil(hook.installed)
    }

    func testEnhancedInstallsAndStandardRemoves() {
        let hook = FakeHook()
        let c = NoiseSuppressionController(hook: hook, voiceIsolationActive: { false })
        c.apply(.enhanced)
        XCTAssertNotNil(hook.installed)
        c.apply(.standard)
        XCTAssertNil(hook.installed)
        c.apply(.enhanced)
        c.apply(.off)
        XCTAssertNil(hook.installed)
    }

    func testHookMissingFallsBack() {
        XCTAssertFalse(PostProcessingHookRegistry.hasPostProcessingHook)
        XCTAssertFalse(NoiseSuppressionController.enhancedAvailable)
        let c = NoiseSuppressionController(hook: nil, voiceIsolationActive: { false })
        c.apply(.enhanced)
        XCTAssertEqual(c.effectiveMode, .standard)
        c.apply(.off)
        XCTAssertEqual(c.effectiveMode, .off)
    }
}
