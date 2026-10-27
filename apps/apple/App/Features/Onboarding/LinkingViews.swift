import AppCore
import CoreImage.CIFilterBuiltins
import SwiftUI

/// The new device shows a QR code; the signed-in iPhone scans it (UI design §6.8).
struct LinkDeviceView: View {
    let model: AppModel

    var body: some View {
        OnboardingPage(title: "Link this device", message: "Open \(AppBrand.name) on your iPhone → Settings → Linked devices → Link a device, and scan this code.") {
            QRCodeView(text: model.account.linkingCode)
                .frame(width: 220, height: 220)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Linking QR code")
                .accessibilityIdentifier("link.qr")
        } buttons: {
            // Sample build: stands in for the iPhone confirming the scan.
            Button("I scanned it") { model.onboarding.linked() }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("link.done")
            if model.onboarding.canCreateAccount {
                Button("I don't have my old device") { model.onboarding.noOldDevice() }
                    .font(.headline)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("link.nodevice")
            }
        }
    }
}

/// Sign in without the old device: passkey, or email and code. History starts empty.
struct LostDeviceView: View {
    let model: AppModel
    @State private var email = ""
    @State private var codeSent = false
    @State private var code = ""
    @State private var wrong = false

    var body: some View {
        OnboardingPage(title: "Sign in on a new phone",
                       message: "Your account comes back, but old messages can only be restored from a backup.") {
            Button("Sign in with passkey", systemImage: "person.badge.key") { model.onboarding.linked() }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
            Text("Or use your email").font(.caption).foregroundStyle(.secondary).padding(.top, 8)
            OnboardingField(label: "Email") {
                TextField("you@example.com", text: $email)
                    .textContentType(.emailAddress)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    #endif
                    .accessibilityIdentifier("lost.email")
            }
            if codeSent {
                OnboardingField(label: "Code") {
                    TextField("123456", text: $code)
                        .textContentType(.oneTimeCode)
                        .accessibilityIdentifier("lost.code")
                }
                if wrong {
                    Text("That code didn't match. Try again.").font(.footnote).foregroundStyle(.red)
                }
            }
        } buttons: {
            Button(codeSent ? "Sign in" : "Send code") {
                Task {
                    if codeSent {
                        let ok = await model.account.verifyEmail(code: code)
                        wrong = !ok
                        if ok { model.onboarding.linked() }
                    } else {
                        await model.account.sendEmailCode(to: email)
                        codeSent = true
                    }
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!email.contains("@") || (codeSent && code.count != 6))
            .accessibilityIdentifier("lost.submit")
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
