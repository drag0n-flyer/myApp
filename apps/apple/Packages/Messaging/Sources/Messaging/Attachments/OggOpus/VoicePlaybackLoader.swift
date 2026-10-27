#if canImport(AVFoundation)
import AVFoundation
import Foundation

public enum VoicePlaybackError: Error, Equatable, Sendable {
    case unknownFormat
    case decoderUnavailable
    case decodeFailed
}

/// Apple only (untested on Linux). Turns a stored voice note into PCM for playback: Ogg Opus is
/// demuxed by `OggOpusReader` and decoded by `AVAudioConverter`'s Opus decoder; an old CAF note
/// is read directly with `AVAudioFile`.
public enum VoicePlaybackLoader {
    public static func load(_ url: URL) throws -> AVAudioPCMBuffer {
        let data = try Data(contentsOf: url)
        switch VoiceContainer.detect(prefix: Array(data.prefix(4))) {
        case .ogg: return try decode(ogg: [UInt8](data))
        case .caf: return try loadCAF(url)
        case nil: throw VoicePlaybackError.unknownFormat
        }
    }

    static func loadCAF(_ url: URL) throws -> AVAudioPCMBuffer {
        let file = try AVAudioFile(forReading: url)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)) else {
            throw VoicePlaybackError.decodeFailed
        }
        try file.read(into: buffer)
        return buffer
    }

    /// Decodes to 48 kHz Float32 and drops the pre-skip and anything past the final granule.
    public static func decode(ogg: [UInt8]) throws -> AVAudioPCMBuffer {
        let stream = try OggOpusReader.read(ogg)
        let channels = AVAudioChannelCount(stream.channels)
        let durations = stream.packets.map { OpusPacket.samples48k($0) ?? 0 }
        let totalFrames = durations.reduce(0, +)
        guard totalFrames > 0, let maxPacket = stream.packets.map(\.count).max() else { throw VoicePlaybackError.decodeFailed }

        var description = AudioStreamBasicDescription(
            mSampleRate: 48_000, mFormatID: kAudioFormatOpus, mFormatFlags: 0,
            mBytesPerPacket: 0, mFramesPerPacket: 0, mBytesPerFrame: 0,
            mChannelsPerFrame: channels, mBitsPerChannel: 0, mReserved: 0)
        // A constant packet length lets the decoder know the frame count; otherwise it is given per packet.
        if Set(durations).count == 1 { description.mFramesPerPacket = UInt32(durations[0]) }
        guard let inputFormat = AVAudioFormat(streamDescription: &description),
              let outputFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: channels, interleaved: false),
              let converter = AVAudioConverter(from: inputFormat, to: outputFormat),
              let compressed = AVAudioCompressedBuffer(
                format: inputFormat, packetCapacity: AVAudioPacketCount(stream.packets.count), maximumPacketSize: maxPacket),
              let pcm = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: AVAudioFrameCount(totalFrames))
        else { throw VoicePlaybackError.decoderUnavailable }

        var offset = 0
        for (i, packet) in stream.packets.enumerated() {
            packet.withUnsafeBytes { raw in
                (compressed.data + offset).copyMemory(from: raw.baseAddress!, byteCount: packet.count)
            }
            compressed.packetDescriptions![i] = AudioStreamPacketDescription(
                mStartOffset: Int64(offset), mVariableFramesInPacket: description.mFramesPerPacket == 0 ? UInt32(durations[i]) : 0,
                mDataByteSize: UInt32(packet.count))
            offset += packet.count
        }
        compressed.packetCount = AVAudioPacketCount(stream.packets.count)
        compressed.byteLength = UInt32(offset)

        var supplied = false
        var failure: NSError?
        let status = converter.convert(to: pcm, error: &failure) { _, inputStatus in
            if supplied { inputStatus.pointee = .endOfStream; return nil }
            supplied = true
            inputStatus.pointee = .haveData
            return compressed
        }
        guard status != .error, failure == nil, pcm.frameLength > 0 else { throw VoicePlaybackError.decodeFailed }

        // Trim: the first `preSkip` samples are encoder delay; the final granule marks the real end.
        let end = min(Int(pcm.frameLength), Int(stream.finalGranule))
        let skip = min(stream.preSkip, end)
        guard let out = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: AVAudioFrameCount(max(end - skip, 1))),
              let src = pcm.floatChannelData, let dst = out.floatChannelData else { throw VoicePlaybackError.decodeFailed }
        for c in 0..<Int(channels) { (dst[c]).update(from: src[c] + skip, count: end - skip) }
        out.frameLength = AVAudioFrameCount(end - skip)
        return out
    }
}
#endif
