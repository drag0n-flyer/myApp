import Foundation

/// SHA-256 in plain Swift. CryptoKit is unavailable on Linux, and safety numbers and attachment
/// digests must be identical on every platform, so all of them use this one implementation
/// (`StreamingSHA256` in AttachmentDigest.swift holds the algorithm).
public enum PortableSHA256 {
    public static func hash(_ data: Data) -> Data {
        var hasher = StreamingSHA256()
        hasher.update(data)
        return hasher.finalize()
    }
}
