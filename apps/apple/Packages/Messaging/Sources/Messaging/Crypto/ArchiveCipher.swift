import Foundation

public enum ArchiveError: Error, Equatable, Sendable {
    /// The key is not 32 bytes.
    case badKey
    /// Not an archive: wrong magic or version, no final chunk, or a malformed record.
    case format
    /// A chunk failed authentication: tampered, reordered, truncated, extended, or the wrong key.
    case authentication
}

/// Streaming archive encryption, format `docs/protocol/archive.md` (archive v1). The AEAD is
/// injected (`ChunkAEAD`, AES-256-GCM in production: `CryptoKitChunkAEAD`), so the framing is tested
/// on Linux, where CryptoKit does not exist; the shared vectors are checked on Apple platforms.
///
/// - header = "APPARCH1" ‖ 0x01 ‖ 7-byte random nonce prefix (16 bytes).
/// - then one record per 1 MiB of plaintext: u32be(length) ‖ AEAD output (ciphertext ‖ 16-byte tag).
/// - chunk i: nonce = prefix ‖ u32be(i) ‖ flag; AAD = header ‖ u32be(i) ‖ flag; flag is 0x01 on the
///   last chunk and 0x00 otherwise. An empty archive is one empty final chunk.
public struct ArchiveCipher: Sendable {
    public static let chunkSize = 1 << 20
    public static let tagSize = 16
    public static let keySize = 32
    public static let prefixSize = 7
    public static let headerSize = 16
    static let magic = Data("APPARCH1".utf8)
    static let version: UInt8 = 1

    private let aead: ChunkAEAD
    public init(aead: ChunkAEAD) { self.aead = aead }

    public static func newKey() -> Data { Data((0..<keySize).map { _ in UInt8.random(in: 0...255) }) }

    static func u32(_ v: UInt32) -> Data { Data([UInt8(v >> 24), UInt8((v >> 16) & 0xff), UInt8((v >> 8) & 0xff), UInt8(v & 0xff)]) }

    static func tail(index: UInt32, last: Bool) -> Data { u32(index) + Data([last ? 1 : 0]) }

    /// Incremental sealing: write `header`, then each `seal(chunk:last:)` result, in order. Every chunk
    /// except the last must be exactly `chunkSize` bytes; the last holds 0...chunkSize bytes.
    public struct Sealer {
        public let header: Data
        private let prefix: Data
        private let key: Data
        private let aead: ChunkAEAD
        private var index: UInt32 = 0
        private var finished = false

        init(aead: ChunkAEAD, key: Data, prefix: Data) throws {
            guard key.count == ArchiveCipher.keySize else { throw ArchiveError.badKey }
            precondition(prefix.count == ArchiveCipher.prefixSize)
            self.aead = aead; self.key = key; self.prefix = prefix
            header = ArchiveCipher.magic + Data([ArchiveCipher.version]) + prefix
        }

        public mutating func seal(chunk: Data, last: Bool) throws -> Data {
            precondition(!finished && chunk.count <= ArchiveCipher.chunkSize && (last || chunk.count == ArchiveCipher.chunkSize))
            let t = ArchiveCipher.tail(index: index, last: last)
            let sealed = try aead.seal(chunk, key: key, nonce: prefix + t, aad: header + t)
            finished = last
            if !last { index += 1 }
            return ArchiveCipher.u32(UInt32(sealed.count)) + sealed
        }
    }

    public func sealer(key: Data) throws -> Sealer {
        try Sealer(aead: aead, key: key, prefix: Data((0..<Self.prefixSize).map { _ in UInt8.random(in: 0...255) }))
    }

    /// Fixed nonce prefix: for test vectors only.
    func sealer(key: Data, prefix: Data) throws -> Sealer { try Sealer(aead: aead, key: key, prefix: prefix) }

    public func seal(_ plaintext: Data, key: Data) throws -> Data {
        var s = try sealer(key: key)
        return try seal(plaintext, with: &s)
    }

    func seal(_ plaintext: Data, key: Data, prefix: Data) throws -> Data {
        var s = try sealer(key: key, prefix: prefix)
        return try seal(plaintext, with: &s)
    }

    private func seal(_ plaintext: Data, with s: inout Sealer) throws -> Data {
        var out = s.header
        var offset = 0
        while true {
            let end = min(offset + Self.chunkSize, plaintext.count)
            let last = end == plaintext.count
            out += try s.seal(chunk: plaintext.subdata(in: (plaintext.startIndex + offset)..<(plaintext.startIndex + end)), last: last)
            if last { return out }
            offset = end
        }
    }

    /// Opens a whole archive held in memory. Nothing is returned unless every chunk, including the
    /// final one, authenticates and nothing follows it.
    public func open(_ archive: Data, key: Data) throws -> Data {
        guard key.count == Self.keySize else { throw ArchiveError.badKey }
        let data = Data(archive) // zero-based indices
        guard data.count >= Self.headerSize, data.prefix(8) == Self.magic, data[8] == Self.version else { throw ArchiveError.format }
        let header = data.prefix(Self.headerSize)
        let prefix = data[9..<Self.headerSize]
        var pos = Self.headerSize
        guard pos < data.count else { throw ArchiveError.format } // no final chunk
        var out = Data()
        var index: UInt32 = 0
        while pos < data.count {
            guard data.count - pos >= 4 else { throw ArchiveError.format }
            let len = data[pos..<(pos + 4)].reduce(0) { ($0 << 8) | Int($1) }
            pos += 4
            guard len >= Self.tagSize, len <= Self.chunkSize + Self.tagSize, data.count - pos >= len else { throw ArchiveError.format }
            let record = data[pos..<(pos + len)]
            pos += len
            let t = Self.tail(index: index, last: pos == data.count)
            do {
                out += try aead.open(Data(record), key: key, nonce: Data(prefix) + t, aad: Data(header) + t)
            } catch { throw ArchiveError.authentication }
            if pos < data.count {
                guard index < UInt32.max else { throw ArchiveError.format }
                index += 1
            }
        }
        return out
    }
}
