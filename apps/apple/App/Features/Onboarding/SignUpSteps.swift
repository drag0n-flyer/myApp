import AppCore
import AVFoundation
import SwiftUI
import UserNotifications

struct InviteCodeView: View {
    let model: AppModel
    @State private var code = ""
    @State private var error: AccountError?
    @State private var checking = false

    private var canSubmit: Bool { !code.trimmingCharacters(in: .whitespaces).isEmpty && !checking }

    var body: some View {
        OnboardingFormPage(title: "Invite code", message: "The \(AppBrand.name) server is invite-only during the beta.") {
            Section {
                TextField("Invite code", text: $code, prompt: Text("BETA-0000"))
                    .autocorrectionDisabled()
                    .submitLabel(.continue)
                    .accessibilityIdentifier("invite.field")
                    .onSubmit { if canSubmit { submit() } }
            } footer: {
                AccountErrorText(error: error)
            }
        } primary: {
            Button(action: submit) { BusyLabel(title: "Continue", busy: checking) }
                .disabled(!canSubmit)
                .accessibilityIdentifier("invite.submit")
        }
    }

    private func submit() {
        checking = true
        Task {
            defer { checking = false }
            do throws(AccountError) {
                if try await model.account.redeemInvite(code: code.trimmingCharacters(in: .whitespaces)) {
                    error = nil
                    model.onboarding.inviteAccepted()
                }
            } catch { self.error = error }
        }
    }
}

struct CreateAccountView: View {
    private enum Field { case name, username, email }

    let model: AppModel
    @Bindable var draft: SignUpDraft
    @State private var check: UsernameCheck?
    @State private var error: AccountError?
    @State private var busy = false
    @FocusState private var focus: Field?

    private var serverName: String {
        switch model.onboarding.server {
        case .invite(let invite): invite.host
        case .address(let host): host
        case .defaultServer, nil: model.account.defaultServer.host
        }
    }

    private var canContinue: Bool {
        !draft.name.trimmingCharacters(in: .whitespaces).isEmpty && check == .available
            && draft.email.contains("@") && draft.email.contains(".") && !busy
    }

    var body: some View {
        OnboardingFormPage(title: "Create your account") {
            Section {
                Label {
                    Text("Joining \(Text(serverName).bold())")
                } icon: {
                    Circle().fill(.green).frame(width: 8, height: 8)
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            }

            Section {
                TextField("Name", text: $draft.name, prompt: Text("Your Name"))
                    .textContentType(.name)
                    .focused($focus, equals: .name)
                    .submitLabel(.next)
                    .onSubmit { focus = .username }
                    .accessibilityIdentifier("account.name")
            } footer: {
                Text("Shown to people you chat with.")
            }

            Section {
                TextField("Username", text: $draft.username, prompt: Text("yourname"))
                    .textContentType(.username)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .focused($focus, equals: .username)
                    .submitLabel(.next)
                    .onSubmit { focus = .email }
                    .accessibilityIdentifier("account.username")
            } footer: {
                if let check {
                    if check == .available {
                        Text(statusText(check))
                            .foregroundStyle(.green)
                            .accessibilityIdentifier("account.username.status")
                    } else {
                        Label(statusText(check), systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("account.username.status")
                    }
                }
            }

            Section {
                TextField("Email", text: $draft.email, prompt: Text("you@example.com"))
                    .textContentType(.emailAddress)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    #endif
                    .focused($focus, equals: .email)
                    .submitLabel(.continue)
                    .onSubmit { if canContinue { submit() } }
                    .accessibilityIdentifier("account.email")
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Only used to recover your account. Nobody can find you by email unless you turn it on.")
                    AccountErrorText(error: error)
                }
            }
        } primary: {
            Button(action: submit) { BusyLabel(title: "Continue", busy: busy) }
                .disabled(!canContinue)
                .accessibilityIdentifier("account.continue")
        }
        .task(id: draft.username) {
            guard !draft.username.isEmpty else { check = nil; return }
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            check = await model.account.checkUsername(draft.username)
        }
    }

    private func submit() {
        busy = true
        Task {
            defer { busy = false }
            do throws(AccountError) {
                try await model.account.startSignUp(displayName: draft.name, username: draft.username, email: draft.email)
                error = nil
                model.onboarding.accountSubmitted()
            } catch { self.error = error }
        }
    }

    private func statusText(_ check: UsernameCheck) -> LocalizedStringKey {
        switch check {
        case .available: "✓ Available"
        case .taken: "Taken"
        case .invalid: "3–32 letters, numbers or _, starting with a letter"
        case .unknown: "Couldn't check right now"
        }
    }
}

struct VerifyEmailView: View {
    let model: AppModel
    @State private var code = ""
    @State private var error: AccountError?
    @State private var busy = false
    /// The code was accepted: a retry after a later failure only repeats the finish, never the code.
    @State private var verified = false

