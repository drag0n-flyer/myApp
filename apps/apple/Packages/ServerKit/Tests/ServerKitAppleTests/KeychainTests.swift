#if canImport(Security)
import Foundation
import Security
import Testing
@testable import ServerKit

@Suite(.serialized) struct KeychainTests {
    private func storage() -> KeychainSessionStorage {
        KeychainSessionStorage(service: "org.example.app.session.test.\(UUID().uuidString)", accessGroup: nil)
    }

    private let s1 = Session(host: "chat.test", accountID: "a", deviceID: "d", token: "t1")

    @Test func saveLoadClear() throws {
        let k = storage()
        defer { try? k.clear() }
        #expect(try k.load() == nil)
        try k.save(s1)
        #expect(try k.load() == s1)
        try k.clear()
        #expect(try k.load() == nil)
    }

    @Test func saveOverwrites() throws {
        let k = storage()
        defer { try? k.clear() }
        try k.save(s1)
        var s2 = s1
        s2.token = "t2"
        try k.save(s2)
        #expect(try k.load() == s2)
    }

    @Test func corruptItemIsClearedAndReadsAsNil() throws {
        let service = "org.example.app.session.test.\(UUID().uuidString)"
        let k = KeychainSessionStorage(service: service, accessGroup: nil)
        defer { try? k.clear() }
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "session",
            kSecAttrSynchronizable as String: false,
            kSecValueData as String: Data("not json".utf8),
        ]
        #expect(SecItemAdd(add as CFDictionary, nil) == errSecSuccess)
        #expect(try k.load() == nil)
        try k.save(s1)
        #expect(try k.load() == s1)
    }

    @Test func attributes() throws {
        let service = "org.example.app.session.test.\(UUID().uuidString)"
        let k = KeychainSessionStorage(service: service, accessGroup: nil)
        defer { try? k.clear() }
        try k.save(s1)
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        #expect(SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess)
        let attrs = out as? [String: Any] ?? [:]
        // Synchronizable is absent or 0 for a local item.
        let sync = attrs[kSecAttrSynchronizable as String] as? Bool ?? (attrs[kSecAttrSynchronizable as String] as? Int).map { $0 != 0 } ?? false
        #expect(sync == false)
        #if os(iOS)
        // The unsigned macOS file keychain does not store the accessibility class.
        #expect(attrs[kSecAttrAccessible as String] as? String == kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        #endif
    }
}
#endif
