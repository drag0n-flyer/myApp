#if canImport(Security)
import Foundation
import Security

public struct KeychainError: Error, Equatable, Sendable {
    public let status: Int32
}

/// Stores one JSON-encoded `Session` as a generic password. Readable after first unlock (so the
/// notification extension can use it while the phone is locked), this device only, never synced.
/// `accessGroup` is `nil` in unsigned package tests.
public struct KeychainSessionStorage: SessionStorage {
    private let service: String
    private let accessGroup: String?
    private let account = "session"

    public init(service: String = "org.example.app.session", accessGroup: String?) {
        self.service = service
        self.accessGroup = accessGroup
    }

    private var identity: [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
        ]
        if let accessGroup {
            q[kSecAttrAccessGroup as String] = accessGroup
            #if os(macOS)
            q[kSecUseDataProtectionKeychain as String] = true
            #endif
        }
        return q
    }

    public func load() throws -> Session? {
        var q = identity
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = out as? Data else { throw KeychainError(status: status) }
        do {
            return try JSONDecoder().decode(Session.self, from: data)
        } catch {
            // A corrupt item can never be read again; drop it so the person can sign in afresh.
            try clear()
            return nil
        }
    }

    public func save(_ s: Session) throws {
        let data = try JSONEncoder().encode(s)
        let attrs: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(identity as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(identity.merging(attrs) { $1 } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    public func clear() throws {
        let status = SecItemDelete(identity as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(status: status) }
    }
}
#endif
