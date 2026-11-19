import AppCore
import CoreImage.CIFilterBuiltins
import SwiftUI

/// The new device shows a QR code; the signed-in iPhone scans it (UI design §6.8).
struct LinkDeviceView: View {
    let model: AppModel
    @State private var qrText = ""
    @State private var error: AccountError?
    @State private var host = ""
    @State private var attempt = 0
    @State private var askingServer = false
    @State private var newHost = ""
    /// Signed in, but the contact list was not imported; the person is told before moving on.
    @State private var contactsNotCopied = false

    var body: some View {
        OnboardingPage(title: "Link this device", message: "Open \(AppBrand.name) on your iPhone → Settings → Linked devices → Link a device, and scan this code.") {
            HStack {
                Label {
                    Text("Server: \(host)")
                } icon: {
                    Image(systemName: "server.rack")
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                Spacer()
                // An approved sign-in waiting to be saved belongs to this server: it can't be moved.
                if !model.account.linkSavePending {
                    Button("Change") {
                        newHost = ""
                        askingServer = true
                    }
                    .font(.footnote)
                    .accessibilityIdentifier("link.change")
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("link.server")

            QRCodeView(text: qrText)
                .frame(width: 220, height: 220)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Linking QR code")
                .accessibilityIdentifier("link.qr")
            AccountErrorText(error: error)
        } primary: {
            if model.isSampleData {
                // Sample build: stands in for the iPhone confirming the scan.
                Button("I scanned it") { model.onboarding.linked() }
                    .accessibilityIdentifier("link.done")
            } else if error != nil, model.account.linkSavePending {
                // Approved, but this device's sign-in wasn't saved: retry the save, never a new code.
                Button("Try again") { attempt += 1 }
                    .accessibilityIdentifier("link.retry")
            } else if error == .linkExpired {
                Button("Show new code") { attempt += 1 }
                    .accessibilityIdentifier("link.newcode")
            }
        } secondary: {
            if model.onboarding.canCreateAccount {
                Button("I don't have my old device") { model.onboarding.noOldDevice() }
                    .accessibilityIdentifier("link.nodevice")
            }
        }
        .task(id: attempt) { await run() }
        .alert("Your contacts weren't copied", isPresented: $contactsNotCopied) {
            Button("OK", role: .cancel) { model.onboarding.linked() }
        } message: {
            Text("This device is linked, but it starts without your contacts.")
        }
        .alert("Server address", isPresented: $askingServer) {
            TextField("chat.example.org", text: $newHost)
            Button("Continue") { changeServer() }
            Button("Cancel", role: .cancel) {}
        }
    }

    /// Shows the code, then waits for the iPhone to approve it. When an approved sign-in is still waiting to
    /// be saved (a failed save, or the screen was left mid-retry) only the save is retried, with no new code.
    private func run() async {
        error = nil
        if host.isEmpty { host = model.account.currentServer?.host ?? model.account.defaultServer.host }
        do throws(AccountError) {
            if model.isSampleData {
                qrText = try await model.account.makeLinkCode().qrText
                return
            }
            if !model.account.linkSavePending { qrText = "" }
            let result = try await model.account.linkThisDevice { qrText = $0 }
            await model.didSignIn()
            if result.contactsCopied {
                model.onboarding.linked()
            } else {
                contactsNotCopied = true
            }
        } catch { self.error = error }
    }

    private func changeServer() {
        let target = newHost.trimmingCharacters(in: .whitespaces)
        guard !target.isEmpty else { return }
        Task {
            do throws(AccountError) {
                let policy = try await model.account.connect(host: target)
                host = policy.host
                attempt += 1
            } catch { self.error = error }
        }
    }
}

/// Sign in without the old device: passkey, or email and code. History starts empty.
struct LostDeviceView: View {
    private enum Field { case email, code }

    let model: AppModel
    @State private var email = ""
    @State private var codeSent = false
    @State private var code = ""
    @State private var error: AccountError?
    @State private var busy = false
    @FocusState private var focus: Field?

    private var canSubmit: Bool { email.contains("@") && (!codeSent || code.count == 6) && !busy }

    var body: some View {
        OnboardingFormPage(title: model.account.supportsPasskeys ? "Sign in on a new device" : "Sign in with an email code",
                           message: "Your account comes back, but old messages can only be restored from a backup.") {
            if model.account.supportsPasskeys {
                Section {
                    Button("Sign in with passkey", systemImage: "person.badge.key") { signInWithPasskey() }
                        .disabled(busy)
                        .accessibilityIdentifier("lost.passkey")
                } footer: {
                    Text("Or use your email")
                }
            }
            Section {
                TextField("Email", text: $email, prompt: Text("you@example.com"))
                    .textContentType(.emailAddress)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    #endif
                    .focused($focus, equals: .email)
                    .submitLabel(codeSent ? .next : .send)
                    .onSubmit { if codeSent { focus = .code } else if canSubmit { submit() } }
                    .accessibilityIdentifier("lost.email")
                if codeSent {
                    TextField("Code", text: $code, prompt: Text("123456"))
                        .textContentType(.oneTimeCode)
                        #if os(iOS)
                        .keyboardType(.numberPad)
                        #endif
                        .focused($focus, equals: .code)
                        .submitLabel(.done)
                        .onSubmit { if canSubmit { submit() } }
                        .accessibilityIdentifier("lost.code")
                }
            } footer: {
                AccountErrorText(error: error)
            }
        } primary: {
            Button(action: submit) { BusyLabel(title: codeSent ? "Sign in" : "Send code", busy: busy) }
                .disabled(!canSubmit)
                .accessibilityIdentifier("lost.submit")
        }
    }

    private func signInWithPasskey() {
        busy = true
        Task {
            defer { busy = false }
            do throws(AccountError) {
                try await model.account.signInWithPasskey()
                await model.didSignIn()
                model.onboarding.recovered()
            } catch { self.error = error }
        }
    }

    private func submit() {
        busy = true
        Task {
            defer { busy = false }
            do throws(AccountError) {
                if codeSent {
                    try await model.account.recover(email: email, code: code)
                    if model.account.supportsPasskeys {
                        // Adding a passkey is optional: cancelling (or failing) carries on signed in.
                        do throws(AccountError) { try await model.account.addPasskey() } catch {}
                    }
                    await model.didSignIn()
                    model.onboarding.recovered()
                } else {
                    try await model.account.sendRecoveryCode(to: email)
                    codeSent = true
                    error = nil
                    focus = .code
                }
            } catch { self.error = error }
        }
    }
}

struct QRCodeView: View {
    let text: String

    var body: some View {
        if let image = Self.render(text) {
            Image(decorative: image, scale: 1)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(12)
                .background(.white, in: RoundedRectangle(cornerRadius: 16))
        } else {
            Image(systemName: "qrcode").resizable().scaledToFit()
        }
    }

    private static func render(_ text: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        return CIContext().createCGImage(output, from: output.extent)
    }
}
