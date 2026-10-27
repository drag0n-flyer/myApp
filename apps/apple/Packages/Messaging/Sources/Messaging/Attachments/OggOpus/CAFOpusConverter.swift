#if canImport(AudioToolbox)
import AudioToolbox
import Foundation

public enum CAFOpusError: Error, Equatable, Sendable {
    case cannotOpen
    case notOpus
    case readFailed(Int32)
}

/// Apple only (untested on Linux). Pulls the Opus packets out of the CAF file `AVAudioRecorder`
/// writes and repackages them as Ogg Opus with `OggOpusWriter`.
public enum CAFOpusConverter {
    /// Pre-skip used when the CAF carries no priming information: libopus' look-ahead for 16 kHz
    /// input, 312 samples at 48 kHz.
    static let defaultPreSkip = 312

    public struct Packets: Sendable {
        public var packets: [[UInt8]]
        /// Samples at 48 kHz.
        public var preSkip: Int
        public var inputSampleRate: UInt32
        public var channels: Int
    }

    public static func readPackets(from url: URL) throws -> Packets {
        var opened: AudioFileID?
        guard AudioFileOpenURL(url as CFURL, .readPermission, kAudioFileCAFType, &opened) == noErr, let file = opened else {
            throw CAFOpusError.cannotOpen
        }
        defer { AudioFileClose(file) }

        var format = AudioStreamBasicDescription()
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        var status = AudioFileGetProperty(file, kAudioFilePropertyDataFormat, &size, &format)
        guard status == noErr else { throw CAFOpusError.readFailed(status) }
        guard format.mFormatID == kAudioFormatOpus else { throw CAFOpusError.notOpus }

        var packetCount: UInt64 = 0
        size = UInt32(MemoryLayout<UInt64>.size)
        status = AudioFileGetProperty(file, kAudioFilePropertyAudioDataPacketCount, &size, &packetCount)
        guard status == noErr else { throw CAFOpusError.readFailed(status) }

        var upperBound: UInt32 = 0
        size = UInt32(MemoryLayout<UInt32>.size)
        status = AudioFileGetProperty(file, kAudioFilePropertyPacketSizeUpperBound, &size, &upperBound)
        if status != noErr || upperBound == 0 { upperBound = 1_500 }

        // Priming frames are counted at the file's sample rate; Ogg wants 48 kHz.
        var table = AudioFilePacketTableInfo()
        size = UInt32(MemoryLayout<AudioFilePacketTableInfo>.size)
        var preSkip = defaultPreSkip
        if AudioFileGetProperty(file, kAudioFilePropertyPacketTableInfo, &size, &table) == noErr,
           table.mPrimingFrames > 0, format.mSampleRate > 0 {
            preSkip = Int((Double(table.mPrimingFrames) * 48_000 / format.mSampleRate).rounded())
        }

        var packets: [[UInt8]] = []
        packets.reserveCapacity(Int(packetCount))
        let chunk = 256
        var start: Int64 = 0
        while UInt64(start) < packetCount {
            var numPackets = UInt32(chunk)
            var numBytes = UInt32(chunk) * upperBound
            var descriptions = [AudioStreamPacketDescription](repeating: AudioStreamPacketDescription(), count: chunk)
            var buffer = [UInt8](repeating: 0, count: Int(numBytes))
            status = AudioFileReadPacketData(file, false, &numBytes, &descriptions, start, &numPackets, &buffer)
            if numPackets == 0 {
                guard status == noErr || status == kAudioFileEndOfFileError else { throw CAFOpusError.readFailed(status) }
                break
            }
            for i in 0..<Int(numPackets) {
                let d = descriptions[i]
                let from = Int(d.mStartOffset)
                packets.append(Array(buffer[from..<from + Int(d.mDataByteSize)]))
            }
            start += Int64(numPackets)
        }
        return Packets(
            packets: packets, preSkip: preSkip,
            inputSampleRate: UInt32(format.mSampleRate.rounded()), channels: max(1, Int(format.mChannelsPerFrame)))
    }

    /// Converts the recorder's CAF to `<same name>.ogg` next to it and deletes the CAF.
    public static func convertToOgg(caf url: URL) throws -> URL {
        let source = try readPackets(from: url)
        let ogg = try OggOpusWriter.write(
            packets: source.packets, preSkip: source.preSkip,
            inputSampleRate: source.inputSampleRate, channels: min(source.channels, 2))
        let out = url.deletingPathExtension().appendingPathExtension(MediaPlan.Voice.fileExtension)
        try Data(ogg).write(to: out, options: .atomic)
        try? FileManager.default.removeItem(at: url)
        return out
    }
}
#endif
