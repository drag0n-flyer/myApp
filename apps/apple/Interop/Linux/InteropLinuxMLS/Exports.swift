// LiveMLSEngine.swift (linked here from SecureMessaging) is written as part of the SecureMessaging module;
// these imports give it the same names on Linux, where that module is built without the engine.
@_exported import SecureMessaging
@_exported import SecureMessagingCore
@_exported import InteropMLSFFI

import Foundation

/// Only so `makeMLSEngine` in LiveMLSEngine.swift compiles: Linux has no Keychain, and the peer never calls it
/// (it passes `InMemorySigningKeyStore`).
struct KeychainSigningKeyStore: SigningKeyStore {
    struct Unavailable: Error {}
    init(accessGroup: String) {}
    func load(identity: String) throws -> (publicKey: Data, secretKey: Data)? { throw Unavailable() }
    func save(identity: String, publicKey: Data, secretKey: Data) throws { throw Unavailable() }
}

/// Internal to SecureMessaging; the same stand-in, for the same `makeMLSEngine`.
enum KeychainAccessGroupConfig {
    static let infoKey = "MLSKeychainAccessGroup"
    static func resolve(_ value: Any?) throws -> String { throw KeychainSigningKeyStore.Unavailable() }
}
