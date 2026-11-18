import AppCore
import SwiftUI

/// "My QR code": the code for `https://<host>/u/<username>`, the username under it, and Share.
struct MyQRCodeView: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if let url = model.myProfileLinkURL {
                    QRCodeView(text: url.absoluteString)
                        .frame(maxWidth: 280)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("My QR code")
                        .accessibilityAddTraits(.isImage)
                        .accessibilityIdentifier("myqr.code")
                    Text("@\(model.me.username)")
                        .font(.title3.weight(.semibold))
                        .accessibilityIdentifier("myqr.username")
                    ShareLink(item: url) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("myqr.share")
                } else {
                    ContentUnavailableView("Your code isn't ready", systemImage: "qrcode")
                }
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("My QR code")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        #if os(macOS)
        .frame(minWidth: 360, minHeight: 440)
        #endif
    }
}

/// A row for the profile page that opens "My QR code".
struct MyQRCodeRow: View {
    let model: AppModel
    @State private var showing = false

    var body: some View {
        Button { showing = true } label: {
            Label("My QR code", systemImage: "qrcode")
        }
        .foregroundStyle(.primary)
        .accessibilityIdentifier("profile.qr")
        .sheet(isPresented: $showing) { MyQRCodeView(model: model) }
    }
}
