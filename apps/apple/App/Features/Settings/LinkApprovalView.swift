import AppCore
import SwiftUI

extension DevicePlatform {
    /// Plain name for the device type, used as text and as the VoiceOver label for its symbol.
    var displayName: String {
        switch self {
        case .mac: String(localized: "Mac")
        case .iPhone: String(localized: "iPhone")
        case .android: String(localized: "Android")
        }
    }
}

/// Shown on the signed-in iPhone after it scans a new device's code. Approving asks for Face ID or Touch ID.
struct LinkApprovalView: View {
    let request: LinkRequest
    let approve: () async -> AccountError?
    let cancel: () -> Void

    @State private var error: AccountError?
    @State private var working = false

    private var platformName: String {
        request.platform.displayName
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: request.platform.symbolName)
                .font(.system(size: 56))
                .foregroundStyle(.tint)
                .frame(maxWidth: .infinity)
                .accessibilityHidden(true)
            Text("Link this device?").font(.title.weight(.bold))

            VStack(alignment: .leading, spacing: 8) {
                LabeledContent("Device", value: request.deviceName)
                LabeledContent("Type", value: platformName)
                if let at = request.requestedAt {
                    LabeledContent("Asked") { Text(at, style: .relative) }
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("link.approval.details")

            Label {
                Text("This device will be able to read your future messages. Only approve a device that you own and can see right now.")
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
            .font(.callout)

            AccountErrorText(error: error)
            Spacer()
            Button {
                Task {
                    working = true
                    defer { working = false }
                    error = await approve()
                }
            } label: {
                Text("Approve").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(working)
            .accessibilityIdentifier("link.approve")
            Button("Don't approve", role: .cancel, action: cancel)
                .frame(maxWidth: .infinity, minHeight: 44)
                .accessibilityIdentifier("link.cancel")
        }
        .padding()
    }
}
