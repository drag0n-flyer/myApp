import Foundation

/// Facts read from the first byte of an Opus packet (RFC 6716 section 3.1).
public enum OpusPacket {
    /// How many 48 kHz samples the packet holds, or nil when the packet is empty or malformed.
    /// Ogg granule positions always count 48 kHz samples, whatever the input sample rate was.
    public static func samples48k(_ packet: [UInt8]) -> Int? {
        guard let toc = packet.first else { return nil }
        let config = Int(toc >> 3)
        let frame: Int   // samples at 48 kHz per frame
        switch config {
        case 0...11:  frame = [480, 960, 1_920, 2_880][config % 4]    // SILK: 10, 20, 40, 60 ms
        case 12...15: frame = config % 2 == 0 ? 480 : 960             // hybrid: 10, 20 ms
        default:      frame = [120, 240, 480, 960][config % 4]        // CELT: 2.5, 5, 10, 20 ms
        }
        let frames: Int
        switch toc & 0x03 {
        case 0: frames = 1
        case 1, 2: frames = 2
        default:
            guard packet.count >= 2 else { return nil }
            frames = Int(packet[1] & 0x3F)
            guard frames >= 1 else { return nil }
        }
        let total = frame * frames
        return total <= 5_760 ? total : nil   // a packet holds at most 120 ms
    }
}
