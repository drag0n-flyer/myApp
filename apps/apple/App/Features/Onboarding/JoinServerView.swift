import AppCore
import SwiftUI

struct JoinServerView: View {
    @Bindable var model: AppModel
    @State private var askingAddress = false
    @State private var askingLink = false
    @State private var address = ""
    @State private var link = ""
    @State private var badLink = false
    @State private var showingScanner = false
    @State private var error: AccountError?

    var body: some View {
        OnboardingPage(title: "Join a server", message: "Your chats live on a server. Use ours, or join one you trust.") {
            VStack(alignment: .leading, spacing: 10) {
                Button {
                    chooseDefault()
                } label: {
                    defaultCard
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("join.default")

                Text("Or join another server")
                    .font(.caption)
                    .textCase(.uppercase)
                    .foregroundStyle(.secondary)
                    .padding(.top, 10)

                option("qrcode.viewfinder", .blue, "Scan invite QR code", "From a friend's phone or a printed card", id: "join.scan") {
                    showingScanner = true
                }
                option("link", .green, "Paste invite link", "Opened automatically if you tapped a link", id: "join.paste") {
                    link = ""
                    askingLink = true
                }
                option("keyboard", .gray, "Enter server address", "For example chat.example.org", id: "join.address") {
                    address = ""
                    askingAddress = true
                }
                if badLink {
                    Text("That isn't an invite link.").font(.footnote).foregroundStyle(.red)
                }
                AccountErrorText(error: error)
            }
        } buttons: {
            EmptyView()
        }
        .onAppear {
            if let invite = model.pendingInvite {
                model.pendingInvite = nil
                join(invite)
            }
        }
        .sheet(isPresented: $showingScanner) {
            NavigationStack {
                QRScannerView { text in
                    showingScanner = false
                    if let url = URL(string: text) { accept(url) } else { badLink = true }
                }
                .navigationTitle("Scan invite QR code")
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { showingScanner = false } }
                }
            }
        }
        .alert("Paste invite link", isPresented: $askingLink) {
            TextField("https://…/invite/…", text: $link)
            Button("Join") { if let url = URL(string: link.trimmingCharacters(in: .whitespaces)) { accept(url) } }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Server address", isPresented: $askingAddress) {
            TextField("chat.example.org", text: $address)
            Button("Continue") {
                let host = address.trimmingCharacters(in: .whitespaces)
                if !host.isEmpty { chooseAddress(host) }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private var defaultCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Image(systemName: "circle.lefthalf.filled")
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(RoundedRectangle(cornerRadius: 12).fill(LinearGradient(colors: [.blue, .indigo], startPoint: .topLeading, endPoint: .bottomTrailing)))
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text("\(AppBrand.name) server").font(.headline)
                        Text("RECOMMENDED")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.tint)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Capsule().fill(Color.secondaryBackground))
                    }
                    Text("Run by the \(AppBrand.name) team").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.forward").foregroundStyle(.tertiary)
            }
            Text("Invite code needed during the beta")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.leading, 52)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color.accentColor.opacity(0.1)))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Color.accentColor, lineWidth: 1.5))
        .accessibilityElement(children: .combine)
    }

    private func option(_ symbol: String, _ color: Color, _ title: LocalizedStringKey, _ subtitle: LocalizedStringKey,
                        id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background(RoundedRectangle(cornerRadius: 10).fill(color))
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).foregroundStyle(.primary)
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.forward").foregroundStyle(.tertiary)
            }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color.secondaryBackground))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(id)
    }

    private func accept(_ url: URL) {
        if let invite = InviteLink.parse(url) {
            badLink = false
            join(invite)
        } else {
            badLink = true
        }
    }

    private func chooseDefault() {
        Task {
            do throws(AccountError) {
                let policy = try await model.account.connect(host: model.account.defaultServer.host)
                error = nil
                model.onboarding.choose(.defaultServer, requiresInvite: policy.requiresInvite)
            } catch { self.error = error }
        }
    }

    private func chooseAddress(_ host: String) {
        Task {
            do throws(AccountError) {
                let policy = try await model.account.connect(host: host)
                error = nil
                model.onboarding.choose(.address(host), requiresInvite: policy.requiresInvite)
            } catch { self.error = error }
        }
    }

    /// Connects to the invite's server and checks the code before moving on.
    private func join(_ invite: InviteLink) {
        Task {
            do throws(AccountError) {
                _ = try await model.account.connect(host: invite.host)
                _ = try await model.account.redeemInvite(code: invite.code)
                error = nil
                model.onboarding.choose(.invite(invite), requiresInvite: true)
            } catch { self.error = error }
        }
    }
}
