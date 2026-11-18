import Foundation

/// Splits a voice note into chunks of about 10 seconds, so playback decodes and schedules a chunk at a time and never
/// holds the whole note as PCM (a 30-minute note would be about 345 MB at 48 kHz Float32).
///
/// Pure arithmetic over the packet lengths: no audio frameworks, so it is tested on Linux. Each chunk says which
/// packets to decode, how many frames to drop at its start (the pre-skip, which can span chunks) and how many to keep
/// (the final granule marks the real end of the audio).
public struct VoiceChunkPlan: Equatable, Sendable {
    public struct Chunk: Equatable, Sendable {
        /// Indices into the stream's packets.
        public var packets: Range<Int>
        /// Frames (48 kHz) the decoder produces for these packets.
        public var decodedFrames: Int
        /// Frames to drop from the start of the decoded audio.
        public var dropFirst: Int
        /// Frames to play after dropping.
        public var keep: Int
    }

    public static let defaultChunkFrames = 10 * 48_000

    public let chunks: [Chunk]
    /// Frames the whole note plays for.
    public let totalPlayableFrames: Int
    /// The most decoded frames any chunk holds (a chunk is a single packet at least).
    public var largestChunkFrames: Int { chunks.map(\.decodedFrames).max() ?? 0 }

    /// - Parameter packetSamples: 48 kHz samples per packet, in order (a malformed packet counts 0).
    public init(packetSamples: [Int], preSkip: Int, finalGranule: Int64, maxChunkFrames: Int = defaultChunkFrames) {
        let limit = max(maxChunkFrames, 1)
        let total = packetSamples.reduce(0) { $0 + max($1, 0) }
        let end = Int(max(0, min(Int64(total), finalGranule)))
        let skip = max(0, min(preSkip, end))
        totalPlayableFrames = end - skip

        var result: [Chunk] = []
        var start = 0          // decoded frames before the chunk
        var first = 0, frames = 0
        func close(upTo next: Int) {
            guard next > first, start < end else { first = next; frames = 0; return }
            let drop = max(0, min(skip - start, frames))
            let keepEnd = max(0, min(end - start, frames))
            result.append(Chunk(packets: first..<next, decodedFrames: frames, dropFirst: drop, keep: max(0, keepEnd - drop)))
            start += frames
            first = next
            frames = 0
        }
        for (index, samples) in packetSamples.enumerated() {
            let n = max(samples, 0)
            if frames > 0, frames + n > limit { close(upTo: index) }
            frames += n
        }
        close(upTo: packetSamples.count)
        chunks = result
    }
}
