import Foundation

public enum AttachmentError: Error, Equatable, Sendable {
    /// The key is not 32 bytes.
    case badKey
    /// The ciphertext's SHA-256 differs from the digest in the envelope. Checked before any
    /// plaintext is written.
    case digest
    /// The file is not a valid chunked ciphertext (empty, or a chunk shorter than its tag).
    case format
    /// A chunk failed authentication: tampered, reordered, truncated or from another file.
    case authentication
}

/// One chunk of authenticated encryption. `CryptoKitAttachmentCipher` uses AES-256-GCM; the
/// protocol exists so the chunk framing (nonce, final-chunk flag, truncation and reorder
/// rejection) is tested on Linux, where CryptoKit does not exist.
public protocol ChunkAEAD: Sendable {
    /// Returns ciphertext ‖ 16-byte tag.
    func seal(_ plaintext: Data, key: Data, nonce: Data, aad: Data) throws -> Data
    /// Takes ciphertext ‖ 16-byte tag. Throws on authentication failure.
    func open(_ sealed: Data, key: Data, nonce: Data, aad: Data) throws -> Data
}

/// Chunked AEAD attachment encryption (Design 7, the STREAM construction):
/// - 65 536 bytes of plaintext per chunk; every chunk adds a 16-byte tag.
/// - nonce = 4 zero bytes ‖ 8-byte big-endian chunk index.
/// - AAD = one byte, 0x01 for the last chunk and 0x00 otherwise, so a truncated file fails.
/// - digest = SHA-256 over the whole ciphertext.
/// An empty file is one empty last chunk (16 bytes). Memory use does not depend on file size.
public struct ChunkedAttachmentCipher: AttachmentCipher {
    public static let chunkSize = 65_536
    public static let tagSize = 16
    static let sealedChunkSize = chunkSize + tagSize

    private let aead: ChunkAEAD

    public init(aead: ChunkAEAD) { self.aead = aead }

    static func nonce(index: UInt64) -> Data {
        var n = Data(count: 4)
        for shift in stride(from: 56, through: 0, by: -8) { n.append(UInt8((index >> UInt64(shift)) & 0xff)) }
        return n
    }

    static func aad(last: Bool) -> Data { Data([last ? 0x01 : 0x00]) }

    public func encrypt(file: URL, to: URL) throws -> (key: Data, digest: Data, size: Int64, cipherSize: Int64) {
        var key = Data(count: 32)
        for i in 0..<32 { key[i] = UInt8.random(in: 0...255) }

        let input = try FileHandle(forReadingFrom: file)
        defer { try? input.close() }
        FileManager.default.createFile(atPath: to.path, contents: nil)
        let output = try FileHandle(forWritingTo: to)
        defer { try? output.close() }

        var hasher = StreamingSHA256()
        var size: Int64 = 0
        var cipherSize: Int64 = 0
        var index: UInt64 = 0
        var current = try Self.readChunk(input, Self.chunkSize)
        while true {
            let next = current.count == Self.chunkSize ? try Self.readChunk(input, Self.chunkSize) : Data()
            let last = next.isEmpty
            let sealed = try aead.seal(current, key: key, nonce: Self.nonce(index: index), aad: Self.aad(last: last))
            try output.write(contentsOf: sealed)
            hasher.update(sealed)
            size += Int64(current.count)
            cipherSize += Int64(sealed.count)
            if last { break }
            current = next
            index += 1
        }
        return (key, hasher.finalize(), size, cipherSize)
    }

    public func decrypt(file: URL, to: URL, key: Data, digest: Data) throws {
        guard key.count == 32 else { throw AttachmentError.badKey }

        // Pass 1: digest over the whole ciphertext. Nothing is written if it differs.
        let hashInput = try FileHandle(forReadingFrom: file)
        var hasher = StreamingSHA256()
        var length: Int64 = 0
        while true {
            let block = try Self.readChunk(hashInput, Self.sealedChunkSize)
            if block.isEmpty { break }
            hasher.update(block)
            length += Int64(block.count)
        }
        try? hashInput.close()
        guard hasher.finalize() == digest else { throw AttachmentError.digest }
        guard length >= Int64(Self.tagSize) else { throw AttachmentError.format }

        // Pass 2: decrypt into a temporary file, moved into place only when every chunk passed.
        let temp = to.appendingPathExtension("part")
        try? FileManager.default.removeItem(at: temp)
        FileManager.default.createFile(atPath: temp.path, contents: nil)
        do {
            let input = try FileHandle(forReadingFrom: file)
            defer { try? input.close() }
            let output = try FileHandle(forWritingTo: temp)
            defer { try? output.close() }

            var index: UInt64 = 0
            var current = try Self.readChunk(input, Self.sealedChunkSize)
            while true {
                guard current.count >= Self.tagSize else { throw AttachmentError.format }
                let next = current.count == Self.sealedChunkSize ? try Self.readChunk(input, Self.sealedChunkSize) : Data()
                let last = next.isEmpty
                let plain: Data
                do {
                    plain = try aead.open(current, key: key, nonce: Self.nonce(index: index), aad: Self.aad(last: last))
                } catch {
                    throw AttachmentError.authentication
                }
                try output.write(contentsOf: plain)
                if last { break }
                current = next
                index += 1
            }
        } catch {
            try? FileManager.default.removeItem(at: temp)
            throw error
        }
        try? FileManager.default.removeItem(at: to)
        try FileManager.default.moveItem(at: temp, to: to)
    }

    /// Reads exactly `count` bytes, or fewer only at the end of the file.
    private static func readChunk(_ handle: FileHandle, _ count: Int) throws -> Data {
        var data = Data()
        while data.count < count {
            guard let part = try handle.read(upToCount: count - data.count), !part.isEmpty else { break }
            data.append(part)
        }
        return data
    }
}

#if canImport(CryptoKit)
import CryptoKit

/// AES-256-GCM with a 12-byte nonce and 16-byte tag.
public struct CryptoKitChunkAEAD: ChunkAEAD {
    public init() {}

    public func seal(_ plaintext: Data, key: Data, nonce: Data, aad: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(data: nonce), authenticating: aad)
        return box.ciphertext + box.tag
    }

    public func open(_ sealed: Data, key: Data, nonce: Data, aad: Data) throws -> Data {
        guard sealed.count >= ChunkedAttachmentCipher.tagSize else { throw AttachmentError.format }
        let split = sealed.count - ChunkedAttachmentCipher.tagSize
        let box = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce), ciphertext: sealed.prefix(split), tag: sealed.suffix(ChunkedAttachmentCipher.tagSize))
        return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }
}

/// The production attachment cipher (Apple platforms).
public struct CryptoKitAttachmentCipher: AttachmentCipher {
    private let inner = ChunkedAttachmentCipher(aead: CryptoKitChunkAEAD())
    public init() {}

    public func encrypt(file: URL, to: URL) throws -> (key: Data, digest: Data, size: Int64, cipherSize: Int64) {
        try inner.encrypt(file: file, to: to)
    }

    public func decrypt(file: URL, to: URL, key: Data, digest: Data) throws {
        try inner.decrypt(file: file, to: to, key: key, digest: digest)
    }
}
#endif