    var body: some View {
        OnboardingFormPage(title: "Check your email", message: "Enter the 6-digit code we sent you.") {
            Section {
                TextField("Code", text: $code, prompt: Text("123456"))
                    .textContentType(.oneTimeCode)
                    .font(.title2.monospacedDigit())
                    #if os(iOS)
                    .keyboardType(.numberPad)
                    #endif
                    .submitLabel(.done)
                    .onSubmit { if code.count == 6 { verify() } }
                    .accessibilityIdentifier("verify.field")
            } footer: {
                AccountErrorText(error: error)
            }
        } primary: {
            if error == .signupExpired {
                Button("Start again") { model.onboarding.startSignUpAgain() }
                    .accessibilityIdentifier("onboarding.startAgain")
            } else {
                Button(action: verify) { BusyLabel(title: "Verify", busy: busy) }
                    .disabled(code.count != 6 || busy)
                    .accessibilityIdentifier("verify.submit")
            }
        }
        .onChange(of: code) { _, new in if new.count == 6 { verify() } }
    }

    /// Without passkeys for this server the sign-up finishes here with the email code, and the
    /// passkey step is skipped.
    private func verify() {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do throws(AccountError) {
                if !verified {
                    try await model.account.verifyEmail(code: code)
                    verified = true
                }
                let passkeys = model.account.supportsPasskeys
                if !passkeys {
                    try await model.account.finishSignUpWithEmailCode()
                    await model.didSignIn()
                }
                error = nil
                model.onboarding.emailVerified(passkeysSupported: passkeys)
            } catch { self.error = error }
        }
    }
}

struct PasskeyView: View {
    let model: AppModel
    @State private var error: AccountError?
    @State private var busy = false

    private var title: LocalizedStringKey {
        #if os(macOS)
        "Use Touch ID to sign in"
        #else
        "Use Face ID to sign in"
        #endif
    }

    var body: some View {
        OnboardingPage(title: title, message: model.account.supportsPasskeys
                       ? "No password to remember. Your passkey is saved in iCloud Keychain, so your other Apple devices can sign in too."
                       : "Passkeys can't be used here. Use an email code instead.") {
            Image(systemName: "person.badge.key.fill")
                .font(.system(size: 72))
                .foregroundStyle(.tint)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
                .accessibilityHidden(true)
            AccountErrorText(error: error)
        } primary: {
            // On `.cancelled` the person stays on this screen.
            if error == .signupExpired {
                Button("Start again") { model.onboarding.startSignUpAgain() }
                    .accessibilityIdentifier("onboarding.startAgain")
            } else if model.account.supportsPasskeys {
                Button { finish(withPasskey: true) } label: { BusyLabel(title: "Create passkey", busy: busy) }
                    .disabled(busy)
                    .accessibilityIdentifier("passkey.create")
            } else {
                Button { finish(withPasskey: false) } label: { BusyLabel(title: "Continue", busy: busy) }
                    .disabled(busy)
                    .accessibilityIdentifier("passkey.create")
            }
        } secondary: {
            // Finishes sign-up without a passkey; later sign-in uses an email code.
            if error != .signupExpired, model.account.supportsPasskeys {
                Button("Use an email code instead") { finish(withPasskey: false) }
                    .disabled(busy)
                    .accessibilityHint(Text("Signs in with a code sent to your email instead of a passkey."))
                    .accessibilityIdentifier("passkey.emailInstead")
            }
        }
    }

    private func finish(withPasskey: Bool) {
        busy = true
        Task {
            defer { busy = false }
            do throws(AccountError) {
                if withPasskey {
                    try await model.account.finishSignUp()
                } else {
                    try await model.account.finishSignUpWithEmailCode()
                }
                error = nil
                await model.didSignIn()
                model.onboarding.passkeyCreated()
            } catch { self.error = error }
        }
    }
}

struct PermissionsView: View {
    let model: AppModel

    var body: some View {
        OnboardingPage(title: "Two quick permissions") {
            reason("bell.badge", "Notifications", "So you know when someone messages or calls you.")
            reason("mic", "Microphone", "So people can hear you on calls.")
        } primary: {
            Button("Continue") {
                Task {
                    if !model.isUITesting {
                        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
                        _ = await AVCaptureDevice.requestAccess(for: .audio)
                        await model.registerForPushIfAllowed()
                    }
                    model.onboarding.permissionsHandled()
                }
            }
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
