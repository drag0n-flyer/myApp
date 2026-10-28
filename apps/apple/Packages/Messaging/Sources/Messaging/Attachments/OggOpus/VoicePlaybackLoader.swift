#if canImport(AVFoundation)
import AVFoundation
import Foundation

public enum VoicePlaybackError: Error, Equatable, Sendable {
    case unknownFormat
    case decoderUnavailable
    case decodeFailed
}

/// Apple only (untested on Linux). Turns a stored voice note into PCM for playback: Ogg Opus is
/// validated and demuxed by `VoiceNoteValidator`, then decoded by `AVAudioConverter`'s Opus decoder;
/// an old local CAF note is read directly with `AVAudioFile`.
public enum VoicePlaybackLoader {
    /// `origin` says where the file came from. A file received from another person must be Ogg Opus
    /// within the size and length limits (`VoiceNoteValidator`); CAF is read only for local files.
    public static func load(_ url: URL, origin: VoiceNoteOrigin) throws -> AVAudioPCMBuffer {
        // Check the size on disk before reading anything into memory.
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        try VoiceNoteValidator.checkFileSize(size)
        let data = try Data(contentsOf: url)
        switch try VoiceNoteValidator.validate([UInt8](data), origin: origin) {
        case .ogg(let stream): return try decode(stream)
        case .caf: return try loadCAF(url)
        }
    }

    static func loadCAF(_ url: URL) throws -> AVAudioPCMBuffer {
        let file = try AVAudioFile(forReading: url)
        try VoiceNoteValidator.checkCAFLength(frames: file.length, sampleRate: file.processingFormat.sampleRate)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)) else {
            throw VoicePlaybackError.decodeFailed
        }
        try file.read(into: buffer)
        return buffer
    }

    /// Decodes to 48 kHz Float32 and drops the pre-skip and anything past the final granule.
    /// The stream must already have passed `VoiceNoteValidator` (limits, no empty packets).
    static func decode(_ stream: OggOpusStream) throws -> AVAudioPCMBuffer {
        try VoiceNoteValidator.check(stream)
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
                if let base = raw.baseAddress { (compressed.data + offset).copyMemory(from: base, byteCount: packet.count) }
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
