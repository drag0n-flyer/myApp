import Foundation
#if canImport(Security)
import Security
#endif

/// Where a device keeps its MLS signature key pair, keyed by identity ("<accountID>/<deviceID>").
/// The secret key never leaves this store except into the engine's memory.
public protocol SigningKeyStore: Sendable {
    func load(identity: String) throws -> (publicKey: Data, secretKey: Data)?
    func save(identity: String, publicKey: Data, secretKey: Data) throws
}

/// Item layout shared by the Keychain store: a 2-byte big-endian public key length, the public
/// key, then the secret key.
enum SigningKeyEncoding {
    static func encode(publicKey: Data, secretKey: Data) throws -> Data {
        guard publicKey.count > 0, publicKey.count <= Int(UInt16.max), secretKey.count > 0 else {
            throw MLSError.storage("signing key has an invalid size")
        }
        let length = UInt16(publicKey.count)
        return Data([UInt8(length >> 8), UInt8(length & 0xff)]) + publicKey + secretKey
    }

    static func decode(_ data: Data) throws -> (publicKey: Data, secretKey: Data) {
        let bytes = Array(data)   // a Data slice does not start at index 0
        guard bytes.count >= 2 else { throw MLSError.storage("signing key item is corrupt") }
        let length = Int(bytes[0]) << 8 | Int(bytes[1])
        guard length > 0, bytes.count > 2 + length else { throw MLSError.storage("signing key item is corrupt") }
        return (Data(bytes[2..<(2 + length)]), Data(bytes[(2 + length)...]))
    }
}

/// Keeps keys in memory. For tests only: nothing is persisted or protected.
public final class InMemorySigningKeyStore: SigningKeyStore, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String: (publicKey: Data, secretKey: Data)] = [:]   // guarded by `lock`
    private var saves = 0                                                   // guarded by `lock`

    public init() {}

    /// How many times `save` has been called.
    public var saveCount: Int { lock.withLock { saves } }

    public func load(identity: String) throws -> (publicKey: Data, secretKey: Data)? {
        lock.withLock { items[identity] }
    }

    public func save(identity: String, publicKey: Data, secretKey: Data) throws {
        lock.withLock {
            items[identity] = (publicKey, secretKey)
            saves += 1
        }
    }
}

#if canImport(Security)
/// Stores the key as a Keychain generic password: service `org.example.app.mls.signing`,
/// account = identity, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (readable by the
/// notification extension while the phone is locked after the first unlock; never synced,
/// never in backups). Pass the shared access group so the app and the extension see one item.
public struct KeychainSigningKeyStore: SigningKeyStore {
    public static let service = "org.example.app.mls.signing"

    private let accessGroup: String?

    public init(accessGroup: String?) {
        self.accessGroup = accessGroup
    }

    private func query(identity: String) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: identity,
            kSecAttrSynchronizable as String: false,
        ]
        #if os(macOS)
        query[kSecUseDataProtectionKeychain as String] = true   // the iOS-style keychain, with access groups
        #endif
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        return query
    }

    public func load(identity: String) throws -> (publicKey: Data, secretKey: Data)? {
        var request = query(identity: identity)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw MLSError.storage("keychain read failed (\(status))")
        }
        return try SigningKeyEncoding.decode(data)
    }

    public func save(identity: String, publicKey: Data, secretKey: Data) throws {
        let data = try SigningKeyEncoding.encode(publicKey: publicKey, secretKey: secretKey)
        var add = query(identity: identity)
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        var status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let update: [String: Any] = [
                kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ]
            status = SecItemUpdate(query(identity: identity) as CFDictionary, update as CFDictionary)
        }
        guard status == errSecSuccess else { throw MLSError.storage("keychain write failed (\(status))") }
    }
}
#endif
