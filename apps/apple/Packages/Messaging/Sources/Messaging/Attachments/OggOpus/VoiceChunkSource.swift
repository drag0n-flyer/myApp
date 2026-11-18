#if canImport(AVFoundation)
import AVFoundation
import Foundation

/// Decodes a voice note a chunk (about 10 seconds) at a time, so memory stays bounded however long the note is.
/// Apple only (untested on Linux; the chunking arithmetic is `VoiceChunkPlan`, which is). Use from one task at a time:
/// `next()` keeps decoder state between chunks.
public final class VoiceChunkSource: @unchecked Sendable {
    /// The format of every buffer `next()` returns.
    public let format: AVAudioFormat
    /// Frames the whole note plays for.
    public let totalFrames: Int

    private enum Backing {
        case ogg(stream: OggOpusStream, plan: VoiceChunkPlan, converter: AVAudioConverter, input: AVAudioFormat)
        case caf(file: AVAudioFile)
    }
    private let backing: Backing
    private var index = 0
    private var cafFramesRead: Int64 = 0

    /// Validates like `VoicePlaybackLoader.load` (size, format, limits) but decodes nothing yet.
    public static func open(_ url: URL, origin: VoiceNoteOrigin) throws -> VoiceChunkSource {
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        try VoiceNoteValidator.checkFileSize(size)
        let data = try Data(contentsOf: url)
        switch try VoiceNoteValidator.validate([UInt8](data), origin: origin) {
        case .ogg(let stream): return try VoiceChunkSource(stream: stream)
        case .caf: return try VoiceChunkSource(cafAt: url)
        }
    }

    private init(stream: OggOpusStream) throws {
        try VoiceNoteValidator.check(stream)
        let channels = AVAudioChannelCount(stream.channels)
        let durations = stream.packets.map { OpusPacket.samples48k($0) ?? 0 }
        guard durations.reduce(0, +) > 0 else { throw VoicePlaybackError.decodeFailed }
        var description = AudioStreamBasicDescription(
            mSampleRate: 48_000, mFormatID: kAudioFormatOpus, mFormatFlags: 0,
            mBytesPerPacket: 0, mFramesPerPacket: 0, mBytesPerFrame: 0,
            mChannelsPerFrame: channels, mBitsPerChannel: 0, mReserved: 0)
        if Set(durations).count == 1 { description.mFramesPerPacket = UInt32(durations[0]) }
        guard let input = AVAudioFormat(streamDescription: &description),
              let output = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: channels, interleaved: false),
              let converter = AVAudioConverter(from: input, to: output) else { throw VoicePlaybackError.decoderUnavailable }
        let plan = VoiceChunkPlan(packetSamples: durations, preSkip: stream.preSkip, finalGranule: stream.finalGranule)
        guard plan.totalPlayableFrames > 0 else { throw VoicePlaybackError.decodeFailed }
        format = output
        totalFrames = plan.totalPlayableFrames
        backing = .ogg(stream: stream, plan: plan, converter: converter, input: input)
    }

    private init(cafAt url: URL) throws {
        let file = try AVAudioFile(forReading: url)
        try VoiceNoteValidator.checkCAFLength(frames: file.length, sampleRate: file.processingFormat.sampleRate)
        guard file.length > 0 else { throw VoicePlaybackError.decodeFailed }
        format = file.processingFormat
        totalFrames = Int(file.length)
        backing = .caf(file: file)
    }

    /// The next chunk, or nil at the end of the note.
    public func next() throws -> AVAudioPCMBuffer? {
        switch backing {
        case .caf(let file):
            let remaining = file.length - cafFramesRead
            guard remaining > 0 else { return nil }
            let count = AVAudioFrameCount(min(remaining, Int64(format.sampleRate * 10)))
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: count) else { throw VoicePlaybackError.decodeFailed }
            try file.read(into: buffer, frameCount: count)
            guard buffer.frameLength > 0 else { return nil }
            cafFramesRead += Int64(buffer.frameLength)
            return buffer
        case .ogg(let stream, let plan, let converter, let input):
            // A chunk that plays nothing (all pre-skip) is decoded for the decoder's state but not returned.
            while index < plan.chunks.count {
                let chunk = plan.chunks[index]
                index += 1
                if let buffer = try decode(chunk, stream: stream, converter: converter, input: input), buffer.frameLength > 0 {
                    return buffer
                }
            }
            return nil
        }
    }

    private func decode(
        _ chunk: VoiceChunkPlan.Chunk, stream: OggOpusStream, converter: AVAudioConverter, input: AVAudioFormat
    ) throws -> AVAudioPCMBuffer? {
        let packets = stream.packets[chunk.packets]
        guard let maxPacket = packets.map(\.count).max(),
              let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(chunk.decodedFrames)) else {
            throw VoicePlaybackError.decodeFailed
        }
        let compressed = AVAudioCompressedBuffer(
            format: input, packetCapacity: AVAudioPacketCount(packets.count), maximumPacketSize: maxPacket)
        var offset = 0
        for (i, packet) in packets.enumerated() {
            packet.withUnsafeBytes { raw in
                if let base = raw.baseAddress { (compressed.data + offset).copyMemory(from: base, byteCount: packet.count) }
            }
            let frames = UInt32(OpusPacket.samples48k(packet) ?? 0)
            compressed.packetDescriptions![i] = AudioStreamPacketDescription(
                mStartOffset: Int64(offset), mVariableFramesInPacket: input.streamDescription.pointee.mFramesPerPacket == 0 ? frames : 0,
                mDataByteSize: UInt32(packet.count))
            offset += packet.count
        }
        compressed.packetCount = AVAudioPacketCount(packets.count)
        compressed.byteLength = UInt32(offset)

        var supplied = false
        var failure: NSError?
        // `.noDataNow` (not end of stream) keeps the decoder's state for the next chunk.
        let status = converter.convert(to: pcm, error: &failure) { _, inputStatus in
            if supplied { inputStatus.pointee = .noDataNow; return nil }
            supplied = true
            inputStatus.pointee = .haveData
            return compressed
        }
        guard status != .error, failure == nil else { throw VoicePlaybackError.decodeFailed }

        let decoded = Int(pcm.frameLength)
        let drop = min(chunk.dropFirst, decoded)
        let keep = min(chunk.keep, decoded - drop)
        guard keep > 0 else { return nil }
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(keep)),
              let src = pcm.floatChannelData, let dst = out.floatChannelData else { throw VoicePlaybackError.decodeFailed }
        for c in 0..<Int(format.channelCount) { dst[c].update(from: src[c] + drop, count: keep) }
        out.frameLength = AVAudioFrameCount(keep)
        return out
    }
}
#endif
