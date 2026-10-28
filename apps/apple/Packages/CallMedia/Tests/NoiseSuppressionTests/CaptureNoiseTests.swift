import XCTest
@testable import NoiseSuppression

final class CaptureNoiseTests: XCTestCase {
    /// Runs `body` on `channelCount` channels of `frames` samples filled by `fill`; returns the channels afterwards.
    private func run(rate: Int = 48_000, frames: Int = 480, channelCount: Int = 1, installed: Bool,
                     fill: (Int, Int) -> Float = { c, i in Float(0.2 * sin(Double(i) * 0.3 + Double(c))) },
                     initialize: Bool = true) -> (before: [[Float]], after: [[Float]]) {
        let node = CaptureNoiseProcessor()
        if installed { node.setProcessor(RNNoiseProcessor()) }
        if initialize { node.initialize(sampleRateHz: rate, channels: channelCount) }
        var chans = (0..<channelCount).map { c in (0..<frames).map { fill(c, $0) } }
        let before = chans
        var pointers: [UnsafeMutablePointer<Float>] = []
        for c in 0..<channelCount {
            let p = UnsafeMutablePointer<Float>.allocate(capacity: frames)
            p.initialize(from: chans[c], count: frames)
            pointers.append(p)
        }
        pointers.withUnsafeBufferPointer { node.process(channels: $0.baseAddress!, frames: frames, channelCount: channelCount) }
        for c in 0..<channelCount { chans[c] = Array(UnsafeBufferPointer(start: pointers[c], count: frames)); pointers[c].deallocate() }
        return (before, chans)
    }

    func testFramePolicy() {
        XCTAssertTrue(CaptureFramePolicy.accepts(sampleRateHz: 48_000, frames: 480, channelCount: 1))
        XCTAssertTrue(CaptureFramePolicy.accepts(sampleRateHz: 48_000, frames: 480, channelCount: 2))
        XCTAssertFalse(CaptureFramePolicy.accepts(sampleRateHz: 32_000, frames: 320, channelCount: 1))
        XCTAssertFalse(CaptureFramePolicy.accepts(sampleRateHz: 48_000, frames: 441, channelCount: 1))
        XCTAssertFalse(CaptureFramePolicy.accepts(sampleRateHz: 48_000, frames: 480, channelCount: 0))
    }

    func testSuppressionOffLeavesEveryChannelUnchanged() {
        let r = run(channelCount: 2, installed: false)
        XCTAssertEqual(r.before, r.after)
        let three = run(channelCount: 3, installed: false)
        XCTAssertEqual(three.before, three.after)
    }

    func testWrongRateOrSizePassesThroughUnchanged() {
        let rate = run(rate: 32_000, frames: 320, installed: true)
        XCTAssertEqual(rate.before, rate.after)
        let size = run(frames: 441, installed: true)
        XCTAssertEqual(size.before, size.after)
        let noInit = run(installed: true, initialize: false)
        XCTAssertEqual(noInit.before, noInit.after)
    }

    func testOutOfRangeSamplesPassThrough() {
        let loud = run(installed: true, fill: { _, i in i % 2 == 0 ? 20_000 : -20_000 })
        XCTAssertEqual(loud.before, loud.after)
    }

    func testSupportedFrameIsProcessedAndOtherChannelsStayUnchanged() {
        // Many frames so the denoiser output settles away from the input.
        let node = CaptureNoiseProcessor()
        node.setProcessor(RNNoiseProcessor())
        node.initialize(sampleRateHz: 48_000, channels: 2)
        let a = UnsafeMutablePointer<Float>.allocate(capacity: 480), b = UnsafeMutablePointer<Float>.allocate(capacity: 480)
        defer { a.deallocate(); b.deallocate() }
        var changed = false
        for n in 0..<50 {
            for i in 0..<480 { a[i] = Float(0.2 * sin(Double(i + n * 480) * 0.3)); b[i] = 0.123 }
            let before = Array(UnsafeBufferPointer(start: a, count: 480))
            [a, b].withUnsafeBufferPointer { node.process(channels: $0.baseAddress!, frames: 480, channelCount: 2) }
            if Array(UnsafeBufferPointer(start: a, count: 480)) != before { changed = true }
            XCTAssertTrue(UnsafeBufferPointer(start: b, count: 480).allSatisfy { $0 == 0.123 }, "channel 1 is never touched")
        }
        XCTAssertTrue(changed, "channel 0 is denoised")
        XCTAssertTrue(node.isActive)
    }
}
