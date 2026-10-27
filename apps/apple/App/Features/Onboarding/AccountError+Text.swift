import AppCore
import SwiftUI

extension AccountError {
    /// Text shown under the field. `nil` for `.cancelled` (the person chose to stop).
    var message: String? {
        switch self {
        case .inviteRequired: String(localized: "This server needs an invite code.")
        case .inviteInvalid: String(localized: "That invite code doesn't work. Check it and try again.")
        case .usernameTaken: String(localized: "That username is taken.")
        case .invalidUsername: String(localized: "Use 3–32 lowercase letters, numbers, or _, starting with a letter.")
        case .invalidEmail: String(localized: "Enter a valid email address.")
        case .codeInvalid: String(localized: "That code isn't right.")
        case .codeExpired: String(localized: "That code has expired. Ask for a new one.")
        case .tooManyAttempts(let seconds):
            if let seconds {
                String(localized: "Too many tries. Try again in \((seconds + 59) / 60) min.")
            } else {
                String(localized: "Too many tries. Try again later.")
            }
        case .linkExpired: String(localized: "This code has expired. Start again on the new device.")
        case .wrongServer: String(localized: "This code is for a different server.")
        case .passkeyUnavailable: String(localized: "Passkeys aren't available on this server. Use an email code instead.")
        case .cancelled: nil
        case .network: String(localized: "Can't reach the server. Check your connection and try again.")
        case .server: String(localized: "Something went wrong on the server. Try again later.")
        case .biometricsUnavailable: String(localized: "Couldn't confirm it's you. Check Face ID or your passcode and try again.")
        }
    }
}

/// Error text under a field, in red.
struct AccountErrorText: View {
    let error: AccountError?

    var body: some View {
        if let text = error?.message {
            Text(text)
                .font(.footnote)
                .foregroundStyle(.red)
                .accessibilityIdentifier("onboarding.error")
        }
    }
}
