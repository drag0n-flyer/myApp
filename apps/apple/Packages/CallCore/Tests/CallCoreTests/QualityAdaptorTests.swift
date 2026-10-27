import Testing
import Foundation
import AppCore
@testable import CallCore

private func sample(_ avail: Double?, loss: Double = 0) -> MediaStats {
    MediaStats(availableOutgoingBitrate: avail, inboundLossFraction: loss)
}

private struct SeededRNG: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}

@Suite struct QualityAdaptorTests {
    @Test func ladderBasics() {
        #expect(VideoLadder.allowed(ceiling: .high) == .p360)
        #expect(VideoLadder.allowed(ceiling: .balanced) == .p270)
        #expect(VideoLadder.allowed(ceiling: .dataSaver) == .p180)
        #expect(VideoLadder.allowed(ceiling: .ultraLow) == .p180)
        #expect(VideoLadder.pick(budget: 300_000, maxTier: .p360)?.tier == .p360)
        #expect(VideoLadder.pick(budget: 300_000, maxTier: .p360)?.maxBitrate == 300_000)
        #expect(VideoLadder.pick(budget: 500_000, maxTier: .p360)?.maxBitrate == 300_000)
        #expect(VideoLadder.pick(budget: 150_000, maxTier: .p360)?.tier == .p270)
        #expect(VideoLadder.pick(budget: 150_000, maxTier: .p180)?.tier == .p180)
        #expect(VideoLadder.pick(budget: 30_000, maxTier: .p360) == nil)
        #expect(VideoLadder.pick(budget: 500_000, maxTier: nil) == nil)
        var s = CallSettings()
        #expect(VideoLadder.startsOff(ceiling: .high, network: .mobile, settings: s))
        #expect(!VideoLadder.startsOff(ceiling: .high, network: .wifi, settings: s))
        s.videoOffOnMobile = false
        #expect(!VideoLadder.startsOff(ceiling: .high, network: .mobile, settings: s))
        #expect(VideoLadder.startsOff(ceiling: .dataSaver, network: .wifi, settings: s))
        #expect(VideoLadder.startsOff(ceiling: .ultraLow, network: .wifi, settings: s))
    }

    @Test func startsAtCeilingWithHeadroom() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: true)
        let o = a.ingest(sample(600_000))
        #expect(o.mode == .high && o.video == .p360 && o.quality == .good && o.banner == nil)
    }

    @Test func dropsVideoTierFirst() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: true)
        _ = a.ingest(sample(600_000))
        let o = a.ingest(sample(260_000))
        #expect(o.video == .p270 && o.banner == .videoLowered && o.mode == .high)
    }

    @Test func videoOffBelow60k() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: true)
        _ = a.ingest(sample(600_000))
        let o = a.ingest(sample(55_000))
        #expect(o.video == nil && o.videoBitrate == nil && o.banner == .audioOnly && o.mode == .dataSaver)
    }

    @Test func audioStepsDownLast() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: true)
        _ = a.ingest(sample(600_000))
        let o = a.ingest(sample(20_000))
        #expect(o.mode == .ultraLow && o.quality == .weak && o.video == nil)
    }

    @Test func lossStepsDownAfterTwoSamples() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: false)
        #expect(a.ingest(sample(600_000)).mode == .high)
        #expect(a.ingest(sample(600_000, loss: 0.15)).mode == .high)
        #expect(a.ingest(sample(600_000, loss: 0.15)).mode == .balanced)
    }

    @Test func nonConsecutiveLossDoesNotStepDown() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: false)
        _ = a.ingest(sample(600_000, loss: 0.15))
        _ = a.ingest(sample(600_000, loss: 0.0))
        #expect(a.ingest(sample(600_000, loss: 0.15)).mode == .high)
    }

    @Test func hysteresisUp() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: true)
        _ = a.ingest(sample(600_000))
        #expect(a.ingest(sample(20_000)).mode == .ultraLow)
        // Audio comes back one level per 5 good samples; video only after audio is at the ceiling.
        var lastOut = a.ingest(sample(600_000))
        for i in 1..<4 { // 4 good samples in total so far: no change
            lastOut = a.ingest(sample(600_000))
            #expect(lastOut.mode == .ultraLow, "sample \(i)")
        }
        lastOut = a.ingest(sample(600_000))
        #expect(lastOut.mode == .dataSaver && lastOut.video == nil)
        for _ in 0..<5 { lastOut = a.ingest(sample(600_000)) }
        #expect(lastOut.mode == .balanced && lastOut.video == nil)
        for _ in 0..<5 { lastOut = a.ingest(sample(600_000)) }
        #expect(lastOut.mode == .high && lastOut.video == nil)
        for _ in 0..<5 { lastOut = a.ingest(sample(600_000)) }
        #expect(lastOut.mode == .high && lastOut.video == .p180)
    }

    @Test func neverExceedsCeiling() {
        var rng = SeededRNG(state: 42)
        var a = QualityAdaptor(ceiling: .high, videoWanted: true)
        var ceiling = DataMode.high
        for i in 0..<2000 {
            if i % 100 == 0 {
                ceiling = DataMode.allCases.randomElement(using: &rng)!
                a.setCeiling(ceiling)
            }
            let avail: Double? = Int.random(in: 0..<10, using: &rng) == 0 ? nil : Double.random(in: 0...800_000, using: &rng)
            let loss = Bool.random(using: &rng) ? 0 : Double.random(in: 0...0.3, using: &rng)
            let o = a.ingest(sample(avail, loss: loss))
            #expect(o.mode <= ceiling)
            if let v = o.video { #expect(v <= VideoLadder.allowed(ceiling: ceiling)!) }
            #expect((o.video == nil) == (o.videoBitrate == nil))
        }
    }

    @Test func videoNotWantedStaysOff() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: false)
        for avail in [600_000.0, 55_000, 20_000, 600_000] {
            let o = a.ingest(sample(avail))
            #expect(o.video == nil && o.banner != .audioOnly)
        }
    }
}
