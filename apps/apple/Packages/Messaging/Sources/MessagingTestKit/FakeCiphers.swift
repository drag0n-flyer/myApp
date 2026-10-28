import Foundation
import Messaging

/// Returns `"ESCROW:" + plaintext`. NOT encryption.
public struct FakeEscrowSealer: EscrowSealer {
    public init() {}
    public func seal(_ plaintext: Data, to recipientKey: Data) throws -> Data {
        Data("ESCROW:".utf8) + plaintext
    }
}

public enum FakeAttachmentCipherError: Error, Equatable, Sendable { case digestMismatch, badKey }

/// XOR with `key[0]`; the digest is SHA-256 of the "ciphertext" through the portable SHA-256.
/// NOT encryption.
public struct FakeAttachmentCipher: AttachmentCipher {
    public init() {}

    public func encrypt(file: URL, to: URL) throws -> (key: Data, digest: Data, size: Int64, cipherSize: Int64) {
        let plain = try Data(contentsOf: file)
        var key = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        if key[0] == 0 { key[0] = 0x5a }   // a zero byte would leave the file unchanged
        let cipher = Self.xor(plain, key[0])
        try cipher.write(to: to)
        return (key, PortableSHA256.hash(cipher), Int64(plain.count), Int64(cipher.count))
    }

    public func decrypt(file: URL, to: URL, key: Data, digest: Data) throws {
        guard let first = key.first else { throw FakeAttachmentCipherError.badKey }
        let cipher = try Data(contentsOf: file)
        guard PortableSHA256.hash(cipher) == digest else { throw FakeAttachmentCipherError.digestMismatch }
        try Self.xor(cipher, first).write(to: to)
    }

    private static func xor(_ data: Data, _ byte: UInt8) -> Data { Data(data.map { $0 ^ byte }) }
}
