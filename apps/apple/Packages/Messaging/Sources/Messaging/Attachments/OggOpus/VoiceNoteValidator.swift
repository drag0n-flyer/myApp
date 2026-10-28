import Foundation

public enum VoiceNoteError: Error, Equatable, Sendable {
    case tooLarge
    case tooLong
    case emptyPacket
    case invalidPacket
    case channelMismatch
    case durationMismatch
    /// A format that must not come from the network (CAF).
    case notAllowedFormat
    case unknownFormat
}

public enum VoiceNoteLimits {
    /// Owner decision: a voice note is at most 30 minutes.
    public static let maxDuration = 1_800
    /// The same limit in 48 kHz samples.
    public static let maxSamples: Int64 = Int64(maxDuration) * 48_000
    /// Largest file read for one voice note. Opus tops out at 510 kbit/s, which for 30 minutes is
    /// 114 750 000 bytes; 120 000 000 leaves room for Ogg page overhead and the two header pages.
    public static let maxFileBytes = 120_000_000
}

public enum VoiceNoteOrigin: Sendable {
    /// Downloaded from another person: only Ogg Opus is accepted.
    case received
    /// Recorded on this device: an old CAF note is also accepted.
    case local
}

public enum VoiceNoteContent: Sendable {
    case ogg(OggOpusStream)
    /// Legacy local file; the caller still has to call `checkCAFLength` once the system has opened it.
    case caf
}

/// Pure checks run on a voice note before anything is decoded, so a hostile file from a peer
/// cannot exhaust memory or crash the player. Tested on Linux.
public enum VoiceNoteValidator {
    /// Call with the file's size before reading it into memory.
    public static func checkFileSize(_ bytes: Int) throws {
        guard bytes >= 0, bytes <= VoiceNoteLimits.maxFileBytes else { throw VoiceNoteError.tooLarge }
    }

    public static func validate(_ data: [UInt8], origin: VoiceNoteOrigin) throws -> VoiceNoteContent {
        try checkFileSize(data.count)
        switch VoiceContainer.detect(prefix: Array(data.prefix(4))) {
        case .caf:
            guard origin == .local else { throw VoiceNoteError.notAllowedFormat }
            return .caf
        case nil:
            throw VoiceNoteError.unknownFormat
        case .ogg:
            let stream = try OggOpusReader.read(data)
            try check(stream)
            return .ogg(stream)
        }
    }

    /// A CAF opened by the system: its frame count must fit the duration cap.
    public static func checkCAFLength(frames: Int64, sampleRate: Double) throws {
        guard sampleRate > 0, frames >= 0, Double(frames) <= sampleRate * Double(VoiceNoteLimits.maxDuration) else {
            throw VoiceNoteError.tooLong
        }
    }

    static func check(_ stream: OggOpusStream) throws {
        // Declared length first, then the length the packets really add up to.
        guard stream.finalGranule <= VoiceNoteLimits.maxSamples + Int64(stream.preSkip) else { throw VoiceNoteError.tooLong }
        var total: Int64 = 0
        for packet in stream.packets {
            guard let toc = packet.first else { throw VoiceNoteError.emptyPacket }
            guard let samples = OpusPacket.samples48k(packet) else { throw VoiceNoteError.invalidPacket }
            if stream.channels == 1, toc & 0x04 != 0 { throw VoiceNoteError.channelMismatch }
            total += Int64(samples)
            guard total <= VoiceNoteLimits.maxSamples + Int64(stream.preSkip) else { throw VoiceNoteError.tooLong }
        }
        // The last granule may trim encoder padding (less than one packet) but never claims more.
        guard stream.finalGranule <= total, total - stream.finalGranule < 5_760 else { throw VoiceNoteError.durationMismatch }
    }
}
