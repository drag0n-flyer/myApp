import Foundation
import Messaging
import ServerKit

/// Build settings the project writes into Info.plist (`project.yml`).
struct AppConfig {
    let defaultServerHost: String
    let passkeyDomains: [String]
    let pushGatewayURL: String
    /// The gateway's X25519 public key; `nil` while the setting is empty, which turns push off.
    let pushGatewayKey: Data?
    let apnsEnvironment: String
    let keychainAccessGroup: String?

    static var current: AppConfig {
        let info = Bundle.main.infoDictionary ?? [:]
        func string(_ key: String) -> String { (info[key] as? String)?.trimmingCharacters(in: .whitespaces) ?? "" }
        let domains = string("PasskeyDomains").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        // Required: without it the app would use its private keychain group, which the extension cannot read.
        let group = MessagingLocations.requiredInfoValue("KeychainAccessGroup", in: info) ?? ""
        return AppConfig(
            defaultServerHost: string("DefaultServerHost").isEmpty ? "home.example.org" : string("DefaultServerHost"),
            passkeyDomains: domains,
            pushGatewayURL: string("PushGatewayURL"),
            pushGatewayKey: Base64URLData(encoded: string("PushGatewayKey")).map(\.data).flatMap { $0.count == 32 ? $0 : nil },
            apnsEnvironment: string("APNSEnvironment").isEmpty ? "sandbox" : string("APNSEnvironment"),
            keychainAccessGroup: group.isEmpty ? nil : group
        )
    }
}
