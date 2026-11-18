import Foundation
import Testing
@testable import Messaging

@Suite("TestVoiceChunkPlan") struct TestVoiceChunkPlan {
    @Test func aThirtyMinuteNoteIsNeverHeldWhole() {
        let packets = Array(repeating: 960, count: 90_000)   // 20 ms each, 30 minutes
        let total = 90_000 * 960
        let plan = VoiceChunkPlan(packetSamples: packets, preSkip: 312, finalGranule: Int64(total))
        #expect(plan.chunks.count >= 180)
        #expect(plan.largestChunkFrames <= VoiceChunkPlan.defaultChunkFrames)
        #expect(plan.totalPlayableFrames == total - 312)
        #expect(plan.chunks.reduce(0) { $0 + $1.keep } == total - 312)
        // The chunks cover every packet exactly once, in order.
        var next = 0
        for chunk in plan.chunks { #expect(chunk.packets.lowerBound == next); next = chunk.packets.upperBound }
        #expect(next == packets.count)
    }

    @Test func thePreSkipIsDroppedOnceAndMayCoverSeveralChunks() {
        let plan = VoiceChunkPlan(packetSamples: Array(repeating: 100, count: 10), preSkip: 250, finalGranule: 1000, maxChunkFrames: 200)
        #expect(plan.chunks.map(\.dropFirst) == [200, 50, 0, 0, 0])
        #expect(plan.chunks.map(\.keep) == [0, 150, 200, 200, 200])
        #expect(plan.totalPlayableFrames == 750)
    }

    @Test func theFinalGranuleCutsTheEnd() {
        let plan = VoiceChunkPlan(packetSamples: Array(repeating: 100, count: 10), preSkip: 0, finalGranule: 450, maxChunkFrames: 200)
        #expect(plan.chunks.reduce(0) { $0 + $1.keep } == 450)
        #expect(plan.chunks.last?.keep == 50)
        #expect(plan.chunks.count == 3, "chunks that start after the end are not decoded")
    }

    @Test func oneHugePacketIsItsOwnChunkAndEmptyInputIsEmpty() {
        let plan = VoiceChunkPlan(packetSamples: [50, 5000, 50], preSkip: 0, finalGranule: 5100, maxChunkFrames: 100)
        #expect(plan.chunks.map(\.packets) == [0..<1, 1..<2, 2..<3])
        let empty = VoiceChunkPlan(packetSamples: [], preSkip: 0, finalGranule: 0)
        #expect(empty.chunks.isEmpty && empty.totalPlayableFrames == 0)
    }

    @Test func playedFramesGiveTheElapsedTime() {
        // Elapsed time is playedFrames / total, whatever the chunking: the chunks add up to the same total.
        let a = VoiceChunkPlan(packetSamples: Array(repeating: 960, count: 500), preSkip: 312, finalGranule: 480_000)
        let b = VoiceChunkPlan(packetSamples: Array(repeating: 960, count: 500), preSkip: 312, finalGranule: 480_000, maxChunkFrames: 5000)
        #expect(a.totalPlayableFrames == b.totalPlayableFrames)
        #expect(b.chunks.reduce(0) { $0 + $1.keep } == b.totalPlayableFrames)
    }
}
