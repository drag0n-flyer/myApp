import Foundation

/// Push payload padding (server spec section 7.4): a 2-byte big-endian length, the payload,
/// then zero bytes up to 256, 512, 1024, 2048 or 3072 bytes in total.
public enum PushPayload {
    /// The total buffer sizes the server may send.
    static let allowedSizes: Set<Int> = [256, 512, 1024, 2048, 3072]

    /// Returns the payload, or `nil` when the length is 0, which means "fetch the inbox".
    /// Throws `MLSError.malformed` for a disallowed buffer size, a length longer than the data,
    /// or a non-zero padding byte.
    public static func unpad(_ data: Data) throws -> Data? {
        let bytes = Array(data)   // a Data slice does not start at index 0
        guard bytes.count >= 2, allowedSizes.contains(bytes.count) else { throw MLSError.malformed }
        let length = Int(bytes[0]) << 8 | Int(bytes[1])
        guard length <= bytes.count - 2 else { throw MLSError.malformed }
        guard bytes[(2 + length)...].allSatisfy({ $0 == 0 }) else { throw MLSError.malformed }
        return length == 0 ? nil : Data(bytes[2..<(2 + length)])
    }
}
