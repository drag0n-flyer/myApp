import Testing
import Foundation
import AppCore
@testable import CallCore

private func sample(_ avail: Double?, loss: Double = 0) -> MediaStats {
    MediaStats(availableOutgoingBitrate: avail, inboundLossFraction: loss)
}

/// The estimate WebRTC reports while the session caps it at `cap` (the current level's ceiling): it sits at the cap.
private func capped(_ cap: Int, loss: Double = 0) -> MediaStats {
    MediaStats(availableOutgoingBitrate: Double(cap), inboundLossFraction: loss, bweCeiling: Double(cap))
}

/// The session's estimator ceiling for the adaptor's current level (`MediaLogic.bweMaxBitrate`).
private func cap(_ a: QualityAdaptor) -> Int {
    MediaLogic.bweMaxBitrate(audioBitrate: AudioProfile.for(a.currentMode).maxAverageBitrate,
                             videoBitrate: a.currentTier.map { VideoLadder.spec($0).maxBps } ?? 0)
}

/// Feeds `n` samples of the capped estimate at the adaptor's current ceiling.
private func feedCapped(_ a: inout QualityAdaptor, _ n: Int, loss: Double = 0) {
    for _ in 0..<n { _ = a.ingest(capped(cap(a), loss: loss)) }
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

    @Test func raisingCeilingKeepsVideo() {
        var a = QualityAdaptor(ceiling: .balanced, videoWanted: true)
        #expect(a.ingest(sample(600_000)).video == .p270)
        a.setCeiling(.high)
        let o = a.ingest(sample(600_000))
        #expect(o.video != nil && o.banner != .audioOnly && o.mode == .high)
    }

    @Test func enablingVideoJumpsToBestFit() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: false)
        #expect(a.ingest(sample(600_000)).video == nil)
        a.setVideoWanted(true)
        let o = a.ingest(sample(600_000))
        #expect(o.video == .p360 && o.mode == .high)

        var b = QualityAdaptor(ceiling: .high, videoWanted: false)
        _ = b.ingest(sample(260_000))
        b.setVideoWanted(true)
        #expect(b.ingest(sample(260_000)).video == .p270)
    }

    @Test func nilAvailabilityHolds() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: false)
        _ = a.ingest(sample(600_000))
        #expect(a.ingest(sample(20_000)).mode == .ultraLow)
        for _ in 0..<3 { _ = a.ingest(sample(600_000)) }          // 3 good samples
        for _ in 0..<20 { #expect(a.ingest(sample(nil)).mode == .ultraLow) }  // nil neither counts nor resets
        #expect(a.ingest(sample(600_000)).mode == .ultraLow)       // 4th good
        #expect(a.ingest(sample(600_000)).mode == .dataSaver)      // 5th good
    }

    @Test func nilAvailabilityStillAppliesLoss() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: false)
        _ = a.ingest(sample(nil, loss: 0.2))
        #expect(a.ingest(sample(nil, loss: 0.2)).mode == .balanced)
    }

    @Test func flappingAroundThresholdIsBounded() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: true)
        var last = a.ingest(sample(600_000))
        var changes = 0
        for i in 0..<200 {
            let o = a.ingest(sample(i % 2 == 0 ? 250_000 : 330_000))
            if o.mode != last.mode || o.video != last.video { changes += 1 }
            last = o
        }
        #expect(changes <= 4)
    }

    // MARK: capped estimate (the session caps the estimate at the level's wire budget)

    @Test func capSaturationStepsBackUp() {
        var a = QualityAdaptor(ceiling: .balanced, videoWanted: false)
        _ = a.ingest(sample(30_000))
        #expect(a.currentMode == .ultraLow)
        feedCapped(&a, QualityAdaptor.goodSamplesToStepUp - 1)
        #expect(a.currentMode == .ultraLow)
        feedCapped(&a, 1)
        #expect(a.currentMode == .dataSaver)
        feedCapped(&a, QualityAdaptor.goodSamplesToStepUp)
        #expect(a.currentMode == .balanced)
    }

    @Test func capSaturationTurnsVideoBackOnAtTheLowestTier() {
        var a = QualityAdaptor(ceiling: .high, videoWanted: true)
        _ = a.ingest(sample(50_000))
        #expect(a.currentTier == nil)
        for _ in 0..<20 where a.currentTier == nil { feedCapped(&a, 1) }
        #expect(a.currentMode == .high)
        #expect(a.currentTier == .p180)
    }

    @Test func capSaturationWithLossDoesNotStepUp() {
        var a = QualityAdaptor(ceiling: .balanced, videoWanted: false)
        _ = a.ingest(sample(30_000))
        feedCapped(&a, 30, loss: 0.05)
        #expect(a.currentMode == .ultraLow)
    }

    @Test func failedStepUpHoldsOffAndBacksOff() {
        var a = QualityAdaptor(ceiling: .balanced, videoWanted: false)
        _ = a.ingest(sample(30_000))
        feedCapped(&a, QualityAdaptor.goodSamplesToStepUp)
        #expect(a.currentMode == .dataSaver)
        _ = a.ingest(sample(20_000))   // the network cannot sustain it
        #expect(a.currentMode == .ultraLow)
        let hold = QualityAdaptor.stepUpHoldOffSamples
        feedCapped(&a, hold + QualityAdaptor.goodSamplesToStepUp - 1)
        #expect(a.currentMode == .ultraLow)
        feedCapped(&a, 1)
        #expect(a.currentMode == .dataSaver)
        _ = a.ingest(sample(20_000))   // fails again: the hold-off doubles
        feedCapped(&a, 2 * hold + QualityAdaptor.goodSamplesToStepUp - 1)
        #expect(a.currentMode == .ultraLow)
        feedCapped(&a, 1)
        #expect(a.currentMode == .dataSaver)
        // A step-up that holds clears the back-off.
        feedCapped(&a, QualityAdaptor.goodSamplesToStepUp)
        #expect(a.currentMode == .balanced)
        _ = a.ingest(sample(40_000))
        #expect(a.currentMode == .dataSaver)
        feedCapped(&a, hold + QualityAdaptor.goodSamplesToStepUp)
        #expect(a.currentMode == .balanced)
    }

    @Test func estimateBelowTheCapIsNotSaturated() {
        var a = QualityAdaptor(ceiling: .balanced, videoWanted: false)
        _ = a.ingest(sample(30_000))
        for _ in 0..<30 { _ = a.ingest(MediaStats(availableOutgoingBitrate: 19_000, bweCeiling: 21_000)) }
        #expect(a.currentMode == .ultraLow)
    }
}
