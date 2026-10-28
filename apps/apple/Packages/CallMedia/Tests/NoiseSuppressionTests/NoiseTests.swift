import XCTest
import AppCore
@testable import NoiseSuppression

final class FakeHook: AudioPostProcessingHook, @unchecked Sendable {
    private(set) var installed: RNNoiseProcessor?
    private(set) var calls = 0
    func setProcessor(_ processor: RNNoiseProcessor?) { installed = processor; calls += 1 }
}

final class SlotHook: AudioPostProcessingHook, @unchecked Sendable {
    let slot = AudioProcessorSlot()
    func setProcessor(_ processor: RNNoiseProcessor?) { slot.set(processor) }
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

    /// Speech must survive while noise goes: a no-op fails the lead-in drop, a zeroing processor fails the speech check.
    func testRNNoiseKeepsSpeech() throws {
        let clean = try loadWAV("speech-clean")
        let outClean = denoise(clean)
        let change = abs(energyDB(clean[speech]) - energyDB(outClean[speech]))
        XCTAssertLessThan(change, 3, "clean speech energy change \(change) dB")

        let noisy = try loadWAV("speech-fan")
        let outNoisy = denoise(noisy)
        let drop = energyDB(noisy[lead]) - energyDB(outNoisy[lead])
        XCTAssertGreaterThanOrEqual(drop, 10, "noise must fall in the same run, drop \(drop) dB")
        let speechVsClean = abs(energyDB(outNoisy[speech]) - energyDB(clean[speech]))
        XCTAssertLessThan(speechVsClean, 6, "denoised speech vs clean speech \(speechVsClean) dB")
    }

    func testOutputIsClamped() {
        let p = RNNoiseProcessor()
        var frame = [Float](repeating: 0, count: 480)
        for i in 0..<480 { frame[i] = i % 2 == 0 ? 1 : -1 }
        for _ in 0..<20 { p.process(&frame); XCTAssertTrue(frame.allSatisfy { abs($0) <= 1 }); for i in 0..<480 { frame[i] = i % 2 == 0 ? 1 : -1 } }
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
        let saved = PostProcessingHookRegistry.current
        PostProcessingHookRegistry.current = nil
        defer { PostProcessingHookRegistry.current = saved }
        XCTAssertFalse(PostProcessingHookRegistry.hasPostProcessingHook)
        XCTAssertFalse(NoiseSuppressionController.enhancedAvailable)
        let c = NoiseSuppressionController(hook: nil, voiceIsolationActive: { false })
        c.apply(.enhanced)
        XCTAssertEqual(c.effectiveMode, .standard)
        c.apply(.off)
        XCTAssertEqual(c.effectiveMode, .off)
    }

    func testHookRegisteredAfterInitIsPickedUp() {
        let saved = PostProcessingHookRegistry.current
        PostProcessingHookRegistry.current = nil
        defer { PostProcessingHookRegistry.current = saved }
        let c = NoiseSuppressionController(voiceIsolationActive: { false })
        XCTAssertFalse(c.canRunEnhanced)
        XCTAssertFalse(NoiseSuppressionController.enhancedAvailable)
        let hook = FakeHook()
        PostProcessingHookRegistry.current = hook
        XCTAssertTrue(c.canRunEnhanced)
        XCTAssertTrue(NoiseSuppressionController.enhancedAvailable)
        c.apply(.enhanced)
        XCTAssertNotNil(hook.installed)
        XCTAssertEqual(c.effectiveMode, .enhanced)
    }

    func testModeSwitchesWhileAudioThreadProcesses() async {
        let hook = SlotHook()
        let c = NoiseSuppressionController(hook: hook, voiceIsolationActive: { false })
        let stop = ManagedAtomicFlag()
        let audio = Task.detached {
            var frame = [Float](repeating: 0.01, count: 480)
            while !stop.isSet {
                frame.withUnsafeMutableBufferPointer { hook.slot.process($0.baseAddress!, count: 480) }
            }
        }
        for _ in 0..<40 {
            c.apply(.enhanced)
            XCTAssertTrue(hook.slot.isInstalled)
            c.apply(.standard)
            XCTAssertFalse(hook.slot.isInstalled)
            // Once apply returns the old processor is quiesced: no frame goes through it any more.
            let n = hook.slot.processedFrames
            for _ in 0..<20 { await Task.yield() }
            XCTAssertEqual(hook.slot.processedFrames, n)
        }
        stop.set()
        await audio.value
        c.apply(.off)
        XCTAssertFalse(hook.slot.isInstalled)
    }
}

final class ManagedAtomicFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var isSet: Bool { lock.withLock { value } }
    func set() { lock.withLock { value = true } }
}
