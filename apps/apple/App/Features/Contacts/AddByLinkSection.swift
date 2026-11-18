import AppCore
import ServerKit
import SwiftUI

/// Rows for the add-contact screen: My QR code, scan (iPhone and iPad), paste a link, and "Invite with a link".
/// A link taken here goes through `AppModel.handle(url:)`, the same road as a tapped universal link. The Mac pastes
/// or opens links only. The app never reads the pasteboard: the person pastes into the field.
struct AddByLinkSection: View {
    let model: AppModel
    /// Hands the link up and closes the add-contact sheet. The presenter keeps the URL and calls `model.handle(url:)`
    /// from its sheet's `onDismiss`, so every sheet is gone before the root presents the confirmation.
    let finish: (URL) -> Void

    @State private var showingMyCode = false
    @State private var showingScanner = false
    /// The scanned text, opened once the scanner sheet has finished closing (two sheets must not close at once).
    @State private var scanned: String?
    @State private var askingLink = false
    @State private var link = ""
    @State private var notALink = false
    @State private var making = false
    @State private var madeLink: SharedLink?
    @State private var linkFailure: NewLinkFailure?

    var body: some View {
        Section {
            Button { showingMyCode = true } label: { Label("My QR code", systemImage: "qrcode") }
                .accessibilityIdentifier("addcontact.myqr")
            #if os(iOS)
            Button { showingScanner = true } label: { Label("Scan QR code", systemImage: "qrcode.viewfinder") }
                .accessibilityIdentifier("addcontact.scan")
            #endif
            Button { link = ""; askingLink = true } label: { Label("Paste link", systemImage: "link") }
                .accessibilityIdentifier("addcontact.paste")
            Button(action: makeLink) { Label("Invite with a link", systemImage: "person.badge.plus") }
                .disabled(making)
                .accessibilityIdentifier("addcontact.invitelink")
        } footer: {
            if notALink {
                Text("That isn't a link for a person.").foregroundStyle(.red)
            } else if let linkFailure {
                Text(linkFailure == .tooMany
                     ? "You have too many unused links. Wait for one to expire."
                     : "Couldn't make a link. Try again.")
                    .foregroundStyle(.red)
                    .accessibilityIdentifier("addcontact.invitelink.error")
            }
        }
        .sheet(isPresented: $showingMyCode) { MyQRCodeView(model: model) }
        #if os(iOS)
        .sheet(isPresented: $showingScanner, onDismiss: {
            if let text = scanned {
                scanned = nil
                open(text)
            }
        }) {
            NavigationStack {
                QRScannerView { text in
                    scanned = text
                    showingScanner = false
                }
                .navigationTitle("Scan QR code")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { showingScanner = false } }
                }
            }
        }
        #endif
        // The camera permission prompt and the scanner can take the app away for a moment: the lock allows the return.
        .expectsReturn(when: showingScanner)
        .alert("Paste link", isPresented: $askingLink) {
            TextField("https://…", text: $link)
            Button("Open") { open(link) }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(item: $madeLink) { shared in
            InviteLinkSheet(url: shared.url)
        }
    }

    /// Hands a scanned or pasted text to the link road. Not a link of ours: say so here, nothing else happens.
    private func open(_ text: String) {
        notALink = false
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed),
              ContactLink.parse(url, serverHost: model.linkHost) != .notALink else {
            notALink = true
            return
        }
        link = ""
        finish(url)
    }

    private func makeLink() {
        making = true
        linkFailure = nil
        Task {
            switch await model.makeInviteLink() {
            case .success(let url): madeLink = SharedLink(url: url)
            case .failure(let failure): linkFailure = failure
            }
            making = false
        }
    }
}

private struct SharedLink: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

/// The new one-time link, ready to send. It is held only while this sheet is up.
private struct InviteLinkSheet: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Image(systemName: "link.circle.fill").font(.system(size: 64)).foregroundStyle(.tint).accessibilityHidden(true)
                Text("Your link is ready. It works once and lasts 7 days.")
                    .multilineTextAlignment(.center)
                ShareLink(item: url) { Label("Share", systemImage: "square.and.arrow.up") }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("invitelink.share")
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("Invite with a link")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        #if os(macOS)
        .frame(minWidth: 360, minHeight: 300)
        #endif
    }
}
