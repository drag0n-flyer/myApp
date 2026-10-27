import AppCore
import AVFoundation
import SwiftUI
import UserNotifications

struct InviteCodeView: View {
    let model: AppModel
    @State private var code = ""
    @State private var wrong = false
    @State private var checking = false

    var body: some View {
        OnboardingPage(title: "Invite code", message: "The \(AppBrand.name) server is invite-only during the beta.") {
            OnboardingField(label: "Invite code") {
                TextField("BETA-0000", text: $code)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("invite.field")
                    .onSubmit(submit)
            }
            if wrong {
                Text("That code didn't work. Check it and try again.").font(.footnote).foregroundStyle(.red)
            }
        } buttons: {
            Button("Continue", action: submit)
                .buttonStyle(PrimaryButtonStyle())
                .disabled(code.trimmingCharacters(in: .whitespaces).isEmpty || checking)
                .accessibilityIdentifier("invite.submit")
        }
    }

    private func submit() {
        checking = true
        Task {
            let ok = await model.account.redeemInvite(code: code.trimmingCharacters(in: .whitespaces))
            checking = false
            wrong = !ok
            if ok { model.onboarding.inviteAccepted() }
        }
    }
}

struct CreateAccountView: View {
    let model: AppModel
    @State private var name = ""
    @State private var username = ""
    @State private var email = ""
    @State private var check: UsernameCheck?

    private var serverName: String {
        switch model.onboarding.server {
        case .invite(let invite): invite.host
        case .address(let host): host
        case .defaultServer, nil: model.account.defaultServer.host
        }
    }

    private var canContinue: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && check == .available && email.contains("@") && email.contains(".")
    }

    var body: some View {
        OnboardingPage(title: "Create your account") {
            Label {
                Text("Joining \(Text(serverName).bold())")
            } icon: {
                Circle().fill(.green).frame(width: 8, height: 8)
            }
            .font(.footnote)
            .foregroundStyle(.secondary)

            OnboardingField(label: "Name") {
                TextField("Your Name", text: $name)
                    .textContentType(.name)
                    .accessibilityIdentifier("account.name")
            }
            Text("Shown to people you chat with.").font(.caption).foregroundStyle(.secondary)

            OnboardingField(label: "Username") {
                TextField("yourname", text: $username)
                    .textContentType(.username)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .accessibilityIdentifier("account.username")
            }
            if let check {
                Text(statusText(check))
                    .font(.caption)
                    .foregroundStyle(check == .available ? Color.green : Color.red)
                    .accessibilityIdentifier("account.username.status")
            }

            OnboardingField(label: "Email") {
                TextField("you@example.com", text: $email)
                    .textContentType(.emailAddress)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    #endif
                    .accessibilityIdentifier("account.email")
            }
            Text("Only used to recover your account. Nobody can find you by email unless you turn it on.")
                .font(.caption)
                .foregroundStyle(.secondary)
        } buttons: {
            Button("Continue") {
                Task {
                    await model.account.sendEmailCode(to: email)
                    model.onboarding.accountSubmitted()
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!canContinue)
            .accessibilityIdentifier("account.continue")
        }
        .task(id: username) {
            guard !username.isEmpty else { check = nil; return }
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            check = await model.account.checkUsername(username)
        }
    }

    private func statusText(_ check: UsernameCheck) -> LocalizedStringKey {
        switch check {
        case .available: "✓ Available"
        case .taken: "Taken"
        case .invalid: "3–32 letters, numbers or _, starting with a letter"
        }
    }
}

struct VerifyEmailView: View {
    let model: AppModel
    @State private var code = ""
    @State private var wrong = false

    var body: some View {
        OnboardingPage(title: "Check your email", message: "Enter the 6-digit code we sent you.") {
            OnboardingField(label: "Code") {
                TextField("123456", text: $code)
                    .textContentType(.oneTimeCode)
                    .font(.title2.monospacedDigit())
                    #if os(iOS)
                    .keyboardType(.numberPad)
                    #endif
                    .accessibilityIdentifier("verify.field")
            }
            if wrong {
                Text("That code didn't match. Try again.").font(.footnote).foregroundStyle(.red)
            }
        } buttons: {
            Button("Verify", action: verify)
                .buttonStyle(PrimaryButtonStyle())
                .disabled(code.count != 6)
                .accessibilityIdentifier("verify.submit")
        }
        .onChange(of: code) { _, new in if new.count == 6 { verify() } }
    }

    private func verify() {
        Task {
            let ok = await model.account.verifyEmail(code: code)
            wrong = !ok
            if ok { model.onboarding.emailVerified() }
        }
    }
}

struct PasskeyView: View {
    let model: AppModel

    private var title: LocalizedStringKey {
        #if os(macOS)
        "Use Touch ID to sign in"
        #else
        "Use Face ID to sign in"
        #endif
    }

    var body: some View {
        OnboardingPage(title: title, message: "No password to remember. Your passkey is saved in iCloud Keychain, so a new phone can sign in too.") {
            Image(systemName: "person.badge.key.fill")
                .font(.system(size: 72))
                .foregroundStyle(.tint)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
                .accessibilityHidden(true)
        } buttons: {
            // The real app runs ASAuthorizationPlatformPublicKeyCredentialProvider here.
            Button("Create passkey") { model.onboarding.passkeyCreated() }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("passkey.create")
        }
    }
}

struct PermissionsView: View {
    let model: AppModel

    var body: some View {
        OnboardingPage(title: "Two quick permissions") {
            reason("bell.badge", "Notifications", "So you know when someone messages or calls you.")
            reason("mic", "Microphone", "So people can hear you on calls.")
        } buttons: {
            Button("Continue") {
                Task {
                    if !model.isUITesting {
                        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
                        _ = await AVCaptureDevice.requestAccess(for: .audio)
                    }
                    model.onboarding.permissionsHandled()
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .accessibilityIdentifier("permissions.continue")
        }
    }

    private func reason(_ symbol: String, _ title: LocalizedStringKey, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).font(.title2).foregroundStyle(.tint).frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(text).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }
}
