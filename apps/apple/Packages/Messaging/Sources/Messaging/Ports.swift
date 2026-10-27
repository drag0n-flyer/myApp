import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit

/// Seals an escrow copy to the server's public key. Tests use `FakeEscrowSealer`.
public protocol EscrowSealer: Sendable {
    func seal(_ plaintext: Data, to recipientKey: Data) throws -> Data
}

public enum EscrowError: Error, Equatable, Sendable { case unavailable }

/// Escrow sealing: HPKE (Curve25519, HKDF-SHA256, ChaCha20-Poly1305, `info = "escrow-v1"`),
/// output `encapsulatedKey (32) || ciphertext`. Needs CryptoKit.
///
/// F2 declares the name and signature only; B1 implements it. Until then it fails closed.
public enum Escrow {
    public static func seal(_ plaintext: Data, serverKey: Data) throws -> Data {
        throw EscrowError.unavailable
    }
}

/// Chunked attachment encryption (Design 7). The real cipher is built in B2; tests use
/// `FakeAttachmentCipher`.
public protocol AttachmentCipher: Sendable {
    func encrypt(file: URL, to: URL) throws -> (key: Data, digest: Data, size: Int64, cipherSize: Int64)
    func decrypt(file: URL, to: URL, key: Data, digest: Data) throws
}

/// Lane A implements this; lane B consumes it.
public protocol ConversationManaging: Sendable {
    /// Creates a conversation if the chat has none.
    func preferredConversation(with peer: String) async throws -> ConversationRecord
    func handleStaleEpoch(_ conversation: ConversationRecord) async throws
}

/// Shows "New device added" (Design 19). The app posts a local notification; the notification
/// extension does nothing here because it shows the notice itself.
public protocol DeviceNotifier: Sendable {
    func ownDeviceAdded(deviceID: String, name: String) async
}

public struct MessagingDeps: Sendable {
    public var me: (accountID: String, deviceID: String)
    public var server: ServerClient
    public var mls: MLSEngine
    public var repo: Repository
    public var lock: ProcessLock
    public var escrow: EscrowSealer
    public var attachments: AttachmentCipher
    public var notifier: DeviceNotifier
    public var mediaDirectory: URL
    public var now: @Sendable () -> Date

    public init(
        me: (accountID: String, deviceID: String), server: ServerClient, mls: MLSEngine, repo: Repository,
        lock: ProcessLock, escrow: EscrowSealer, attachments: AttachmentCipher, notifier: DeviceNotifier,
        mediaDirectory: URL, now: @escaping @Sendable () -> Date
    ) {
        self.me = me
        self.server = server
        self.mls = mls
        self.repo = repo
        self.lock = lock
        self.escrow = escrow
        self.attachments = attachments
        self.notifier = notifier
        self.mediaDirectory = mediaDirectory
        self.now = now
    }
}
