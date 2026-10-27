import Foundation

public enum PaddingError: Error, Equatable, Sendable { case malformed }

/// The push body layout from server design 7.4: a 2-byte big-endian length, the payload, then
/// zeros up to a bucket size.
public enum Padding {
    public static func unpad(_ b: Data) throws -> Data {
        let bytes = [UInt8](b)
        guard bytes.count >= 2 else { throw PaddingError.malformed }
        let length = Int(bytes[0]) << 8 | Int(bytes[1])
        guard length <= bytes.count - 2 else { throw PaddingError.malformed }
        return Data(bytes[2..<(2 + length)])
    }
}
