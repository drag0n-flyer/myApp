import AppCore
import Foundation

/// Passkeys work only for hosts in the app's associated domains (`PASSKEY_DOMAINS`).
/// On other servers, sign-up finishes without a passkey and sign-in uses an email code.
public enum PasskeyPolicy {
    /// Exact, case-insensitive host match (no subdomains).
    public static func supported(host: String, domains: [String]) -> Bool {
        let h = host.lowercased()
        return domains.contains { $0.lowercased() == h }
    }
}

public protocol PasskeyAuthenticating: Sendable {
    func register(_ options: CreationOptions) async throws(AccountError) -> RegistrationCredential
    func assert(_ options: RequestOptions) async throws(AccountError) -> AssertionCredential
}
